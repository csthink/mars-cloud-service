#!/usr/bin/env bash
#
# 网关的端到端验收：以**真进程**（java -jar）启动，逐条核对文档承诺的行为。
#
# 与契约测试的分工：WebTestClient 测试用假上游验证信封与透传；本脚本验证
# 「打包出来的 jar 接上真实注册中心与真实 UPMS 后真能这样跑」，其中最重要的一条是启动顺序：
# **先起网关、后起 UPMS**，网关必须在不重启的情况下发现后起的实例。
#
# 前置：本机 Nacos 已启动，Namespace 下已有 COMMON/shared-common.yaml、
# DEFAULT_GROUP/mars-cloud-gateway.yaml 与 DEFAULT_GROUP/mars-cloud-upms-service.yaml；
# 本目录有 `.env`（或环境里已有 NACOS_* 变量）；两个模块都已 mvn package；
# 机器上有 security_curl 与 python3（第 ⑦ 项用它比较 JSON 顶层键集合）。
#
set -uo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../scripts/security-test-runtime.sh
. "$MODULE_DIR/../scripts/security-test-runtime.sh"
GATEWAY_JAR="$MODULE_DIR/target/mars-cloud-gateway.jar"
UPMS_JAR="$MODULE_DIR/../mars-cloud-upms-service/target/mars-cloud-upms-service.jar"
GATEWAY_LOG="$(mktemp -t gateway-e2e)"
UPMS_LOG="$(mktemp -t upms-e2e)"
DISCOVERY_TIMEOUT="${DISCOVERY_TIMEOUT:-90}"
JVM_FLAGS=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED)

pass=0
fail=0

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  ok   %s\n' "$label"
    pass=$((pass + 1))
  else
    printf '  FAIL %s\n       期望: %s\n       实际: %s\n' "$label" "$expected" "$actual"
    fail=$((fail + 1))
  fi
}

contains() {
  local label="$1" needle="$2" haystack="$3"
  case "$haystack" in
    *"$needle"*) printf '  ok   %s\n' "$label"; pass=$((pass + 1)) ;;
    *) printf '  FAIL %s\n       期望包含: %s\n       实际: %s\n' "$label" "$needle" "$haystack"; fail=$((fail + 1)) ;;
  esac
}

status_of() { security_curl -s -o /dev/null -w '%{http_code}' "$@"; }

wait_until_ok() {
  # 轮询直到 URL 返回 2xx；输出耗时秒数，超时返回非零。
  local url="$1" limit="$2" waited=0
  while [ "$waited" -lt "$limit" ]; do
    if security_curl -fsS "$url" >/dev/null 2>&1; then
      echo "$waited"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "$waited"
  return 1
}

for jar in "$GATEWAY_JAR" "$UPMS_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "找不到 $jar —— 请先在仓根执行 mvn package"
    exit 2
  fi
done

ENV_FILE="${E2E_ENV_FILE:-$MODULE_DIR/.env}"
if [ -n "${E2E_ENV_FILE:-}" ] && [ ! -f "$ENV_FILE" ]; then
  echo "指定的验收环境文件不存在。" >&2
  exit 2
fi
if [ -f "$ENV_FILE" ]; then
  echo "加载 $ENV_FILE"
  set -a
  # shellcheck disable=SC1091
  . "$ENV_FILE"
  set +a
fi
GATEWAY_PORT="${GATEWAY_PORT:-8100}"
UPMS_PORT="${UPMS_PORT:-8102}"
# 管理端点在管理端口上，取值是业务端口加 1000，由可观测性组件推导。
GATEWAY_MANAGEMENT_PORT="${GATEWAY_MANAGEMENT_PORT:-$((GATEWAY_PORT + 1000))}"
UPMS_MANAGEMENT_PORT="${UPMS_MANAGEMENT_PORT:-$((UPMS_PORT + 1000))}"
GATEWAY="http://127.0.0.1:${GATEWAY_PORT}"
UPMS="http://127.0.0.1:${UPMS_PORT}"
GATEWAY_MANAGEMENT="http://127.0.0.1:${GATEWAY_MANAGEMENT_PORT}"
UPMS_MANAGEMENT="http://127.0.0.1:${UPMS_MANAGEMENT_PORT}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
if [ -z "${NACOS_NAMESPACE_ID:-}" ] || [ -z "${NACOS_USERNAME:-}" ] || [ -z "${NACOS_PASSWORD:-}" ]; then
  echo "错误：NACOS_NAMESPACE_ID、NACOS_USERNAME 与 NACOS_PASSWORD 都不能为空。" >&2
  exit 2
fi

# 两个端口都必须空闲：否则健康检查会打到已在跑的旧实例上，所有断言都「通过」而其实验的是别的进程。
for probe in "$GATEWAY_MANAGEMENT/actuator/health" "$UPMS_MANAGEMENT/actuator/health"; do
  if security_curl -fsS "$probe" >/dev/null 2>&1; then
    echo "$probe 已经有服务在响应。本脚本需要自己启动实例，请先停掉它，或用 GATEWAY_PORT / UPMS_PORT 换端口。"
    exit 2
  fi
done

cleanup() {
  for pid in "${UPMS_PID:-}" "${GATEWAY_PID:-}"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
    fi
  done
  stop_security_test_issuer
}
trap cleanup EXIT
start_security_test_issuer "$MODULE_DIR/.." || exit 1
SECURITY_CURL_HEADER="$SECURITY_TEST_DIR/admin.headers"

echo "① 先启动网关（端口 $GATEWAY_PORT，日志 $GATEWAY_LOG）"
SERVER_PORT="$GATEWAY_PORT" java "${JVM_FLAGS[@]}" -jar "$GATEWAY_JAR" >"$GATEWAY_LOG" 2>&1 &
GATEWAY_PID=$!
if ! elapsed=$(wait_until_ok "$GATEWAY_MANAGEMENT/actuator/health" 60); then
  echo "网关未在 60 秒内就绪，日志末尾："; tail -30 "$GATEWAY_LOG"; exit 1
fi
check "网关管理端口的 actuator/health 为 UP" "UP" \
  "$(security_curl -fsS "$GATEWAY_MANAGEMENT/actuator/health" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')"

echo
echo "② UPMS 尚未启动：经网关访问必须是信封式 503（63002），不是裸错误页"
# 用真实业务端点探测：管理端点已挪到管理端口，业务端口上不再有 actuator，
# 经网关访问那条路径得到的是「没有路由」而不是「没有实例」。
body=$(security_curl -s -o /dev/stdout -w '\n%{http_code}' -X POST "$GATEWAY/upms/v1/decision" \
  -H 'Content-Type: application/json' -d '{"caller_id":"local-admin","action":"view","resource":"demo:view:domain:kubernetes-ops"}')
check "HTTP 状态码" "503" "$(printf '%s' "$body" | tail -1)"
contains "success 为 false" '"success":false' "$body"
contains "错误码为 63002" '"code":"63002"' "$body"

echo
echo "③ 没有路由的路径：信封式 404（63001）"
body=$(security_curl -s -o /dev/stdout -w '\n%{http_code}' "$GATEWAY/no-such-path")
check "HTTP 状态码" "404" "$(printf '%s' "$body" | tail -1)"
contains "错误码为 63001" '"code":"63001"' "$body"

echo
echo "④ 再启动 UPMS（端口 $UPMS_PORT，日志 $UPMS_LOG）"
SERVER_PORT="$UPMS_PORT" java "${JVM_FLAGS[@]}" -jar "$UPMS_JAR" >"$UPMS_LOG" 2>&1 &
UPMS_PID=$!
if ! elapsed=$(wait_until_ok "$UPMS_MANAGEMENT/actuator/health" 90); then
  echo "UPMS 未在 90 秒内就绪，日志末尾："; tail -30 "$UPMS_LOG"; exit 1
fi
printf '  ok   UPMS 直连就绪（%ss）\n' "$elapsed"; pass=$((pass + 1))

echo
echo "⑤ 【回归项】网关先起、UPMS 后起：网关不重启也必须发现新实例"
discovery_probe() {
  security_curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/upms/v1/decision" \
    -H 'Content-Type: application/json' \
    -d '{"caller_id":"local-admin","action":"view","resource":"demo:view:domain:kubernetes-ops"}'
}
elapsed=0
while [ "$elapsed" -lt "$DISCOVERY_TIMEOUT" ]; do
  [ "$(discovery_probe)" = "200" ] && break
  sleep 1
  elapsed=$((elapsed + 1))
done
if [ "$(discovery_probe)" = "200" ]; then
  printf '  ok   经网关访问 UPMS 在 %ss 内变为可达\n' "$elapsed"; pass=$((pass + 1))
else
  printf '  FAIL 等待 %ss 后经网关仍不可达（服务发现未更新）\n' "$DISCOVERY_TIMEOUT"; fail=$((fail + 1))
fi
check "经网关的 UPMS 决策调用返回 200" "200" "$(discovery_probe)"

echo
echo "⑥ 业务调用经网关成功：种子快照里 local-admin 持有 demo 平台全部能力"
decision='{"caller_id":"local-admin","action":"view","resource":"demo:view:domain:kubernetes-ops"}'
body=$(security_curl -s -o /dev/stdout -w '\n%{http_code}' -X POST "$GATEWAY/upms/v1/decision" \
  -H 'Content-Type: application/json' -d "$decision")
check "HTTP 状态码" "200" "$(printf '%s' "$body" | tail -1)"
contains "success 为 true" '"success":true' "$body"
contains "决策为 allow" '"decision":"allow"' "$body"

echo
echo "⑦ 错误响应格式一致：业务服务的失败信封经网关原样透传；网关自产错误与之同一种形状"
via_gateway=$(security_curl -s -o /dev/stdout -w '\n%{http_code}' -X POST "$GATEWAY/upms/v1/decision" \
  -H 'Content-Type: application/json' -d '{"caller_id":')
direct=$(security_curl -s -o /dev/stdout -w '\n%{http_code}' -X POST "$UPMS/upms/v1/decision" \
  -H 'Content-Type: application/json' -d '{"caller_id":')
check "非法请求体：HTTP 状态码经网关与直连一致" "$(printf '%s' "$direct" | tail -1)" "$(printf '%s' "$via_gateway" | tail -1)"
check "非法请求体：响应体经网关与直连逐字节一致" "$(printf '%s' "$direct" | sed '$d')" "$(printf '%s' "$via_gateway" | sed '$d')"
keys_of() { printf '%s' "$1" | sed '$d' | python3 -c 'import sys,json; print(",".join(sorted(k for k in json.load(sys.stdin) if k != "result")))'; }
check "网关自产 63001 信封与业务失败信封的键集合相同（忽略 result）" \
  "$(keys_of "$direct")" \
  "$(keys_of "$(security_curl -s -o /dev/stdout -w '\n%{http_code}' "$GATEWAY/no-such-path")")"

echo
echo "⑧ 两个真进程的日志里没有 JDK 警告"
check "网关日志 sun.misc.Unsafe 行数" "0" "$(grep -c 'sun.misc.Unsafe' "$GATEWAY_LOG")"
check "网关日志 restricted method 行数" "0" "$(grep -c 'restricted method' "$GATEWAY_LOG")"
check "UPMS 日志 sun.misc.Unsafe 行数" "0" "$(grep -c 'sun.misc.Unsafe' "$UPMS_LOG")"

echo
echo "──────────────────────────────"
printf '通过 %d，失败 %d\n' "$pass" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "网关日志末尾："; tail -20 "$GATEWAY_LOG"
  exit 1
fi
verify_no_test_credentials "$GATEWAY_LOG" "$UPMS_LOG" || exit 1
echo "全部通过。"
