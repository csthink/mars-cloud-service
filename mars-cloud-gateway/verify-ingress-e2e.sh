#!/usr/bin/env bash
# shellcheck disable=SC2030,SC2031
# 各服务在独立子进程中设置自己的 SERVER_PORT。
# 以独立进程核对网关来源地址、auth-service 可信网关接入与 sample 路由。
set -euo pipefail

module_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(cd "$module_dir/.." && pwd)
AUTH_ENV_FILE=${INGRESS_E2E_AUTH_ENV_FILE:-$repo_dir/mars-cloud-auth-service/.env}
GATEWAY_ENV_FILE=${INGRESS_E2E_GATEWAY_ENV_FILE:-$module_dir/.env}
SAMPLE_ENV_FILE=${INGRESS_E2E_SAMPLE_ENV_FILE:-$repo_dir/mars-cloud-sample-service/.env}
AUTH_PORT=${INGRESS_E2E_AUTH_PORT:-8301}
GATEWAY_PORT=${INGRESS_E2E_GATEWAY_PORT:-8300}
SAMPLE_PORT=${INGRESS_E2E_SAMPLE_PORT:-8303}
: "${INGRESS_E2E_CA_FILE:?Set INGRESS_E2E_CA_FILE to the local auth-service CA certificate}"
: "${INGRESS_E2E_TRUST_STORE:?Set INGRESS_E2E_TRUST_STORE to a PKCS12 trust store containing that CA}"
: "${INGRESS_E2E_TRUST_PASSWORD:?Set INGRESS_E2E_TRUST_PASSWORD for the local trust store}"
for path in "$AUTH_ENV_FILE" "$GATEWAY_ENV_FILE" "$SAMPLE_ENV_FILE" "$INGRESS_E2E_CA_FILE" "$INGRESS_E2E_TRUST_STORE" \
        "$repo_dir/mars-cloud-auth-service/target/mars-cloud-auth-service.jar" \
        "$module_dir/target/mars-cloud-gateway.jar" \
        "$repo_dir/mars-cloud-sample-service/target/mars-cloud-sample-service.jar"; do
    if [ ! -f "$path" ]; then printf 'Missing input: %s\n' "$path" >&2; exit 2; fi
done

for port in "$AUTH_PORT" "$((AUTH_PORT + 1000))" "$GATEWAY_PORT" "$((GATEWAY_PORT + 1000))" \
        "$SAMPLE_PORT" "$((SAMPLE_PORT + 1000))"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | rg -q .; then
        printf 'Port %s is already in use\n' "$port" >&2; exit 2
    fi
done

log_dir=$(mktemp -d -t gateway-ingress-e2e)
AUTH_PID='' GATEWAY_PID='' SAMPLE_PID=''
cleanup() {
    for pid in "$GATEWAY_PID" "$SAMPLE_PID" "$AUTH_PID"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
    done
}
trap cleanup EXIT
jvm_flags=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED)
trust_flags=(-Djavax.net.ssl.trustStore="$INGRESS_E2E_TRUST_STORE" -Djavax.net.ssl.trustStoreType=PKCS12
    -Djavax.net.ssl.trustStorePassword="$INGRESS_E2E_TRUST_PASSWORD")

wait_health() {
    local name=$1 port=$2 attempt
    for ((attempt=0; attempt<60; attempt++)); do
        if curl -fsS --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    printf '%s did not become healthy; log: %s/%s.log\n' "$name" "$log_dir" "$name" >&2
    return 1
}
status() { curl -sS --max-time 8 -o /dev/null -w '%{http_code}' "$@" || true; }
check() {
    local label=$1 expected=$2 actual=$3
    if [ "$actual" != "$expected" ]; then
        printf 'FAIL %s: expected %s, got %s\n' "$label" "$expected" "$actual" >&2
        exit 1
    fi
    printf 'ok %s: %s\n' "$label" "$actual"
}

(
    set -a
    # shellcheck disable=SC1090
    . "$AUTH_ENV_FILE"
    set +a
    export SERVER_PORT="$AUTH_PORT"
    exec java "${jvm_flags[@]}" -jar "$repo_dir/mars-cloud-auth-service/target/mars-cloud-auth-service.jar"
) >"$log_dir/auth.log" 2>&1 & AUTH_PID=$!
wait_health auth "$((AUTH_PORT + 1000))"
check 'trusted direct auth request without canonical headers' 400 \
    "$(status --cacert "$INGRESS_E2E_CA_FILE" "https://127.0.0.1:$AUTH_PORT/login")"

(
    set -a
    # shellcheck disable=SC1090
    . "$SAMPLE_ENV_FILE"
    set +a
    export SERVER_PORT="$SAMPLE_PORT"
    export MARS_SECURITY_ISSUER_URI="https://127.0.0.1:$AUTH_PORT"
    export MARS_SECURITY_JWK_SET_URI="https://127.0.0.1:$AUTH_PORT/.well-known/jwks.json"
    exec java "${jvm_flags[@]}" "${trust_flags[@]}" -jar "$repo_dir/mars-cloud-sample-service/target/mars-cloud-sample-service.jar"
) >"$log_dir/sample.log" 2>&1 & SAMPLE_PID=$!
wait_health sample "$((SAMPLE_PORT + 1000))"

start_gateway() {
    local cidrs=$1
    (
        set -a
        # shellcheck disable=SC1090
        . "$GATEWAY_ENV_FILE"
        set +a
        export SERVER_PORT="$GATEWAY_PORT"
        export MARS_GATEWAY_TRUSTED_HOP_COUNT=1
        export MARS_GATEWAY_DIRECT_PEER_CIDRS="$cidrs"
        exec java "${jvm_flags[@]}" "${trust_flags[@]}" -jar "$module_dir/target/mars-cloud-gateway.jar"
    ) >"$log_dir/gateway.log" 2>&1 & GATEWAY_PID=$!
    wait_health gateway "$((GATEWAY_PORT + 1000))"
}
base="http://127.0.0.1:$GATEWAY_PORT"
start_gateway '127.0.0.1/32'
check 'trusted login route' 200 "$(status -H 'Host: auth.flippoabc.com' \
    -H 'X-Forwarded-For: 198.51.100.77, 192.0.2.23' -H 'X-Forwarded-Proto: https' \
    -H 'X-Forwarded-Host: attacker.example' -H 'X-Mars-Subject: fake' "$base/login")"
check 'missing source chain' 400 "$(status -H 'Host: auth.flippoabc.com' -H 'X-Forwarded-Proto: https' "$base/login")"
check 'repeated source header' 400 "$(status -H 'Host: auth.flippoabc.com' -H 'X-Forwarded-For: 192.0.2.23' \
    -H 'X-Forwarded-For: 192.0.2.24' -H 'X-Forwarded-Proto: https' "$base/login")"
check 'invalid source address' 400 "$(status -H 'Host: auth.flippoabc.com' -H 'X-Forwarded-For: example.com' \
    -H 'X-Forwarded-Proto: https' "$base/login")"
check 'missing protocol header' 400 "$(status -H 'Host: auth.flippoabc.com' -H 'X-Forwarded-For: 192.0.2.23' "$base/login")"

for ((attempt=0; attempt<60; attempt++)); do
    sample_status=$(status -H 'Host: api.flippoabc.com' -H 'X-Forwarded-For: 192.0.2.23' \
        -H 'X-Forwarded-Proto: https' "$base/sample/v1/security/me")
    [ "$sample_status" = 401 ] && break
    sleep 1
done
check 'sample route reaches protected service' 401 "$sample_status"
check 'sample route rejects auth Host' 404 "$(status -H 'Host: auth.flippoabc.com' \
    -H 'X-Forwarded-For: 192.0.2.23' -H 'X-Forwarded-Proto: https' "$base/sample/v1/security/me")"

kill "$GATEWAY_PID"; wait "$GATEWAY_PID" 2>/dev/null || true; GATEWAY_PID=
start_gateway '192.0.2.0/24'
check 'untrusted TCP peer' 403 "$(status -H 'Host: auth.flippoabc.com' \
    -H 'X-Forwarded-For: 192.0.2.23' -H 'X-Forwarded-Proto: https' "$base/login")"
check 'management port ignores proxy headers' 200 \
    "$(status "http://127.0.0.1:$((GATEWAY_PORT + 1000))/actuator/health")"
printf 'Gateway ingress process checks passed\n'
