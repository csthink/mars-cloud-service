#!/usr/bin/env bash
# Run the packaged Gateway against a disposable Redis process and a local HTTP upstream.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=SCRIPTDIR/../scripts/security-test-runtime.sh
. "$MODULE_DIR/../scripts/security-test-runtime.sh"

GATEWAY_JAR="$MODULE_DIR/target/mars-cloud-gateway.jar"
GATEWAY_PORT="${REVOCATION_GATEWAY_PORT:-28300}"
UPSTREAM_PORT="${REVOCATION_UPSTREAM_PORT:-28301}"
REDIS_PORT="${REVOCATION_REDIS_PORT:-26380}"
MANAGEMENT_PORT="$((GATEWAY_PORT + 1000))"
REDIS_CONTAINER="mars-cloud-gateway-revocation-$$"
REDIS_IMAGE="$(python3 - "$MODULE_DIR/../dev/images.lock.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))['images']['redis']['digest'])
PY
)"
WORK_DIR="$(mktemp -d)"
RESPONSE="$WORK_DIR/response"

cleanup() {
  if [ -n "${GATEWAY_PID:-}" ]; then kill "$GATEWAY_PID" 2>/dev/null || true; wait "$GATEWAY_PID" 2>/dev/null || true; fi
  if [ -n "${UPSTREAM_PID:-}" ]; then kill "$UPSTREAM_PID" 2>/dev/null || true; wait "$UPSTREAM_PID" 2>/dev/null || true; fi
  docker rm -f "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  stop_security_test_issuer
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [ ! -f "$GATEWAY_JAR" ]; then
  echo "Build mars-cloud-gateway before running this check." >&2
  exit 2
fi
if ! docker image inspect "$REDIS_IMAGE" >/dev/null 2>&1; then
  echo "Load the Redis image declared by dev/images.lock.json before running this check." >&2
  exit 2
fi
python3 - "$GATEWAY_PORT" "$MANAGEMENT_PORT" "$UPSTREAM_PORT" "$REDIS_PORT" <<'PY'
import socket, sys
for raw in sys.argv[1:]:
    port = int(raw)
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(('127.0.0.1', port))
        except OSError as exc:
            raise SystemExit(f'Port {port} is unavailable: {exc}')
PY

docker run --pull=never -d --name "$REDIS_CONTAINER" -p "127.0.0.1:$REDIS_PORT:6379" \
  "$REDIS_IMAGE" redis-server --save '' --appendonly no >/dev/null
wait_for_redis() {
  for _ in $(seq 1 50); do
    if [ "$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null || true)" = PONG ]; then return 0; fi
    sleep 0.2
  done
  echo "Disposable Redis did not become ready." >&2
  return 1
}
wait_for_redis

mkdir -p "$WORK_DIR/upstream/auth/v1"
printf 'ok\n' >"$WORK_DIR/upstream/auth/v1/me"
printf 'ok\n' >"$WORK_DIR/upstream/login"
python3 -u -m http.server "$UPSTREAM_PORT" --bind 127.0.0.1 \
  --directory "$WORK_DIR/upstream" >"$WORK_DIR/upstream.log" 2>&1 &
UPSTREAM_PID=$!
for _ in $(seq 1 50); do
  if curl --silent --fail "http://127.0.0.1:$UPSTREAM_PORT/login" >/dev/null; then break; fi
  sleep 0.1
done

start_security_test_issuer "$MODULE_DIR/.."
SPRING_PROFILES_ACTIVE=local,test \
SPRING_CLOUD_NACOS_CONFIG_ENABLED=false SPRING_CLOUD_NACOS_CONFIG_IMPORT_CHECK_ENABLED=false \
SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false \
SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT="$REDIS_PORT" SPRING_DATA_REDIS_DATABASE=2 \
SERVER_PORT="$GATEWAY_PORT" MARS_SECURITY_ISSUER_URI="$MARS_SECURITY_ISSUER_URI" \
MARS_SECURITY_JWK_SET_URI="$MARS_SECURITY_JWK_SET_URI" \
java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -jar "$GATEWAY_JAR" \
  "--spring.cloud.discovery.client.simple.instances.mars-cloud-auth-service[0].uri=http://127.0.0.1:$UPSTREAM_PORT" \
  >"$WORK_DIR/gateway.log" 2>&1 &
GATEWAY_PID=$!
ready=false
for _ in $(seq 1 120); do
  if curl --silent --fail "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health" >/dev/null; then ready=true; break; fi
  kill -0 "$GATEWAY_PID" 2>/dev/null || break
  sleep 0.25
done
if [ "$ready" != true ]; then
  tail -n 25 "$WORK_DIR/gateway.log" >&2
  echo "Gateway did not become healthy." >&2
  exit 1
fi

request() {
  local path="$1" token="$2" host="$3"
  local headers=()
  if [ "$token" != none ]; then headers+=(--header "@$SECURITY_TEST_DIR/$token.headers"); fi
  curl --silent --show-error --max-time 10 --output "$RESPONSE" --write-out '%{http_code}' \
    --header "Host: $host" "${headers[@]}" "http://127.0.0.1:$GATEWAY_PORT$path"
}
expect() {
  local label="$1" path="$2" token="$3" host="$4" expected_status="$5" expected_code="$6"
  local actual_status actual_code
  actual_status="$(request "$path" "$token" "$host")"
  if [ "$actual_status" != "$expected_status" ]; then
    echo "$label: expected HTTP $expected_status, got $actual_status" >&2
    exit 1
  fi
  if [ "$expected_code" != none ]; then
    actual_code="$(python3 - "$RESPONSE" <<'PY'
import json, sys
print(json.load(open(sys.argv[1])).get('code'))
PY
)"
    if [ "$actual_code" != "$expected_code" ]; then
      echo "$label: expected code $expected_code, got $actual_code" >&2
      exit 1
    fi
  fi
  echo "ok $label"
}
upstream_count() { wc -l <"$WORK_DIR/upstream.log" | tr -d ' '; }

expect "unrevoked session" /auth/v1/me allow api.flippoabc.com 200 none
sid="$(python3 - "$SECURITY_TEST_DIR/allow.token" <<'PY'
import base64, json, pathlib, sys
part = pathlib.Path(sys.argv[1]).read_text().split('.')[1]
print(json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))['sid'])
PY
)"
docker exec "$REDIS_CONTAINER" redis-cli -n 2 SET "mars:auth:revoked:sid:$sid" 1 EX 900 >/dev/null
sleep 6
before="$(upstream_count)"
expect "revoked session" /auth/v1/me allow api.flippoabc.com 401 62002
expect "missing sid" /auth/v1/me missing-sid api.flippoabc.com 401 62002
expect "invalid sid" /auth/v1/me invalid-sid api.flippoabc.com 401 62002
if [ "$(upstream_count)" != "$before" ]; then echo "Rejected requests reached the upstream." >&2; exit 1; fi

expect "second session before Redis stop" /auth/v1/me admin api.flippoabc.com 200 none
docker stop --time 1 "$REDIS_CONTAINER" >/dev/null
sleep 6
expect "Redis unavailable after cache expiry" /auth/v1/me admin api.flippoabc.com 503 63005
expect "issuer route while Redis unavailable" /login none auth.flippoabc.com 200 none
docker start "$REDIS_CONTAINER" >/dev/null
wait_for_redis
expect "Redis recovered" /auth/v1/me admin api.flippoabc.com 200 none
verify_no_test_credentials "$WORK_DIR/gateway.log" "$WORK_DIR/upstream.log"
echo "Gateway revocation acceptance passed."
