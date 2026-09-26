#!/usr/bin/env python3
"""Publish, replace or delete the gateway Sentinel rule configurations in the Nacos namespace of an env file.

publish            writes every *-rules.json file of the local baseline in dev/config/sentinel
put DATA_ID FILE   writes one data ID from a file (use - for standard input)
delete DATA_ID     deletes one data ID

The Nacos address, namespace and account come from the env file (NACOS_SERVER_ADDR, NACOS_NAMESPACE_ID,
NACOS_USERNAME, NACOS_PASSWORD). NACOS_SERVER_ADDR is one host:port without a scheme. Credentials are never printed.
Connection, HTTP, JSON and file errors end with one line on standard error and a non-zero exit status.
"""
import argparse
import http.client
import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

GROUP = "SENTINEL_GROUP"
# The same files seed the base namespace and fill in the missing rule configurations of the numbered
# environments when the local middleware is initialized (dev/middleware_init.py).
RULES_DIR = pathlib.Path(__file__).resolve().parent.parent / "dev" / "config" / "sentinel"


def read_env(path):
    values = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    for key in ("NACOS_NAMESPACE_ID", "NACOS_USERNAME", "NACOS_PASSWORD"):
        if not values.get(key):
            sys.exit(f"{key} is empty in {path}")
    return values


class Nacos:
    def __init__(self, env):
        address = env.get("NACOS_SERVER_ADDR") or "127.0.0.1:8848"
        if "://" in address or "," in address or "/" in address:
            sys.exit(f"NACOS_SERVER_ADDR must be one host:port without a scheme or path, got {address!r}")
        self.base = "http://" + address
        self.namespace = env["NACOS_NAMESPACE_ID"]
        self.token = self.call("POST", "/nacos/v3/auth/user/login",
                               {"username": env["NACOS_USERNAME"], "password": env["NACOS_PASSWORD"]})["accessToken"]

    def call(self, method, path, params):
        url, data, headers = self.base + path, None, {}
        if getattr(self, "token", None):
            headers["accessToken"] = self.token
        if method in ("GET", "DELETE"):
            url += "?" + urllib.parse.urlencode(params)
        else:
            data = urllib.parse.urlencode(params).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            sys.exit(f"Nacos {method} {path} returned HTTP {error.code}")
        except urllib.error.URLError as error:
            sys.exit(f"Nacos {method} {path} failed: {error.reason}")
        except (OSError, http.client.HTTPException) as error:
            sys.exit(f"Nacos {method} {path} failed: {error}")
        except json.JSONDecodeError:
            sys.exit(f"Nacos {method} {path} returned a body that is not JSON")
        if isinstance(body, dict) and body.get("code", 0) != 0:
            sys.exit(f"Nacos {method} {path} failed with code {body.get('code')}")
        return body.get("data", body) if isinstance(body, dict) else body

    def put(self, data_id, content, source):
        try:
            json.loads(content)  # a malformed file is a local mistake, not something to publish
        except json.JSONDecodeError as error:
            sys.exit(f"{source} is not valid JSON: {error}")
        self.call("POST", "/nacos/v3/admin/cs/config",
                  {"namespaceId": self.namespace, "groupName": GROUP, "dataId": data_id,
                   "content": content, "type": "json"})
        print(f"published {GROUP}/{data_id} in {self.namespace}")

    def delete(self, data_id):
        self.call("DELETE", "/nacos/v3/admin/cs/config",
                  {"namespaceId": self.namespace, "groupName": GROUP, "dataId": data_id})
        print(f"deleted {GROUP}/{data_id} in {self.namespace}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env-file", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("publish")
    put = commands.add_parser("put")
    put.add_argument("data_id")
    put.add_argument("file")
    delete = commands.add_parser("delete")
    delete.add_argument("data_id")
    args = parser.parse_args()

    nacos = Nacos(read_env(args.env_file))
    if args.command == "publish":
        files = sorted(RULES_DIR.glob("*-rules.json"))
        if not files:
            sys.exit(f"no rule files in {RULES_DIR}")
        for file in files:
            nacos.put(file.name, file.read_text(encoding="utf-8"), file)
    elif args.command == "put":
        content = sys.stdin.read() if args.file == "-" else pathlib.Path(args.file).read_text(encoding="utf-8")
        nacos.put(args.data_id, content, "standard input" if args.file == "-" else args.file)
    else:
        nacos.delete(args.data_id)


if __name__ == "__main__":
    try:
        main()
    except OSError as error:
        sys.exit(f"cannot read {getattr(error, 'filename', '') or 'input'}: {error.strerror or error}")
