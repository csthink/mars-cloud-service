#!/usr/bin/env bash
# 网关限流的真进程验收：以 java -jar 启动网关，规则经 Nacos 下发。
#
# 核对：缺少规则配置时启动失败；规则修改后不重启即生效；按客户端地址分别计数（地址取自可信代理给出的
# X-Forwarded-For，TCP 对端相同）；回调路径按 API 分组计总数；坏规则与删除配置都保留上一批规则；
# 重启后规则仍在；被拒绝的响应是 429 与码 63006；网关只监听业务端口与管理端口；Sentinel 不写任何文件。
#
# 前置：本机 Nacos 已启动，命名空间里已有网关的应用配置；本目录有 .env（或用 SENTINEL_E2E_ENV_FILE 指定）；
# 已 mvn package 出 target/mars-cloud-gateway.jar。
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
    MARS_GATEWAY_TRUSTED_HOP_COUNT=1 MARS_GATEWAY_DIRECT_PEER_CIDRS=127.0.0.1/32 SERVER_PORT="$GATEWAY_PORT" \
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
# 放行的请求打到没有实例的 order 服务，网关以 503（63002）结束；只有它算放行，连接失败的 000 与 500 都不算
let_through() { [ "$1" = 503 ]; }
expect_blocked_third() { # 标签 客户端地址
    local statuses
    statuses=$(burst GET /order/v1/orders "$2" 3)
    case "$statuses" in
        "503 503 429") ok "$1: $statuses" ;;
        *429-wrong-body*) fail "$1: 429 without the 63006 envelope ($statuses)" ;;
        *) fail "$1: expected 503 503 429, got $statuses" ;;
    esac
}

loose_rules='[{"resource":"order","count":1000,"intervalSec":60,"paramItem":{"parseStrategy":0}},
 {"resource":"order-callbacks","resourceMode":1,"count":1000,"intervalSec":60}]'
tight_rules='[{"resource":"order","count":2,"intervalSec":60,"paramItem":{"parseStrategy":0}},
 {"resource":"order-callbacks","resourceMode":1,"count":2,"intervalSec":60}]'

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
request GET /order/v1/orders 10.9.9.9 >/dev/null   # 预热：冷启动的第一次请求可能很慢
statuses=$(burst GET /order/v1/orders 10.1.0.1 3)
[ "$statuses" = "503 503 503" ] || fail "loose rules did not let three requests through to the route ($statuses)"
ok "loose rules let three requests through: $statuses"

echo "== 收紧规则，不重启生效"
printf '%s' "$tight_rules" | "${rules[@]}" put "$FLOW_ID" - >/dev/null
published=$(date +%s)
effective=''
for ((attempt = 1; attempt <= 10; attempt++)); do
    statuses=$(burst GET /order/v1/orders "10.2.0.$attempt" 3)
    if [ "${statuses##* }" = 429 ]; then effective=$(( $(date +%s) - published )); break; fi
    sleep 1
done
[ -n "$effective" ] || fail "tightened rules did not take effect within 10 seconds"
[ "$effective" -le 5 ] || fail "tightened rules took ${effective}s"
kill -0 "$GATEWAY_PID" 2>/dev/null || fail "gateway process exited"
ok "tightened rules took effect in ${effective}s without a restart"

echo "== 按客户端地址分别计数"
expect_blocked_third "client 10.3.0.1" 10.3.0.1
status=$(request GET /order/v1/orders 10.3.0.2)
let_through "$status" || fail "another client address was not let through to the route ($status)"
ok "another client address still passes: $status"

echo "== 回调路径按 API 分组计总数"
first=$(request POST /order/v1/callbacks/pay 10.4.0.1)
second=$(request POST /order/v1/callbacks/pay 10.4.0.2)
third=$(request POST /order/v1/callbacks/pay 10.4.0.3)
{ let_through "$first" && let_through "$second"; } || fail "callbacks were not let through before the group limit ($first $second)"
[ "$third" = 429 ] || fail "third callback from a new address was not blocked ($third)"
ok "callback group limit applies across addresses: $first $second $third"
status=$(request GET /order/v1/other 10.4.0.4)
let_through "$status" || fail "a non-callback path was not let through ($status)"
ok "other order paths are outside the callback group: $status"

echo "== 坏规则与删除配置都保留上一批"
printf '%s' '[{"resource":"orders","count":1}]' | "${rules[@]}" put "$FLOW_ID" - >/dev/null
for ((i = 0; i < 10; i++)); do grep -q 'Sentinel 规则被拒绝.*不是网关已声明的路由 ID：orders' "$work/run-1.log" && break; sleep 1; done
grep -q 'Sentinel 规则被拒绝.*不是网关已声明的路由 ID：orders' "$work/run-1.log" || fail "invalid rules were not rejected loudly"
expect_blocked_third "previous rules after an invalid update" 10.5.0.1
"${rules[@]}" delete "$FLOW_ID" >/dev/null
for ((i = 0; i < 10; i++)); do grep -q '配置已被删除或内容为空白' "$work/run-1.log" && break; sleep 1; done
grep -q '配置已被删除或内容为空白' "$work/run-1.log" || fail "deleting the data ID was not rejected loudly"
expect_blocked_third "previous rules after the data ID was deleted" 10.6.0.1
# 写回收紧规则，供重启用例读取；内容与仍在生效的一批相同，不产生「规则已更新」日志
printf '%s' "$tight_rules" | "${rules[@]}" put "$FLOW_ID" - >/dev/null
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
request GET /order/v1/orders 10.9.9.8 >/dev/null
expect_blocked_third "rules after a restart" 10.7.0.1
stop_gateway

echo "== Sentinel 不写文件，日志没有意外告警"
files=$(find "$work/sentinel-log-dir" "$work/eagleeye-log-dir" -type f | wc -l | tr -d ' ')
[ "$files" = 0 ] || fail "Sentinel wrote $files files"
ok "no Sentinel files after blocking requests"
# 放行的请求打到没有实例的 order 服务，以 503 结束：负载均衡与网关各为它写一行 WARN，这是验收有意安排的
unexpected=$(cat "$work/run-1.log" "$work/run-2.log" | grep -E ' (WARN|ERROR) ' \
    | grep -v 'Sentinel 规则被拒绝' \
    | grep -v '网关错误 503 code=63002' \
    | grep -v 'No servers available for service: mars-cloud-order-service' \
    | grep -v 'ObservabilityConventionVerifier' || true)
[ -z "$unexpected" ] || { printf '%s\n' "$unexpected" | head -20 >&2; fail "unexpected WARN or ERROR lines"; }
ok "gateway logs contain only the expected rule rejections"

printf '\n通过 %s，失败 0\n全部通过。\n' "$passed"
