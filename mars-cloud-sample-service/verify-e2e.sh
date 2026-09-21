#!/usr/bin/env bash
#
# 示例服务的端到端验收：以**真进程**（java -jar）启动，逐条核对文档承诺的行为。
#
# 与单元测试的分工：MockMvc 测试验证契约，本脚本验证「打包出来的 jar 真能这样跑」——
# 包括 actuator、信封与 i18n 这些只有真进程才暴露的事实。
#
set -uo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../scripts/security-test-runtime.sh
. "$MODULE_DIR/../scripts/security-test-runtime.sh"
JAR="$MODULE_DIR/target/mars-cloud-sample-service.jar"
LOG="$(mktemp -t sample-e2e)"
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

if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR —— 请先在模块目录执行 mvn package"
  exit 2
fi

if [ -f "$MODULE_DIR/.env" ]; then
  echo "加载 $MODULE_DIR/.env"
  set -a
  # shellcheck disable=SC1091
  . "$MODULE_DIR/.env"
  set +a
fi
PORT="${SAMPLE_PORT:-8103}"
BASE="http://127.0.0.1:${PORT}/sample"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
if [ -z "${NACOS_NAMESPACE_ID:-}" ] || [ -z "${NACOS_USERNAME:-}" ] || [ -z "${NACOS_PASSWORD:-}" ]; then
  echo "错误：NACOS_NAMESPACE_ID、NACOS_USERNAME 与 NACOS_PASSWORD 都不能为空。" >&2
  exit 2
fi

# 端口必须空闲：否则本脚本起不来自己的实例，健康检查却会打到**已在跑的旧实例**上，
# 于是所有断言都「通过」而其实验的是别的进程——这种假绿比失败更危险。
if security_curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then
  echo "端口 ${PORT} 上已经有服务在响应。"
  echo "本脚本需要自己启动实例，请先停掉它（例如 Ctrl-C），或用 SAMPLE_PORT 换一个端口。"
  exit 2
fi

# 拒绝任何已占用端口，避免误验已有进程。
python3 - "$PORT" <<'PY'
import socket, sys
for port in sys.argv[1:]:
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", int(port)))
PY
[ "$?" -eq 0 ] || exit 2

cleanup() {
  if [ -n "${PID:-}" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null
    wait "$PID" 2>/dev/null
  fi
  stop_security_test_issuer
}
trap cleanup EXIT
start_security_test_issuer "$MODULE_DIR/.." || exit 1

echo "启动 $JAR（端口 $PORT，日志 $LOG）"
SERVER_PORT="$PORT" java "${JVM_FLAGS[@]}" -jar "$JAR" >"$LOG" 2>&1 &
PID=$!

echo "等待健康检查通过..."
ready=0
for _ in $(seq 1 60); do
  if security_curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  echo "服务未在 60 秒内就绪，日志末尾："
  tail -30 "$LOG"
  exit 1
fi

echo
echo "① 健康检查"
check "actuator/health 为 UP" "UP" \
  "$(security_curl -fsS "$BASE/actuator/health" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')"

echo
echo "② 成功路径：业务对象被包成信封"
body=$(security_curl -fsS "$BASE/v1/orders/1")
contains "success 为 true" '"success":true' "$body"
contains "result 带业务字段" '"sku":"demo-sku"' "$body"
case "$body" in
  *'"code"'*) printf '  FAIL 成功响应不应出现 code\n'; fail=$((fail + 1)) ;;
  *) printf '  ok   成功响应不含 code\n'; pass=$((pass + 1)) ;;
esac

echo
echo "③ 业务拒绝：HTTP 200 + success:false（不是 200 之外的任何码）"
status=$(security_curl -s -o "${SECURITY_TEST_DIR}/sample-business.json" -w '%{http_code}' -X POST "$BASE/v1/orders" \
  -H 'Content-Type: application/json' -d '{"sku":"out-of-stock","quantity":1}')
check "HTTP 状态码" "200" "$status"
contains "success 为 false" '"success":false' "$(cat "${SECURITY_TEST_DIR}/sample-business.json")"
contains "错误码为 66102" '"code":"66102"' "$(cat "${SECURITY_TEST_DIR}/sample-business.json")"

echo
echo "④ 资源不存在：HTTP 404"
status=$(security_curl -s -o /dev/null -w '%{http_code}' "$BASE/v1/orders/missing")
check "HTTP 状态码" "404" "$status"

echo
echo "⑤ 参数校验失败：HTTP 400"
status=$(security_curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/orders" \
  -H 'Content-Type: application/json' -d '{"sku":"","quantity":0}')
check "HTTP 状态码" "400" "$status"

echo
echo "⑥ i18n：文案随 Accept-Language 变化"
# 该端点刻意返回 404，所以这里**不能**用 security_curl -f —— -f 会把响应体丢掉
zh=$(security_curl -sS -H 'Accept-Language: zh-CN' "$BASE/v1/orders/missing" | sed -n 's/.*"message":"\([^"]*\)".*/\1/p')
en=$(security_curl -sS -H 'Accept-Language: en-US' "$BASE/v1/orders/missing" | sed -n 's/.*"message":"\([^"]*\)".*/\1/p')
check "zh-CN 文案" "资源不存在" "$zh"
check "en-US 文案" "Resource not found" "$en"

echo
echo "⑦ 接口文档可达"
check "v3/api-docs 状态码" "200" \
  "$(security_curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")"

echo
echo "──────────────────────────────"
printf '通过 %d，失败 %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || { echo "日志末尾："; tail -20 "$LOG"; exit 1; }
verify_no_test_credentials "$LOG" || exit 1
echo "全部通过。"
