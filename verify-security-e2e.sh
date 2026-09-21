#!/usr/bin/env bash
# Full resource server acceptance against real packaged sample and UPMS processes.
set -euo pipefail
SERVICE_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SERVICE_DIR/scripts/security-test-runtime.sh"
if [ -f "$SERVICE_DIR/mars-cloud-sample-service/.env" ]; then
  set -a
  . "$SERVICE_DIR/mars-cloud-sample-service/.env"
  set +a
fi
: "${NACOS_NAMESPACE_ID:?Nacos namespace is required}"
: "${NACOS_USERNAME:?Nacos username is required}"
: "${NACOS_PASSWORD:?Nacos password is required}"
SAMPLE_PORT="${SAMPLE_PORT:-8103}"
UPMS_PORT="${UPMS_PORT:-8102}"
SAMPLE="http://127.0.0.1:$SAMPLE_PORT/sample"
UPMS="http://127.0.0.1:$UPMS_PORT/upms"
SAMPLE_JAR="$SERVICE_DIR/mars-cloud-sample-service/target/mars-cloud-sample-service.jar"
UPMS_JAR="$SERVICE_DIR/mars-cloud-upms-service/target/mars-cloud-upms-service.jar"
[ -f "$SAMPLE_JAR" ] && [ -f "$UPMS_JAR" ] || { echo "Build sample and UPMS first"; exit 2; }
LOG_DIR="${SECURITY_E2E_LOG_DIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"
JVM_FLAGS=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED)
python3 - "$SAMPLE_PORT" "$UPMS_PORT" <<'PYCODE'
import socket, sys
for value in sys.argv[1:]:
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', int(value)))
PYCODE
stop_owned() {
  local process_id="$1"
  if [ -n "$process_id" ] && kill -0 "$process_id" 2>/dev/null; then
    kill "$process_id" 2>/dev/null || true
    wait "$process_id" 2>/dev/null || true
  fi
}
cleanup() {
  stop_owned "${SAMPLE_PID:-}"
  stop_owned "${UPMS_PID:-}"
  stop_security_test_issuer
}
trap cleanup EXIT
start_security_test_issuer "$SERVICE_DIR"
wait_ready() {
  local url="$1" process_id="$2" count=0
  until command curl -fsS "$url/actuator/health" >/dev/null 2>&1; do
    kill -0 "$process_id" 2>/dev/null || { echo "Service exited before readiness; inspect $LOG_DIR"; return 1; }
    count=$((count + 1))
    [ "$count" -lt 90 ] || { echo "Service readiness timed out"; return 1; }
    sleep 1
  done
}
SERVER_PORT="$UPMS_PORT" java "${JVM_FLAGS[@]}" -jar "$UPMS_JAR" >"$LOG_DIR/upms.log" 2>&1 &
UPMS_PID=$!
wait_ready "$UPMS" "$UPMS_PID"
SERVER_PORT="$SAMPLE_PORT" java "${JVM_FLAGS[@]}" -jar "$SAMPLE_JAR" >"$LOG_DIR/sample-development.log" 2>&1 &
SAMPLE_PID=$!
wait_ready "$SAMPLE" "$SAMPLE_PID"
python3 "$SERVICE_DIR/scripts/verify-security-http.py" "$SAMPLE" "$UPMS" "$SECURITY_TEST_DIR" development
stop_owned "$SAMPLE_PID"
SAMPLE_PID=""
SERVER_PORT="$SAMPLE_PORT" MARS_ENV_DEV_PROFILES=disabled java "${JVM_FLAGS[@]}" -jar "$SAMPLE_JAR" >"$LOG_DIR/sample-production.log" 2>&1 &
SAMPLE_PID=$!
wait_ready "$SAMPLE" "$SAMPLE_PID"
python3 "$SERVICE_DIR/scripts/verify-security-http.py" "$SAMPLE" "$UPMS" "$SECURITY_TEST_DIR" production
stop_owned "$UPMS_PID"
UPMS_PID=""
python3 "$SERVICE_DIR/scripts/verify-security-http.py" "$SAMPLE" "$UPMS" "$SECURITY_TEST_DIR" unavailable
verify_no_test_credentials "$LOG_DIR/upms.log" "$LOG_DIR/sample-development.log" "$LOG_DIR/sample-production.log"
python3 - "$LOG_DIR" <<'PYCODE'
import pathlib, sys
for path in pathlib.Path(sys.argv[1]).glob('*.log'):
    if any(value in path.read_text() for value in ('sentinel-proxy-secret', 'sentinel-cookie-secret', 'sentinel-set-cookie-secret', 'sentinel-api-secret')):
        raise SystemExit('Credential sentinel appeared in log: ' + str(path))
print('Credential sentinel log check passed')
PYCODE
printf 'Security acceptance passed. Logs: %s\n' "$LOG_DIR"
