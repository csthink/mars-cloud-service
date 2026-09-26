#!/usr/bin/env bash
# 网关限流的真进程验收：以 java -jar 启动网关，规则经 Nacos 下发。
#
# 核对：缺少规则配置时启动失败；规则修改后不重启即生效；按客户端地址分别计数（地址取自可信代理给出的
# X-Forwarded-For，TCP 对端相同）；回调路径按 API 分组计总数；认证入口分组按客户端地址计数；拦截次数经管理端口的
# prometheus 端点以 Basic 凭据可读、匿名不可读；坏规则与删除配置都保留上一批规则；重启后规则仍在；
# 被拒绝的响应是 429 与码 63006；网关只监听业务端口与管理端口；Sentinel 不写任何文件。
#
# 只请求不需要访问令牌的路径（product 的匿名读取、order 的回调、认证 Host 的签发方端点），放行的请求打到没有实例的
# 目标服务、以 503 结束，不需要其他服务。本验收不核对令牌、不查询 Redis：签发方地址取 .env 的 MARS_SECURITY_ISSUER_URI，
# 未提供时用回环占位地址；本进程关闭 Redis 健康检查项，部署物的其他配置不变。
#
# 前置：本机 Nacos 已启动，命名空间里已有网关的应用配置；本目录有 .env（或用 SENTINEL_E2E_ENV_FILE 指定），
# 其中有管理端点凭据 MARS_MANAGEMENT_USERNAME 与 MARS_MANAGEMENT_PASSWORD；已 mvn package 出 target/mars-cloud-gateway.jar。
# 脚本会改写 .env 所指命名空间里的两个规则配置，结束时把基线规则（dev/config/sentinel/）写回；写回失败时退出码非零。
set -euo pipefail

module_dir=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=${SENTINEL_E2E_ENV_FILE:-$module_dir/.env}
JAR=$module_dir/target/mars-cloud-gateway.jar
for path in "$ENV_FILE" "$JAR"; do
    if [ ! -f "$path" ]; then printf 'Missing input: %s\n' "$path" >&2; exit 2; fi
done
set -a
# 环境文件是本机数据，不是要检查的脚本。
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
for name in MARS_MANAGEMENT_USERNAME MARS_MANAGEMENT_PASSWORD; do
    if [ -z "${!name:-}" ]; then printf 'Missing %s in %s\n' "$name" "$ENV_FILE" >&2; exit 2; fi
done
GATEWAY_PORT=${GATEWAY_PORT:-8100}
MANAGEMENT_PORT=$((GATEWAY_PORT + 1000))
for port in "$GATEWAY_PORT" "$MANAGEMENT_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
        printf 'Port %s is already in use\n' "$port" >&2; exit 2
    fi
done

FLOW_ID=mars-cloud-gateway-sentinel-gw-flow-rules.json
rules=(python3 "$module_dir/sentinel-rules.py" --env-file "$ENV_FILE")
work=$(mktemp -d -t gateway-sentinel-e2e)
# 两个目录预先建好：Sentinel 如果写文件就会写在这里，结束时核对它们仍是空的
mkdir -p "$work/sentinel-log-dir" "$work/eagleeye-log-dir"
GATEWAY_PID=''
passed=0

stop_gateway() {
    if [ -n "$GATEWAY_PID" ] && kill -0 "$GATEWAY_PID" 2>/dev/null; then
        kill "$GATEWAY_PID" 2>/dev/null || true
        wait "$GATEWAY_PID" 2>/dev/null || true
    fi
    GATEWAY_PID=''
}
cleanup() {
    local status=$?
    stop_gateway
    if ! "${rules[@]}" publish >/dev/null; then
        printf 'FAIL could not write the baseline rules back to the namespace\n' >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT

fail() { printf 'FAIL %s\nlogs: %s\n' "$1" "$work" >&2; exit 1; }
ok() { passed=$((passed + 1)); printf 'ok   %s\n' "$1"; }

jvm_flags=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED
    "-Dcsp.sentinel.log.dir=$work/sentinel-log-dir/" "-DEAGLEEYE.LOG.PATH=$work/eagleeye-log-dir/")
start_gateway() { # 日志文件名
    # 签发方地址是启动必填项；本验收不核对令牌，.env 没有给出时用不可达的回环占位地址（local profile 允许回环 HTTP）
    local issuer="${MARS_SECURITY_ISSUER_URI:-}" jwks="${MARS_SECURITY_JWK_SET_URI:-}"
    if [ -z "$issuer" ]; then issuer=http://127.0.0.1:1; jwks=http://127.0.0.1:1/keys; fi
    MARS_GATEWAY_TRUSTED_HOP_COUNT=1 MARS_GATEWAY_DIRECT_PEER_CIDRS=127.0.0.1/32 SERVER_PORT="$GATEWAY_PORT" \
        MARS_SECURITY_ISSUER_URI="$issuer" MARS_SECURITY_JWK_SET_URI="$jwks" MANAGEMENT_HEALTH_REDIS_ENABLED=false \
        java "${jvm_flags[@]}" -jar "$JAR" --mars.observability.logging.console-format=plain >"$work/$1.log" 2>&1 &
    GATEWAY_PID=$!
}
wait_healthy() { # 日志文件名
    local attempt
    for ((attempt = 0; attempt < 90; attempt++)); do
        if curl -fsS --max-time 2 "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health" >/dev/null 2>&1; then return 0; fi
        kill -0 "$GATEWAY_PID" 2>/dev/null || fail "gateway exited during startup (see $1.log)"
        sleep 1
    done
    fail "gateway did not become healthy (see $1.log)"
}

# 以可信代理的身份发请求：X-Forwarded-For 给出客户端地址。输出 HTTP 状态；429 时核对信封里的码。
request() { # 方法 路径 客户端地址
    local status
    status=$(curl -sS --max-time 8 -o "$work/body" -w '%{http_code}' -X "$1" \
        -H "X-Forwarded-For: $3" -H 'X-Forwarded-Proto: http' "http://127.0.0.1:$GATEWAY_PORT$2" || true)
    if [ "$status" = 429 ]; then
        python3 - "$work/body" <<'PY' || status=429-wrong-body
import json, sys
body = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if body.get("success") is False and str(body.get("code")) == "63006" else 1)
PY
    fi
    printf '%s' "$status"
}
# 同一客户端连续请求 n 次，输出每次的状态，空格分隔
burst() { # 方法 路径 客户端地址 次数
    local i out=''
    for ((i = 0; i < $4; i++)); do out+="$(request "$1" "$2" "$3") "; done
    printf '%s' "${out% }"
}
# 放行的请求打到没有实例的目标服务，网关以 503（63002）结束；只有它算放行，连接失败的 000 与 500 都不算
let_through() { [ "$1" = 503 ]; }
# 路由级的探测走 product 的匿名读取路径：安全链放行它，请求到达 product 路由
PROBE_PATH=/product/v1/catalog
expect_blocked_third() { # 标签 客户端地址 [方法 路径]
    local statuses
    statuses=$(burst "${3:-GET}" "${4:-$PROBE_PATH}" "$2" 3)
    case "$statuses" in
        "503 503 429") ok "$1: $statuses" ;;
        *429-wrong-body*) fail "$1: 429 without the 63006 envelope ($statuses)" ;;
        *) fail "$1: expected 503 503 429, got $statuses" ;;
    esac
}

loose_rules='[{"resource":"product","count":1000,"intervalSec":60,"paramItem":{"parseStrategy":0}},
 {"resource":"order-callbacks","resourceMode":1,"count":1000,"intervalSec":60},
 {"resource":"auth-entry","resourceMode":1,"count":1000,"intervalSec":60,"paramItem":{"parseStrategy":0}}]'
tight_rules='[{"resource":"product","count":2,"intervalSec":60,"paramItem":{"parseStrategy":0}},
 {"resource":"order-callbacks","resourceMode":1,"count":2,"intervalSec":60},
 {"resource":"auth-entry","resourceMode":1,"count":2,"intervalSec":60,"paramItem":{"parseStrategy":0}}]'

echo "== 缺少规则配置时启动失败"
"${rules[@]}" publish >/dev/null
"${rules[@]}" delete "$FLOW_ID" >/dev/null
start_gateway missing-rules
for ((i = 0; i < 90 && ${#GATEWAY_PID} > 0; i++)); do kill -0 "$GATEWAY_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$GATEWAY_PID" 2>/dev/null; then fail "gateway kept running without $FLOW_ID"; fi
wait "$GATEWAY_PID" 2>/dev/null && fail "gateway exited with status 0 without $FLOW_ID" || true
GATEWAY_PID=''
{ grep -q "dataId=$FLOW_ID" "$work/missing-rules.log" && grep -q '配置不存在' "$work/missing-rules.log"; } \
    || fail "startup failure does not name the missing data ID"
ok "startup fails without $FLOW_ID"

echo "== 宽松规则下启动"
printf '%s' "$loose_rules" | "${rules[@]}" put "$FLOW_ID" - >/dev/null
start_gateway run-1
wait_healthy run-1
request GET "$PROBE_PATH" 10.9.9.9 >/dev/null   # 预热：冷启动的第一次请求可能很慢
statuses=$(burst GET "$PROBE_PATH" 10.1.0.1 3)
[ "$statuses" = "503 503 503" ] || fail "loose rules did not let three requests through to the route ($statuses)"
ok "loose rules let three requests through: $statuses"

echo "== 收紧规则，不重启生效"
printf '%s' "$tight_rules" | "${rules[@]}" put "$FLOW_ID" - >/dev/null
published=$(date +%s)
effective=''
for ((attempt = 1; attempt <= 10; attempt++)); do
    statuses=$(burst GET "$PROBE_PATH" "10.2.0.$attempt" 3)
    case "$statuses" in
        "503 503 429") effective=$(( $(date +%s) - published )); break ;;
        *429-wrong-body*) fail "tightened rules: 429 without the 63006 envelope ($statuses)" ;;
    esac
    sleep 1
done
[ -n "$effective" ] || fail "tightened rules did not take effect within 10 seconds"
[ "$effective" -le 5 ] || fail "tightened rules took ${effective}s"
kill -0 "$GATEWAY_PID" 2>/dev/null || fail "gateway process exited"
ok "tightened rules took effect in ${effective}s without a restart"

echo "== 按客户端地址分别计数"
expect_blocked_third "client 10.3.0.1" 10.3.0.1
status=$(request GET "$PROBE_PATH" 10.3.0.2)
let_through "$status" || fail "another client address was not let through to the route ($status)"
ok "another client address still passes: $status"

echo "== 回调路径按 API 分组计总数"
first=$(request POST /order/v1/callbacks/pay 10.4.0.1)
second=$(request POST /order/v1/callbacks/pay 10.4.0.2)
third=$(request POST /order/v1/callbacks/pay 10.4.0.3)
{ let_through "$first" && let_through "$second"; } || fail "callbacks were not let through before the group limit ($first $second)"
[ "$third" = 429 ] || fail "third callback from a new address was not blocked ($third)"
ok "callback group limit applies across addresses: $first $second $third"
status=$(request GET "$PROBE_PATH" 10.4.0.4)
let_through "$status" || fail "a path outside the callback group was not let through ($status)"
ok "paths outside the callback group are not counted by it: $status"

echo "== 认证入口分组按客户端地址计数"
# 认证 Host 的签发方端点不要求令牌；本机 local profile 接受回环 Host，请求到达 auth-issuer 路由
first=$(request POST /oauth2/token 10.8.0.1)
second=$(request POST /login/sms/authenticate 10.8.0.1)
third=$(request POST /login/sms/send 10.8.0.1)
{ let_through "$first" && let_through "$second"; } || fail "auth entry requests were not let through before the limit ($first $second)"
[ "$third" = 429 ] || fail "the third auth entry request from one address was not blocked ($third)"
ok "the auth entry paths share one counter per client address: $first $second $third"
status=$(request GET /oauth2/authorize 10.8.0.2)
let_through "$status" || fail "another client address was not let through to the authorization endpoint ($status)"
ok "another client address still reaches the authorization endpoint: $status"
status=$(request GET /login 10.8.0.1)
let_through "$status" || fail "the login page was counted by the auth entry group ($status)"
ok "the login page is outside the auth entry group: $status"

echo "== 拦截次数经管理端口的指标可读"
status=$(curl -sS --max-time 8 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$MANAGEMENT_PORT/actuator/prometheus" || true)
[ "$status" = 401 ] || fail "anonymous read of the prometheus endpoint was not rejected ($status)"
curl -sS --max-time 8 -u "$MARS_MANAGEMENT_USERNAME:$MARS_MANAGEMENT_PASSWORD" -o "$work/prometheus.txt" \
    "http://127.0.0.1:$MANAGEMENT_PORT/actuator/prometheus" || fail "reading the prometheus endpoint with credentials failed"
for resource in product order-callbacks auth-entry; do
    grep -Eq "^mars_sentinel_requests_blocked_total\{[^}]*resource=\"$resource\"" "$work/prometheus.txt" \
        || fail "mars_sentinel_requests_blocked_total has no sample for resource $resource"
done
ok "mars_sentinel_requests_blocked_total is readable with credentials and counts every blocked resource"

echo "== 坏规则与删除配置都保留上一批"
printf '%s' '[{"resource":"orders","count":1}]' | "${rules[@]}" put "$FLOW_ID" - >/dev/null
for ((i = 0; i < 10; i++)); do grep -q 'Sentinel 规则被拒绝.*不是网关已声明的路由 ID：orders' "$work/run-1.log" && break; sleep 1; done
grep -q 'Sentinel 规则被拒绝.*不是网关已声明的路由 ID：orders' "$work/run-1.log" || fail "invalid rules were not rejected loudly"
expect_blocked_third "previous rules after an invalid update" 10.5.0.1
"${rules[@]}" delete "$FLOW_ID" >/dev/null
for ((i = 0; i < 10; i++)); do grep -q '配置已被删除或内容为空白' "$work/run-1.log" && break; sleep 1; done
grep -q '配置已被删除或内容为空白' "$work/run-1.log" || fail "deleting the data ID was not rejected loudly"
expect_blocked_third "previous rules after the data ID was deleted" 10.6.0.1
# 写回收紧规则，供重启用例读取；内容与仍在生效的一批相同，不产生「规则已更新」日志。
# 等 Nacos 客户端把这次推送交给监听器（notify-ok）再停机：推送若与停机同时发生，Nacos 客户端在停机中登记
# shutdown hook 会失败并写一行 ERROR，那是客户端库在停机窗口的行为，不是本次验收要核对的内容
delivered_before=$(grep -c "notify-ok\] dataId=$FLOW_ID," "$work/run-1.log" || true)
printf '%s' "$tight_rules" | "${rules[@]}" put "$FLOW_ID" - >/dev/null
for ((i = 0; i < 10; i++)); do
    [ "$(grep -c "notify-ok\] dataId=$FLOW_ID," "$work/run-1.log" || true)" -gt "$delivered_before" ] && break
    sleep 1
done
[ "$(grep -c "notify-ok\] dataId=$FLOW_ID," "$work/run-1.log" || true)" -gt "$delivered_before" ] \
    || fail "the republished rules were not delivered to the gateway within 10 seconds"
ok "invalid and deleted configurations kept the previous rules"

echo "== 只监听业务端口与管理端口"
# lsof 没有找到监听端口时返回非零；在 set -e 下显式处理，不让脚本静默退出
if ! lsof_out=$(lsof -nP -a -p "$GATEWAY_PID" -iTCP -sTCP:LISTEN 2>/dev/null); then
    fail "lsof found no listening TCP ports for the gateway process $GATEWAY_PID"
fi
listening=$(printf '%s\n' "$lsof_out" | awk 'NR > 1 {sub(/.*:/, "", $9); print $9}' | sort -un | tr '\n' ' ')
expected_ports=$(printf '%s\n%s\n' "$GATEWAY_PORT" "$MANAGEMENT_PORT" | sort -n | tr '\n' ' ')
[ "$listening" = "$expected_ports" ] || fail "unexpected listening ports: $listening (expected $expected_ports)"
ok "listening ports: $listening"

echo "== 重启后规则仍在"
stop_gateway
start_gateway run-2
wait_healthy run-2
request GET "$PROBE_PATH" 10.9.9.8 >/dev/null
expect_blocked_third "rules after a restart" 10.7.0.1
stop_gateway

echo "== Sentinel 不写文件，日志没有意外告警"
files=$(find "$work/sentinel-log-dir" "$work/eagleeye-log-dir" -type f | wc -l | tr -d ' ')
[ "$files" = 0 ] || fail "Sentinel wrote $files files"
ok "no Sentinel files after blocking requests"
# 放行的请求打到没有实例的 product、order（回调）或 auth 服务，以 503 结束：负载均衡与网关各为它写一行 WARN，这是验收有意安排的
unexpected=$(cat "$work/run-1.log" "$work/run-2.log" | grep -E ' (WARN|ERROR) ' \
    | grep -v 'Sentinel 规则被拒绝' \
    | grep -v '网关错误 503 code=63002' \
    | grep -v 'No servers available for service: mars-cloud-product-service' \
    | grep -v 'No servers available for service: mars-cloud-order-service' \
    | grep -v 'No servers available for service: mars-cloud-auth-service' \
    | grep -v 'ObservabilityConventionVerifier' || true)
[ -z "$unexpected" ] || { printf '%s\n' "$unexpected" | head -20 >&2; fail "unexpected WARN or ERROR lines"; }
ok "gateway logs contain only the expected rule rejections"

printf '\n通过 %s，失败 0\n全部通过。\n' "$passed"
