#!/usr/bin/env bash
# shellcheck disable=SC2030,SC2031
# 各服务在独立子进程中设置自己的 SERVER_PORT。
# 以独立的认证服务与网关进程核对设备上限：被踢出的设备经网关得到 62007/401，其余设备照常通过。
set -euo pipefail

module_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(cd "$module_dir/.." && pwd)
AUTH_ENV_FILE=${SESSION_E2E_AUTH_ENV_FILE:-$module_dir/.env}
GATEWAY_ENV_FILE=${SESSION_E2E_GATEWAY_ENV_FILE:-$repo_dir/mars-cloud-gateway/.env}
AUTH_PORT=${SESSION_E2E_AUTH_PORT:-8301}
GATEWAY_PORT=${SESSION_E2E_GATEWAY_PORT:-8300}
: "${SESSION_E2E_CA_FILE:?Set SESSION_E2E_CA_FILE to the local auth-service CA certificate}"
: "${SESSION_E2E_TRUST_STORE:?Set SESSION_E2E_TRUST_STORE to a PKCS12 trust store containing that CA}"
: "${SESSION_E2E_TRUST_PASSWORD:?Set SESSION_E2E_TRUST_PASSWORD for the local trust store}"
for path in "$AUTH_ENV_FILE" "$GATEWAY_ENV_FILE" "$SESSION_E2E_CA_FILE" "$SESSION_E2E_TRUST_STORE" \
        "$module_dir/target/mars-cloud-auth-service.jar" "$repo_dir/mars-cloud-gateway/target/mars-cloud-gateway.jar"; do
    if [ ! -f "$path" ]; then printf 'Missing input: %s\n' "$path" >&2; exit 2; fi
done
for port in "$AUTH_PORT" "$((AUTH_PORT + 1000))" "$GATEWAY_PORT" "$((GATEWAY_PORT + 1000))"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | rg -q .; then
        printf 'Port %s is already in use\n' "$port" >&2; exit 2
    fi
done

log_dir=$(mktemp -d -t auth-session-e2e)
chmod 700 "$log_dir"
AUTH_PID='' GATEWAY_PID=''
cleanup() {
    for pid in "$GATEWAY_PID" "$AUTH_PID"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
    done
}
trap cleanup EXIT
jvm_flags=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED)
trust_flags=(-Djavax.net.ssl.trustStore="$SESSION_E2E_TRUST_STORE" -Djavax.net.ssl.trustStoreType=PKCS12
    -Djavax.net.ssl.trustStorePassword="$SESSION_E2E_TRUST_PASSWORD")

wait_health() {
    local name=$1 port=$2 attempt
    for ((attempt=0; attempt<90; attempt++)); do
        if curl -fsS --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    printf '%s did not become healthy; log: %s/%s.log\n' "$name" "$log_dir" "$name" >&2
    return 1
}

(
    set -a
    # shellcheck disable=SC1090
    . "$AUTH_ENV_FILE"
    set +a
    export SERVER_PORT="$AUTH_PORT"
    exec java "${jvm_flags[@]}" -jar "$module_dir/target/mars-cloud-auth-service.jar"
) >"$log_dir/auth.log" 2>&1 & AUTH_PID=$!
wait_health auth "$((AUTH_PORT + 1000))"

(
    set -a
    # shellcheck disable=SC1090
    . "$GATEWAY_ENV_FILE"
    set +a
    export SERVER_PORT="$GATEWAY_PORT"
    export MARS_GATEWAY_TRUSTED_HOP_COUNT=1
    export MARS_GATEWAY_DIRECT_PEER_CIDRS='127.0.0.1/32'
    export MARS_SECURITY_ISSUER_URI="https://127.0.0.1:$AUTH_PORT"
    export MARS_SECURITY_JWK_SET_URI="https://127.0.0.1:$AUTH_PORT/.well-known/jwks.json"
    exec java "${jvm_flags[@]}" "${trust_flags[@]}" -jar "$repo_dir/mars-cloud-gateway/target/mars-cloud-gateway.jar"
) >"$log_dir/gateway.log" 2>&1 & GATEWAY_PID=$!
wait_health gateway "$((GATEWAY_PORT + 1000))"

# The gateway discovers auth-service through Nacos; wait until the API route reaches it.
for ((attempt=0; attempt<60; attempt++)); do
    status=$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' -H 'Host: api.flippoabc.com' \
        -H 'X-Forwarded-For: 203.0.113.10' -H 'X-Forwarded-Proto: https' "http://127.0.0.1:$GATEWAY_PORT/auth/v1/me" || true)
    [ "$status" = 401 ] && break
    sleep 1
done
if [ "$status" != 401 ]; then printf 'Gateway did not route to auth-service (last status %s)\n' "$status" >&2; exit 1; fi

(
    set -a
    # shellcheck disable=SC1090
    . "$AUTH_ENV_FILE"
    set +a
    python3 "$module_dir/scripts/verify_devices.py" --base "https://127.0.0.1:$AUTH_PORT" --ca "$SESSION_E2E_CA_FILE" \
        --gateway "http://127.0.0.1:$GATEWAY_PORT"
)
# shellcheck disable=SC1090
password=$(set -a; . "$AUTH_ENV_FILE"; set +a; printf '%s' "${MARS_AUTH_LOCAL_LOGIN_PASSWORD:-}")
if [ -n "$password" ] && grep -qF -- "$password" "$log_dir/auth.log" "$log_dir/gateway.log"; then
    printf 'A credential appeared in process logs\n' >&2; exit 1
fi
printf 'Device session acceptance passed; private logs: %s\n' "$log_dir"
