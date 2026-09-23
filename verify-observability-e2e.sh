#!/usr/bin/env bash
#
# 可观测性的真进程验收：一次经网关到示例服务再到授权决策服务的请求，
# 在追踪后端是一条链、三个进程各有 span，三个进程的日志带同一个链路标识，
# 日志按该标识能查回三个服务，Grafana 能从日志行打开这条链，
# 监控面板能看到全部实例并在实例下线时写出日志通知；
# 四个部署物只监听回环地址并注册回环地址，给出私网地址时按它绑定与注册。
#
# 前置：本机中间件已启动（追踪后端、日志后端、注册中心）；四个模块已 clean package；
# 环境文件给出注册中心连接、端口与管理端点凭据；本机有 docker（临时 Grafana 用）。
#
set -uo pipefail

SERVICE_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=SCRIPTDIR/scripts/security-test-runtime.sh
. "$SERVICE_DIR/scripts/security-test-runtime.sh"

ENV_FILE="${E2E_ENV_FILE:-$SERVICE_DIR/mars-cloud-sample-service/.env}"
[ -f "$ENV_FILE" ] || { echo "找不到环境文件 $ENV_FILE" >&2; exit 2; }
set -a
# 环境文件是本机数据，不是要检查的脚本。
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
# 前九步核对缺省的监听与注册地址，环境文件里即使写了这三项也不传给部署物；
# 按给出的地址绑定与注册由第十步单独核对。
unset SERVER_ADDRESS MANAGEMENT_SERVER_ADDRESS SPRING_CLOUD_NACOS_DISCOVERY_IP

GATEWAY_PORT="${GATEWAY_PORT:-8100}"
UPMS_PORT="${UPMS_PORT:-8102}"
SAMPLE_PORT="${SAMPLE_PORT:-8103}"
MONITOR_PORT="${MONITOR_PORT:-8190}"
MONITOR_MANAGEMENT_PORT="${MONITOR_MANAGEMENT_PORT:-$((MONITOR_PORT + 1000))}"
GATEWAY_MANAGEMENT_PORT="${GATEWAY_MANAGEMENT_PORT:-$((GATEWAY_PORT + 1000))}"
UPMS_MANAGEMENT_PORT="${UPMS_MANAGEMENT_PORT:-$((UPMS_PORT + 1000))}"
SAMPLE_MANAGEMENT_PORT="${SAMPLE_MANAGEMENT_PORT:-$((SAMPLE_PORT + 1000))}"

UPMS_JAR="$SERVICE_DIR/mars-cloud-upms-service/target/mars-cloud-upms-service.jar"
SAMPLE_JAR="$SERVICE_DIR/mars-cloud-sample-service/target/mars-cloud-sample-service.jar"
GATEWAY_JAR="$SERVICE_DIR/mars-cloud-gateway/target/mars-cloud-gateway.jar"
MONITOR_JAR="$SERVICE_DIR/mars-cloud-monitor/target/mars-cloud-monitor.jar"
for jar in "$UPMS_JAR" "$SAMPLE_JAR" "$GATEWAY_JAR" "$MONITOR_JAR"; do
  [ -f "$jar" ] || { echo "找不到 $jar，先对四个模块执行 clean package" >&2; exit 2; }
done

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
  for pid in "${MONITOR_PID:-}" "${GATEWAY_PID:-}" "${SAMPLE_PID:-}" "${UPMS_PID:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null
  done
  stop_security_test_issuer
  [ -n "${GRAFANA_CONTAINER:-}" ] && docker rm -f "$GRAFANA_CONTAINER" >/dev/null 2>&1
}
trap cleanup EXIT

if ! python3 - "$GATEWAY_PORT" "$UPMS_PORT" "$SAMPLE_PORT" "$MONITOR_PORT" \
  "$GATEWAY_MANAGEMENT_PORT" "$UPMS_MANAGEMENT_PORT" "$SAMPLE_MANAGEMENT_PORT" "$MONITOR_MANAGEMENT_PORT" <<'PY'
import socket, sys
for value in sys.argv[1:]:
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", int(value)))
PY
then
  echo "验收端口已被占用" >&2
  exit 2
fi

echo "① 启动测试签发器与临时 Grafana"
start_security_test_issuer "$SERVICE_DIR" || exit 1
ok "测试签发器就绪"
# 临时 Grafana 只用来核对本工作树的候选数据源配置：与本机第一套同一镜像摘要，
# 匿名管理员，只绑回环地址的随机端口，不接入中间件的网络，验收结束即删除。
# 第一套 Grafana 只在启动时读取数据源配置，不受影响。
GRAFANA_IMAGE="grafana/grafana@$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["images"]["grafana"]["digest"])' \
  "$SERVICE_DIR/dev/images.lock.json")"
GRAFANA_CONTAINER="observability-e2e-grafana-$RUN_ID"
if ! docker run -d --rm --name "$GRAFANA_CONTAINER" -p 127.0.0.1::3000 \
    -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin -e GF_AUTH_DISABLE_LOGIN_FORM=true \
    -v "$SERVICE_DIR/dev/config/datasources.yaml:/etc/grafana/provisioning/datasources/local.yaml:ro" \
    "$GRAFANA_IMAGE" >/dev/null; then
  echo "临时 Grafana 启动失败" >&2
  exit 1
fi
ok "临时 Grafana 已启动"

wait_health() { # 管理端口 [秒数] [地址]
  local port="$1" limit="${2:-90}" host="${3:-127.0.0.1}" waited=0
  while [ "$waited" -lt "$limit" ]; do
    command curl -fsS "http://$host:$port/actuator/health" >/dev/null 2>&1 && { echo "$waited"; return 0; }
    sleep 1
    waited=$((waited + 1))
  done
  echo "$waited"
  return 1
}

echo
echo "② 启动四个部署物"
SERVER_PORT="$UPMS_PORT" java "${JVM_FLAGS[@]}" -jar "$UPMS_JAR" > "$LOG_DIR/upms.log" 2>&1 &
UPMS_PID=$!
SERVER_PORT="$SAMPLE_PORT" java "${JVM_FLAGS[@]}" -jar "$SAMPLE_JAR" > "$LOG_DIR/sample.log" 2>&1 &
SAMPLE_PID=$!
# 网关的日志级别只能在启动时给：它没有可写的日志级别端点（见下一节）。
SERVER_PORT="$GATEWAY_PORT" LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_CLOUD_GATEWAY=DEBUG \
  java "${JVM_FLAGS[@]}" -jar "$GATEWAY_JAR" > "$LOG_DIR/gateway.log" 2>&1 &
GATEWAY_PID=$!
SERVER_PORT="$MONITOR_PORT" \
  java "${JVM_FLAGS[@]}" -jar "$MONITOR_JAR" > "$LOG_DIR/monitor.log" 2>&1 &
MONITOR_PID=$!
for spec in "UPMS:$UPMS_MANAGEMENT_PORT" "示例服务:$SAMPLE_MANAGEMENT_PORT" "网关:$GATEWAY_MANAGEMENT_PORT" "监控面板:$MONITOR_MANAGEMENT_PORT"; do
  name="${spec%%:*}"; port="${spec##*:}"
  if elapsed=$(wait_health "$port"); then ok "$name 就绪（${elapsed}s）"; else bad "$name 未在 90 秒内就绪"; tail -20 "$LOG_DIR"/*.log; exit 1; fi
done

echo
echo "③ 经管理端点把请求日志调到 DEBUG，让这次请求在三个进程里都留下日志行"
# Basic 凭据经标准输入以 curl 配置的形式传入，不出现在命令行参数里（printf 是 shell 内建命令）。
# curl 配置里双引号内的值要转义反斜杠与双引号。
config_quote() { local value="${1//\\/\\\\}"; printf '%s' "${value//\"/\\\"}"; }
basic_curl() { # 账号 口令 curl 参数…
  local account phrase
  account="$(config_quote "$1")"
  phrase="$(config_quote "$2")"
  shift 2
  printf 'user = "%s:%s"\n' "$account" "$phrase" | command curl -K - "$@"
}
management_curl() { basic_curl "$MARS_MANAGEMENT_USERNAME" "$MARS_MANAGEMENT_PASSWORD" "$@"; }
monitor_curl() { basic_curl "$MONITOR_USERNAME" "$MONITOR_PASSWORD" "$@"; }
raise_level() { # 管理端口 logger
  management_curl -s -o /dev/null -w '%{http_code}' \
    -X POST -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}' \
    "http://127.0.0.1:$1/actuator/loggers/$2"
}
# 网关的 classpath 上没有安全组件，管理端点按约定收窄为 health 与 info，
# 日志级别端点因此不可达。这既是预期行为，也是收窄确实生效的证据。
check "网关的日志级别端点按收窄不可达" "404" "$(raise_level "$GATEWAY_MANAGEMENT_PORT" org.springframework.cloud.gateway)"
check "网关的健康端点仍匿名可读" "200" "$(command curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_MANAGEMENT_PORT/actuator/health")"
check "网关的指标端点按收窄不可达" "404" \
  "$(management_curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_MANAGEMENT_PORT/actuator/prometheus")"
check "示例服务的日志级别端点可写（带凭据）" "204" "$(raise_level "$SAMPLE_MANAGEMENT_PORT" org.springframework.web)"
check "授权决策服务的日志级别端点可写（带凭据）" "204" "$(raise_level "$UPMS_MANAGEMENT_PORT" org.springframework.web)"
anonymous=$(command curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}' "http://127.0.0.1:$SAMPLE_MANAGEMENT_PORT/actuator/loggers/org.springframework.web")
check "匿名写日志级别被拒" "401" "$anonymous"
check "授权决策服务的指标端点匿名被拒" "401" \
  "$(command curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$UPMS_MANAGEMENT_PORT/actuator/prometheus")"
check "授权决策服务的指标端点带凭据可读" "200" \
  "$(management_curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$UPMS_MANAGEMENT_PORT/actuator/prometheus")"
# 路径带示例服务的 context path：管理端点若回到业务端口，它在这个地址上返回 200。
check "示例服务的业务端口上没有管理端点" "404" \
  "$(security_curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$SAMPLE_PORT/sample/actuator/health")"

echo
echo "④ 经网关发起一次跨三进程的请求"
# 访问令牌经头文件传给 curl，不出现在命令行参数里。
body=""
waited=0
while [ "$waited" -lt 90 ]; do
  body=$(security_curl -s -H 'Content-Type: application/json' \
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

if python3 "$SERVICE_DIR/scripts/observability-e2e.py" log-fields "$TRACE_ID" \
  "$LOG_DIR/gateway.log" "$LOG_DIR/sample.log" "$LOG_DIR/upms.log"; then
  ok "三份日志各有带该标识的结构化行且 span 标识互不相同"
else
  bad "结构化日志字段不满足要求"
fi

echo
echo "⑥ 追踪后端里是一条链，三个进程各有 span"
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" trace "$JAEGER_QUERY" "$TRACE_ID" \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service; then
  ok "追踪后端返回同一条链且三个进程都有 span"
else
  bad "追踪后端的链路不完整"
fi

echo
echo "⑦ 日志推送到日志后端后按链路标识能查回三个服务，Grafana 能从日志行打开这条链"
# 日志要推送到日志后端，推送前先确认里面没有测试签发器的令牌。进程还在写日志，
# 所以先拷一份快照，检查与推送用同一份。
PUSHED="$LOG_DIR/pushed"
mkdir -p "$PUSHED"
cp "$LOG_DIR/gateway.log" "$LOG_DIR/sample.log" "$LOG_DIR/upms.log" "$LOG_DIR/monitor.log" "$PUSHED/"
if verify_no_test_credentials "$PUSHED/gateway.log" "$PUSHED/sample.log" "$PUSHED/upms.log" "$PUSHED/monitor.log"; then
  ok "四份日志里没有测试令牌"
else
  bad "日志里出现了测试令牌，不推送"
  exit 1
fi
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" push-logs "$LOKI" "$RUN_ID" \
  "mars-cloud-gateway:$PUSHED/gateway.log" \
  "mars-cloud-sample-service:$PUSHED/sample.log" \
  "mars-cloud-upms-service:$PUSHED/upms.log"; then
  ok "三份日志已推送"
else
  bad "日志推送失败"
fi
sleep 3
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" query-logs "$LOKI" "$RUN_ID" "$TRACE_ID" \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service; then
  ok "按链路标识查回三个服务的日志"
else
  bad "日志后端按链路标识查不回三个服务"
fi
GRAFANA="http://127.0.0.1:$(docker port "$GRAFANA_CONTAINER" 3000/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do command curl -fs "$GRAFANA/api/health" >/dev/null 2>&1 && break; sleep 1; done
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" trace-link "$GRAFANA" "$TRACE_ID" \
  "$PUSHED/gateway.log" "$PUSHED/sample.log" "$PUSHED/upms.log"; then
  ok "Grafana 的关联字段能从三份日志取出本次链路标识并链到 Jaeger"
else
  bad "Grafana 的关联字段不能从日志链到这条调用链"
fi

echo
echo "⑧ 监控面板发现全部实例"
# 读面板的辅助命令从导出的 MONITOR_USERNAME 与 MONITOR_PASSWORD 取账号，不经命令行参数。
MONITOR="http://127.0.0.1:$MONITOR_PORT"
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" applications "$MONITOR" \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service mars-cloud-monitor; then
  ok "四个应用都在面板里且状态为 UP"
else
  bad "面板没有发现全部实例"
fi
# 实例显示 UP 只需要匿名可读的健康端点；经面板读一个需要认证的端点，才能证明面板带的实例凭据有效。
SAMPLE_INSTANCE_ID="$(python3 "$SERVICE_DIR/scripts/observability-e2e.py" instance-ids "$MONITOR" \
  mars-cloud-sample-service | head -1)"
check "经面板读取示例服务的指标端点" "200" "$(monitor_curl -s -o /dev/null -w '%{http_code}' \
  -H 'Accept: application/json' "$MONITOR/instances/$SAMPLE_INSTANCE_ID/actuator/metrics")"
# 没有给出 SERVER_ADDRESS 时，四个部署物的业务端口与管理端口都只绑回环地址，注册到 Nacos 的也是它。
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" loopback-only \
  "$GATEWAY_PORT" "$GATEWAY_MANAGEMENT_PORT" "$UPMS_PORT" "$UPMS_MANAGEMENT_PORT" \
  "$SAMPLE_PORT" "$SAMPLE_MANAGEMENT_PORT" "$MONITOR_PORT" "$MONITOR_MANAGEMENT_PORT"; then
  ok "四个部署物的业务端口与管理端口从本机的非回环地址连不上"
else
  bad "有部署物的端口在非回环地址上可达"
fi
if python3 "$SERVICE_DIR/scripts/observability-e2e.py" registered-host "$MONITOR" 127.0.0.1 \
  mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service mars-cloud-monitor; then
  ok "四个应用的实例都按回环地址注册，面板按它读取管理端点"
else
  bad "有实例的注册地址不是回环地址"
fi

echo
echo "⑨ 实例下线时面板写出日志通知"
# 只认停机之后、为这一个示例服务实例写的通知行。面板若在示例服务的管理端口就绪之前发现它，
# 启动阶段就会为它记一行 OFFLINE；不限定停机之后的行，检查会被那一行满足而不再等真正的下线。
SAMPLE_INSTANCE_IDS="$(python3 "$SERVICE_DIR/scripts/observability-e2e.py" instance-ids "$MONITOR" \
  mars-cloud-sample-service | paste -sd '|' -)"
MONITOR_LOG_OFFSET="$(wc -l < "$LOG_DIR/monitor.log" | tr -d ' ')"
kill "$SAMPLE_PID" 2>/dev/null
wait "$SAMPLE_PID" 2>/dev/null
SAMPLE_PID=""
# 示例服务停止时先从 Nacos 注销、再等 10 秒才停机；面板的状态轮询与发现刷新互不等待，
# 先到的一方决定通知是状态变化（OUT_OF_SERVICE 等）还是移除（DEREGISTERED），两种都算。
# 移除在注销后一个 watch-delay（30 秒）内必然发生，所以 60 秒内一定有其中一行。
notified=0
notification=""
for _ in $(seq 1 60); do
  [ -n "$SAMPLE_INSTANCE_IDS" ] || break
  notification="$(tail -n +"$((MONITOR_LOG_OFFSET + 1))" "$LOG_DIR/monitor.log" \
      | grep -E '"logger":"de\.codecentric\.boot\.admin\.server\.notify\.LoggingNotifier"' \
      | grep -oE "Instance mars-cloud-sample-service \(($SAMPLE_INSTANCE_IDS)\) (is (OFFLINE|DOWN|OUT_OF_SERVICE)|DEREGISTERED)" \
      | head -1 || true)"
  if [ -n "$notification" ]; then
    notified=1
    break
  fi
  sleep 1
done
check "面板的日志通知记录了示例服务下线（状态变化或移除）" "1" "$notified"
[ -z "$notification" ] || echo "       通知：$notification"

echo
echo "⑩ 给出私网地址时按它绑定与注册"
# 用本机的非回环地址代替部署时的私网地址重启示例服务：两个端口只在这个地址上可连、在回环地址上连不上，
# 面板里它的注册地址也是这个地址。下线的旧实例在面板下一次发现刷新时移除，检查会等到那时。
PRIVATE_ADDRESS="$(python3 "$SERVICE_DIR/scripts/observability-e2e.py" outbound-address)"
if [ -z "$PRIVATE_ADDRESS" ]; then
  bad "本机没有非回环地址，无法核对按给出的地址绑定"
else
  SERVER_ADDRESS="$PRIVATE_ADDRESS" SERVER_PORT="$SAMPLE_PORT" \
    java "${JVM_FLAGS[@]}" -jar "$SAMPLE_JAR" > "$LOG_DIR/sample-private-address.log" 2>&1 &
  SAMPLE_PID=$!
  if elapsed=$(wait_health "$SAMPLE_MANAGEMENT_PORT" 90 "$PRIVATE_ADDRESS"); then
    ok "示例服务按给出的地址就绪（${elapsed}s）"
    if python3 "$SERVICE_DIR/scripts/observability-e2e.py" listens-only-on "$PRIVATE_ADDRESS" \
      "$SAMPLE_PORT" "$SAMPLE_MANAGEMENT_PORT"; then
      ok "示例服务的业务端口与管理端口只在给出的地址上可连"
    else
      bad "示例服务的端口没有只绑定给出的地址"
    fi
    if python3 "$SERVICE_DIR/scripts/observability-e2e.py" registered-host "$MONITOR" "$PRIVATE_ADDRESS" \
      mars-cloud-sample-service; then
      ok "示例服务按给出的地址注册，面板读到它且为 UP"
    else
      bad "示例服务的注册地址不是给出的地址"
    fi
  else
    bad "示例服务按给出的地址未在 90 秒内就绪"
    tail -20 "$LOG_DIR/sample-private-address.log"
  fi
fi

echo
echo "──────────────────────────────"
echo "通过 $pass，失败 $fail"
echo "日志目录 $LOG_DIR"
[ "$fail" -eq 0 ] || exit 1
echo "全部通过。"
