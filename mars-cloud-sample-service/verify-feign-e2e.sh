#!/usr/bin/env bash
#
# sample 经 Nacos 服务发现调用 UPMS 的真进程验收。
#
# 前置：本机 Nacos 已启动，隔离 Namespace 下已有 shared-common、sample 与 UPMS 配置；
# sample 目录有对应 .env；两个模块都已用同一个隔离 Maven 仓完成 package。
#
set -uo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_DIR="$(cd "$MODULE_DIR/.." && pwd)"
SAMPLE_JAR="$MODULE_DIR/target/mars-cloud-sample-service.jar"
UPMS_JAR="$SERVICE_DIR/mars-cloud-upms-service/target/mars-cloud-upms-service.jar"
SAMPLE_PORT="${SAMPLE_PORT:-8203}"
UPMS_PORT="${UPMS_PORT:-8202}"
SAMPLE="http://127.0.0.1:${SAMPLE_PORT}/sample"
UPMS="http://127.0.0.1:${UPMS_PORT}/upms"
SAMPLE_LOG="$(mktemp -t sample-feign-e2e)"
UPMS_LOG="$(mktemp -t upms-feign-e2e)"
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

wait_until_ok() {
  local url="$1" limit="$2" waited=0
  while [ "$waited" -lt "$limit" ]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$waited"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "$waited"
  return 1
}

for jar in "$SAMPLE_JAR" "$UPMS_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "找不到 $jar，请先在仓根完成 package。"
    exit 2
  fi
done

if [ -f "$MODULE_DIR/.env" ]; then
  echo "加载 $MODULE_DIR/.env"
  set -a
  # shellcheck disable=SC1091
  . "$MODULE_DIR/.env"
  set +a
fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
if [ -z "${NACOS_NAMESPACE_ID:-}" ] || [ -z "${NACOS_USERNAME:-}" ] || [ -z "${NACOS_PASSWORD:-}" ]; then
  echo "错误：NACOS_NAMESPACE_ID、NACOS_USERNAME 与 NACOS_PASSWORD 都不能为空。" >&2
  exit 2
fi

for probe in "$SAMPLE/actuator/health" "$UPMS/actuator/health"; do
  if curl -fsS "$probe" >/dev/null 2>&1; then
    echo "$probe 已经有服务在响应。本脚本必须自己启动实例，请先停掉它或更换端口。"
    exit 2
  fi
done

cleanup() {
  for pid in "${SAMPLE_PID:-}" "${UPMS_PID:-}"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
    fi
  done
}
trap cleanup EXIT

echo "① 启动 UPMS（端口 $UPMS_PORT，日志 $UPMS_LOG）"
SERVER_PORT="$UPMS_PORT" java "${JVM_FLAGS[@]}" -jar "$UPMS_JAR" >"$UPMS_LOG" 2>&1 &
UPMS_PID=$!
if ! elapsed=$(wait_until_ok "$UPMS/actuator/health" 90); then
  echo "UPMS 未在 90 秒内就绪，日志末尾："; tail -30 "$UPMS_LOG"; exit 1
fi
printf '  ok   UPMS 就绪（%ss）\n' "$elapsed"; pass=$((pass + 1))

echo
echo "② 启动 sample（端口 $SAMPLE_PORT，日志 $SAMPLE_LOG）"
SERVER_PORT="$SAMPLE_PORT" java "${JVM_FLAGS[@]}" -jar "$SAMPLE_JAR" >"$SAMPLE_LOG" 2>&1 &
SAMPLE_PID=$!
if ! elapsed=$(wait_until_ok "$SAMPLE/actuator/health" 90); then
  echo "sample 未在 90 秒内就绪，日志末尾："; tail -30 "$SAMPLE_LOG"; exit 1
fi
printf '  ok   sample 就绪（%ss）\n' "$elapsed"; pass=$((pass + 1))

echo
echo "③ sample 经 Nacos 服务名调用 UPMS"
request='{"action":"view","resource":"demo:view:domain:kubernetes-ops"}'
elapsed=0
while [ "$elapsed" -lt "$DISCOVERY_TIMEOUT" ]; do
  body=$(curl -s -o /dev/stdout -w '\n%{http_code}' -X POST "$SAMPLE/v1/upms/decision" \
    -H 'Content-Type: application/json' -d "$request")
  if [ "$(printf '%s' "$body" | tail -1)" = "200" ]; then
    break
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
check "HTTP 状态码" "200" "$(printf '%s' "$body" | tail -1)"
contains "success 为 true" '"success":true' "$body"
contains "返回 UPMS decision" '"decision":' "$body"
contains "返回 UPMS decision_id" '"decision_id":' "$body"

echo
echo "④ 停止 UPMS 后，sample 映射为 HTTP 503 + 66104"
kill "$UPMS_PID" 2>/dev/null
wait "$UPMS_PID" 2>/dev/null
UPMS_PID=""
elapsed=0
while [ "$elapsed" -lt "$DISCOVERY_TIMEOUT" ]; do
  body=$(curl -s -o /dev/stdout -w '\n%{http_code}' -X POST "$SAMPLE/v1/upms/decision" \
    -H 'Content-Type: application/json' -d "$request")
  if [ "$(printf '%s' "$body" | tail -1)" = "503" ] && printf '%s' "$body" | grep -q '"code":"66104"'; then
    break
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
check "HTTP 状态码" "503" "$(printf '%s' "$body" | tail -1)"
contains "sample 错误码为 66104" '"code":"66104"' "$body"

echo
echo "──────────────────────────────"
printf '通过 %d，失败 %d\n' "$pass" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "sample 日志末尾："; tail -30 "$SAMPLE_LOG"
  exit 1
fi
echo "全部通过。"
