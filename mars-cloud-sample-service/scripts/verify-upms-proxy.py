"""本地验收代理：观察成功转发至真实 UPMS 的固定演示身份头。"""
import http.client
import http.server
import json
import pathlib
import sys

upms_port = int(sys.argv[1])
output_dir = pathlib.Path(sys.argv[2])
header_names = ("X-Mars-Subject", "X-Mars-Client-Id", "X-Mars-Tenant-Id")


class Proxy(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        connection = http.client.HTTPConnection("127.0.0.1", upms_port, timeout=5)
        try:
            headers = {key: value for key, value in self.headers.items()
                       if key.lower() not in ("host", "connection", "transfer-encoding")}
            connection.request("POST", self.path, body, headers)
            response = connection.getresponse()
            payload = response.read()
            if response.status == 200 and self.path == "/upms/v1/decision":
                observed = {name: self.headers.get_all(name, []) for name in header_names}
                temporary = output_dir / "headers.tmp"
                temporary.write_text(json.dumps(observed))
                temporary.replace(output_dir / "headers.json")
            self.send_response(response.status)
            self.send_header("Content-Type", response.getheader("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except OSError:
            self.send_error(502)
        finally:
            connection.close()

    def log_message(self, *_args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Proxy)
(output_dir / "port").write_text(str(server.server_port))
server.serve_forever()
