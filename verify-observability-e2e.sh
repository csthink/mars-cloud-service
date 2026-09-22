#!/usr/bin/env bash
#
# 可观测性的真进程验收：一次经网关到示例服务再到授权决策服务的请求，
# 在追踪后端是一条链、三个进程各有 span，三个进程的日志带同一个链路标识，
# 日志按该标识能查回三个服务，监控面板能看到全部实例并在实例下线时通知。
#
# 前置：本机中间件已启动（追踪后端、日志后端、注册中心）；四个模块已 clean package；
# 环境文件给出注册中心连接、端口与管理端点凭据。
#
set -uo pipefail

SERVICE_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SERVICE_DIR/scripts/security-test-runtime.sh"

ENV_FILE="${E2E_ENV_FILE:-$SERVICE_DIR/mars-cloud-sample-service/.env}"
[ -f "$ENV_FILE" ] || { echo "找不到环境文件 $ENV_FILE" >&2; exit 2; }
set -a
# shellcheck disable=SC1091
. "$ENV_FILE"
set +a

GATEWAY_PORT="${GATEWAY_PORT:-8100}"
UPMS_PORT="${UPMS_PORT:-8102}"
SAMPLE_PORT="${SAMPLE_PORT:-8103}"
MONITOR_PORT="${MONITOR_PORT:-8190}"
MONITOR_MANAGEMENT_PORT="${MONITOR_MANAGEMENT_PORT:-$((MONITOR_PORT + 1000))}"
GATEWAY_MANAGEMENT_PORT="${GATEWAY_MANAGEMENT_PORT:-$((GATEWAY_PORT + 1000))}"
UPMS_MANAGEMENT_PORT="${UPMS_MANAGEMENT_PORT:-$((UPMS_PORT + 1000))}"
SAMPLE_MANAGEMENT_PORT="${SAMPLE_MANAGEMENT_PORT:-$((SAMPLE_PORT + 1000))}"

JAEGER_QUERY="${JAEGER_QUERY:?追踪后端查询地址必须给出，例如 http://127.0.0.1:36686}"
LOKI="${LOKI:?日志后端地址必须给出，例如 http://127.0.0.1:23100}"
: "${OTLP_TRACING_ENDPOINT:?追踪导出端点必须给出}"
: "${MARS_MANAGEMENT_USERNAME:?管理端点账号必须给出}"
: "${MARS_MANAGEMENT_PASSWORD:?管理端点口令必须给出}"
: "${MONITOR_USERNAME:?监控面板账号必须给出}"
: "${MONITOR_PASSWORD:?监控面板口令必须给出}"
: "${NACOS_NAMESPACE_ID:?注册中心命名空间必须给出}"

LOG_DIR="${OBSERVABILITY_E2E_LOG_DIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"
RUN_ID="$(date +%s)-$$"
JVM_FLAGS=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED)

pass=0
fail=0
check() {
  if [ "$2" = "$3" ]; then printf '  ok   %s\n' "$1"; pass=$((pass + 1))
  else printf '  FAIL %s\n       期望: %s\n       实际: %s\n' "$1" "$2" "$3"; fail=$((fail + 1)); fi
}
ok() { printf '  ok   %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL %s\n       %s\n' "$1" "${2:-}"; fail=$((fail + 1)); }

cleanup() {
  for pid in "${MONITOR_PID:-}" "${GATEWAY_PID:-}" "${SAMPLE_PID:-}" "${UPMS_PID:-}" "${WEBHOOK_PID:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null
  done
  stop_security_test_issuer
}
trap cleanup EXIT

python3 - "$GATEWAY_PORT" "$UPMS_PORT" "$SAMPLE_PORT" "$MONITOR_PORT" \
  "$GATEWAY_MANAGEMENT_PORT" "$UPMS_MANAGEMENT_PORT" "$SAMPLE_MANAGEMENT_PORT" "$MONITOR_MANAGEMENT_PORT" <<'PY'
import socket, sys
for value in sys.argv[1:]:
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", int(value)))
PY
[ "$?" -eq 0 ] || { echo "验收端口已被占用" >&2; exit 2; }

echo "① 启动群机器人接收器与测试签发器"
WEBHOOK_DIR="$(mktemp -d)"
python3 - "$WEBHOOK_DIR" <<'PY' > "$LOG_DIR/webhook.log" 2>&1 &
import http.server, pathlib, sys, threading
directory = pathlib.Path(sys.argv[1])
class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)
        (directory / 'received').write_bytes(body)
        self.send_response(200)
        self.end_headers()
    def log_message(self, *args):
        pass
server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
(directory / 'port').write_text(str(server.server_address[1]))
server.serve_forever()
PY
WEBHOOK_PID=$!
for _ in $(seq 1 50); do [ -s "$WEBHOOK_DIR/port" ] && break; sleep 0.1; done
[ -s "$WEBHOOK_DIR/port" ] || { echo "群机器人接收器未就绪" >&2; exit 1; }
WEBHOOK_URL="http://127.0.0.1:$(cat "$WEBHOOK_DIR/port")/hook"
start_security_test_issuer "$SERVICE_DIR" || exit 1
ok "群机器人接收器与测试签发器就绪"

wait_health() {
  local port="$1" limit="${2:-90}" waited=0
  while [ "$waited" -lt "$limit" ]; do
    command curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && { echo "$waited"; return 0; }
    sleep 1
    waited=$((waited + 1))
  done
  echo "$waited"
  return 1
}

echo
echo "② 启动四个部署物"
SERVER_PORT="$UPMS_PORT" java "${JVM_FLAGS[@]}" -jar mars-cloud-upms-service/target/mars-cloud-upms-service.jar > "$LOG_DIR/upms.log" 2>&1 &
UPMS_PID=$!
SERVER_PORT="$SAMPLE_PORT" java "${JVM_FLAGS[@]}" -jar mars-cloud-sample-service/target/mars-cloud-sample-service.jar > "$LOG_DIR/sample.log" 2>&1 &
SAMPLE_PID=$!
# 网关的日志级别只能在启动时给：它没有可写的日志级别端点（见下一节）。
SERVER_PORT="$GATEWAY_PORT" LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_CLOUD_GATEWAY=DEBUG \
  java "${JVM_FLAGS[@]}" -jar mars-cloud-gateway/target/mars-cloud-gateway.jar > "$LOG_DIR/gateway.log" 2>&1 &
GATEWAY_PID=$!
# 群机器人通知的实现无条件加签，webhook 地址与密钥必须成对给出，
# 只给地址会在发送时因为空密钥报错。接收器不校验签名，这里给一个任意密钥。
SERVER_PORT="$MONITOR_PORT" SPRING_BOOT_ADMIN_NOTIFY_DINGTALK_WEBHOOK_URL="$WEBHOOK_URL" \
  SPRING_BOOT_ADMIN_NOTIFY_DINGTALK_SECRET="verification-secret" \
  java "${JVM_FLAGS[@]}" -jar mars-cloud-monitor/target/mars-cloud-monitor.jar > "$LOG_DIR/monitor.log" 2>&1 &
MONITOR_PID=$!
for spec in "UPMS:$UPMS_MANAGEMENT_PORT" "示例服务:$SAMPLE_MANAGEMENT_PORT" "网关:$GATEWAY_MANAGEMENT_PORT" "监控面板:$MONITOR_MANAGEMENT_PORT"; do
  name="${spec%%:*}"; port="${spec##*:}"
  if elapsed=$(wait_health "$port"); then ok "$name 就绪（${elapsed}s）"; else bad "$name 未在 90 秒内就绪"; tail -20 "$LOG_DIR"/*.log; exit 1; fi
done

echo
echo "③ 经管理端点把请求日志调到 DEBUG，让这次请求在三个进程里都留下日志行"
raise_level() { # 管理端口 logger
  command curl -s -o /dev/null -w '%{http_code}' -u "$MARS_MANAGEMENT_USERNAME:$MARS_MANAGEMENT_PASSWORD" \
    -X POST -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}' \
    "http://127.0.0.1:$1/actuator/loggers/$2"
}
# 网关的 classpath 上没有安全组件，管理端点按约定收窄为 health 与 info，
# 日志级别端点因此不可达。这既是预期行为，也是收窄确实生效的证据。
check "网关的日志级别端点按收窄不可达" "404" "$(raise_level "$GATEWAY_MANAGEMENT_PORT" org.springframework.cloud.gateway)"
check "网关的健康端点仍匿名可读" "200" "$(command curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_MANAGEMENT_PORT/actuator/health")"
check "网关的指标端点按收窄不可达" "404" "$(command curl -s -o /dev/null -w '%{http_code}' -u "$MARS_MANAGEMENT_USERNAME:$MARS_MANAGEMENT_PASSWORD" "http://127.0.0.1:$GATEWAY_MANAGEMENT_PORT/actuator/prometheus")"
check "示例服务的日志级别端点可写（带凭据）" "204" "$(raise_level "$SAMPLE_MANAGEMENT_PORT" org.springframework.web)"
check "授权决策服务的日志级别端点可写（带凭据）" "204" "$(raise_level "$UPMS_MANAGEMENT_PORT" org.springframework.web)"
anonymous=$(command curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}' "http://127.0.0.1:$SAMPLE_MANAGEMENT_PORT/actuator/loggers/org.springframework.web")
check "匿名写日志级别被拒" "401" "$anonymous"

echo
echo "④ 经网关发起一次跨三进程的请求"
TOKEN="$(cat "$MARS_SECURITY_TOKEN_FILE")"
body=""
waited=0
while [ "$waited" -lt 90 ]; do
  body=$(command curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "X-Mars-Verification: $RUN_ID" \
    -d '{"action":"view","resource":"demo:view:domain:kubernetes-ops"}' \
    "http://127.0.0.1:$GATEWAY_PORT/sample/v1/upms/decision")
  case "$body" in *'"success":true'*) break;; esac
  sleep 1
  waited=$((waited + 1))
done
case "$body" in
  *'"success":true'*) ok "经网关到示例服务再到授权决策服务的调用成功（${waited}s）";;
  *) bad "跨三进程调用未成功" "$body"; exit 1;;
esac

echo
echo "⑤ 三个进程的结构化日志带同一个链路标识"
TRACE_ID="$(python3 "$SERVICE_DIR/scripts/observability-e2e.py" trace-id \
  "$LOG_DIR/gateway.log" "$LOG_DIR/sample.log" "$LOG_DIR/upms.log")"
if [ -z "$TRACE_ID" ]; then bad "三个日志里找不到共同的链路标识"; else ok "共同链路标识 ${TRACE_ID:0:8}…"; fi

python3 "$SERVICE_DIR/scripts/observability-e2e.py" log-fields "$TRACE_ID" \
  "$LOG_DIR/gateway.log" "$LOG_DIR/sample.log" "$LOG_DIR/upms.log" && ok "三份日志各有带该标识的结构化行且 span 标识互不相同" \
  || bad "结构化日志字段不满足要求"

echo
echo "⑥ 追踪后端里是一条链，三个进程各有 span"
sleep 6
python3 "$SERVICE_DIR/scripts/observability-e2e.py" trace "$JAEGER_QUERY" "$TRACE_ID" \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service \
  && ok "追踪后端返回同一条链且三个进程都有 span" || bad "追踪后端的链路不完整"

echo
echo "⑦ 日志推送到日志后端后按链路标识能查回三个服务"
python3 "$SERVICE_DIR/scripts/observability-e2e.py" push-logs "$LOKI" "$RUN_ID" \
  "mars-cloud-gateway:$LOG_DIR/gateway.log" \
  "mars-cloud-sample-service:$LOG_DIR/sample.log" \
  "mars-cloud-upms-service:$LOG_DIR/upms.log" \
  && ok "三份日志已推送" || bad "日志推送失败"
sleep 3
python3 "$SERVICE_DIR/scripts/observability-e2e.py" query-logs "$LOKI" "$RUN_ID" "$TRACE_ID" \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service \
  && ok "按链路标识查回三个服务的日志" || bad "日志后端按链路标识查不回三个服务"

echo
echo "⑧ 监控面板发现全部实例"
MONITOR="http://127.0.0.1:$MONITOR_PORT"
python3 "$SERVICE_DIR/scripts/observability-e2e.py" applications "$MONITOR" \
  "$MONITOR_USERNAME" "$MONITOR_PASSWORD" \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service mars-cloud-monitor \
  && ok "四个应用都在面板里且状态为 UP" || bad "面板没有发现全部实例"

echo
echo "⑨ 实例下线时面板发出通知"
kill "$SAMPLE_PID" 2>/dev/null
wait "$SAMPLE_PID" 2>/dev/null
SAMPLE_PID=""
notified=0
for _ in $(seq 1 60); do
  if grep -q 'mars-cloud-sample-service' "$LOG_DIR/monitor.log" && grep -qE 'OFFLINE|DOWN' "$LOG_DIR/monitor.log"; then
    notified=1
    break
  fi
  sleep 1
done
check "面板日志出现实例状态变化" "1" "$notified"
[ -s "$WEBHOOK_DIR/received" ] && ok "群机器人接收器收到一次通知" || bad "群机器人接收器没有收到通知"

echo
echo "──────────────────────────────"
echo "通过 $pass，失败 $fail"
echo "日志目录 $LOG_DIR"
[ "$fail" -eq 0 ] || exit 1
echo "全部通过。"
