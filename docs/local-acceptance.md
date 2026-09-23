# 本地运行与验收

本文说明在本机用 [`dev/` 编排的中间件](../dev/README.md) 运行 gateway、UPMS、sample 与监控面板 monitor 四个部署物，并完成验收：先跑自动验收脚本，再手工启动四个部署物，用命令行检查管理端点、注册与调用链，最后在浏览器里查看监控面板、Jaeger 与 Grafana。

逐步配置 IntelliJ IDEA 启动项、测试签发器与带令牌的业务请求，见 [IDEA 本地运行指南](idea-local-development.md)。除明确说明外，命令都从 `mars-cloud-service` 根目录执行。

文中地址按中间件的默认项目 `mars-lab` 与默认端口偏移 20000 写：中间件端口等于组件基础端口加偏移，例如 Grafana 为 23000、Jaeger UI 为 36686、Loki 为 23100、OTLP 接收端为 24318。使用别的偏移时按同一规则换算。部署物自己的端口不受中间件偏移影响：业务端口 8100、8102、8103、8190，管理端口为业务端口加 1000。

## 1. 前提

| 项 | 核对方法 |
| --- | --- |
| 中间件运行且健康 | `python3 dev/middleware.py status` 列出的容器都是 healthy；首次启动与维护见 [本地中间件说明](../dev/README.md) |
| framework 已安装到本机 Maven 仓 | 按 [IDEA 本地运行指南](idea-local-development.md) 第 3 节在 framework 根目录执行 `mvn clean install` |
| 四个模块都有 `.env` | `mars-cloud-gateway`、`mars-cloud-upms-service`、`mars-cloud-sample-service`、`mars-cloud-monitor` 各一份，从根目录 [`.env.example`](../.env.example) 复制，权限 `0600` |
| 默认端口空闲 | 8100、8102、8103、8190 与 9100、9102、9103、9190 没有被 IDEA 或之前启动的进程占用 |

四份 `.env` 里 Nacos 连接的写法见 IDEA 指南第 4 节。本文还用到下列变量：

| 变量 | 写在哪些 `.env` | 取值 |
| --- | --- | --- |
| `MARS_MANAGEMENT_USERNAME` / `MARS_MANAGEMENT_PASSWORD` | 四份都写，取值相同 | 各部署物管理端点的 Basic 认证账号；面板用同一组凭据读取各实例 |
| `OTLP_TRACING_ENDPOINT` | 四份都写，取值相同 | 默认编排为 `http://127.0.0.1:24318/v1/traces` |
| `MONITOR_USERNAME` / `MONITOR_PASSWORD` | 只写在 `mars-cloud-monitor/.env` | 面板的登录账号，任一为空时面板拒绝启动，见 [面板说明](../mars-cloud-monitor/README.md) |

## 2. 自动验收

```bash
mvn clean package -DskipTests
mars-cloud-sample-service/verify-e2e.sh
mars-cloud-sample-service/verify-feign-e2e.sh
mars-cloud-gateway/verify-e2e.sh
./verify-security-e2e.sh
( set -a; . mars-cloud-monitor/.env; set +a
  JAEGER_QUERY=http://127.0.0.1:36686 LOKI=http://127.0.0.1:23100 ./verify-observability-e2e.sh )
```

| 脚本 | 验证内容 |
| --- | --- |
| `mars-cloud-sample-service/verify-e2e.sh` | 以 `java -jar` 启动示例服务，核对 README 承诺的行为，包括管理端点、响应信封与多语言消息 |
| `mars-cloud-sample-service/verify-feign-e2e.sh` | 示例服务经 Nacos 服务发现调用 UPMS |
| `mars-cloud-gateway/verify-e2e.sh` | 网关接上真实注册中心与 UPMS 后的行为，包括先起网关、后起 UPMS 时不重启就能发现后起的实例 |
| `verify-security-e2e.sh` | 以打包后的示例服务与 UPMS 进程做资源服务器验收，覆盖 development、production 与授权决策服务不可用三种情况 |
| `verify-observability-e2e.sh` | 一次经网关到示例服务再到 UPMS 的请求：追踪后端里是一条链、三个进程各有 span；三份结构化日志带同一个 `traceId`，推送到 Loki 后能按它查回；Grafana 能从日志行打开这条链；面板发现全部实例，并在实例下线时写出日志通知 |

说明：

- 每个脚本自己启动所需进程与临时测试签发器，结束时停止。运行前先停掉 IDEA 或手工启动的实例，否则端口冲突。安全验收以 `Security acceptance passed.` 结束，其余四个脚本输出「通过 N，失败 0」并以「全部通过。」结束；任一项失败时脚本以非零状态退出。
- 可观测性验收默认读 `mars-cloud-sample-service/.env`。面板的登录账号与两个查询地址不在这份文件里，所以先导出 `mars-cloud-monitor/.env`，再给出 `JAEGER_QUERY` 与 `LOKI`。括号里的命令在子 shell 执行，口令不留在当前终端环境，也不出现在命令行参数里。
- 可观测性验收输出的「共同链路标识」只显示 `traceId` 前 8 位。输出末尾的「日志目录」里有这次请求的完整日志，`gateway.log` 还含其他请求的 `traceId`，所以按前 8 位查完整值，第 5 节的浏览器检查要用它：

  ```bash
  grep -o '"traceId":"<前 8 位>[0-9a-f]*"' <日志目录>/gateway.log | head -1
  ```

## 3. 手工启动四个部署物

按 UPMS、sample、gateway、monitor 的顺序启动，每个进程只导出本模块的 `.env`：

```bash
for m in upms-service sample-service gateway monitor; do
  ( set -a; . mars-cloud-$m/.env; set +a
    nohup java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
      -jar mars-cloud-$m/target/mars-cloud-$m.jar > "mars-cloud-$m/target/local-run.log" 2>&1 & )
  sleep 2
done
```

四个管理端口 9102、9103、9100、9190 的 `/actuator/health` 都返回 200 即启动完成。在 IntelliJ IDEA 里启动时按 IDEA 指南第 6 节配置启动项，面板是其中可选的一项。

sample 与 UPMS 的 `.env` 里 `MARS_SECURITY_ISSUER_URI`、`MARS_SECURITY_JWK_SET_URI` 指向测试签发器。签发器没有运行时服务照常启动，业务请求一律得到 `401`；健康检查与第 4 节的检查不受影响。需要带令牌的业务请求时，按 IDEA 指南第 5 节启动测试签发器。

## 4. 命令行检查

`curl -u <账号>` 只给账号时会提示输入口令，口令不进入命令历史。

| 检查 | 命令 | 期望 |
| --- | --- | --- |
| 管理端点匿名健康检查 | `curl -s http://127.0.0.1:9103/actuator/health`（9100、9102、9190 同理） | `{"groups":["liveness","readiness"],"status":"UP"}`：只有聚合状态与探针分组名，没有组件明细 |
| 指标端点要认证 | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9103/actuator/prometheus` | `401`；9102、9190 相同 |
| 带凭据读指标 | `curl -s -u mars-management http://127.0.0.1:9103/actuator/prometheus \| head` | 输入 `MARS_MANAGEMENT_PASSWORD` 后得到 Prometheus 文本；把账号换成 `.env` 里实际填写的值 |
| 网关只暴露 health 与 info | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9100/actuator/prometheus` | `404`，带凭据也是 `404`；`/actuator/info` 匿名返回 `200`。网关没有接入 Spring Security，见 [网关说明](../mars-cloud-gateway/README.md) |
| 业务端口上没有管理端点 | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8100/actuator/health` | `404` |
| 面板读到全部实例 | `curl -s -u monitor-admin -H 'Accept: application/json' http://127.0.0.1:8190/applications` | 四个应用 `mars-cloud-gateway`、`mars-cloud-upms-service`、`mars-cloud-sample-service`、`mars-cloud-monitor`，状态都是 `UP`；各实例的 `managementUrl` 端口依次是 9100、9102、9103、9190。面板刚启动时可能还没列出它自己，稍等再查 |
| 请求留下调用链 | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8100/sample/v1/orders/1`，再执行 `curl -s 'http://127.0.0.1:36686/api/traces?service=mars-cloud-gateway&lookback=5m&limit=5'` | 没有令牌时请求得到 `401`；追踪后端最近的一条链同时含 `mars-cloud-gateway` 与 `mars-cloud-sample-service` 的 span |

## 5. 浏览器检查

| 对象 | 地址 | 登录 | 看什么与期望 |
| --- | --- | --- | --- |
| 监控面板 | <http://127.0.0.1:8190> | `MONITOR_USERNAME` 与 `MONITOR_PASSWORD` | 应用列表有四个应用且都是 UP；点进 `mars-cloud-sample-service` 的实例，「细节」有健康明细，元数据 `management.port` 为 `9103`；「日志配置」里把任一 logger 改为 DEBUG 再改回 INFO，两次都生效；右上角用户菜单「退出」后回到登录页 |
| Jaeger | `http://127.0.0.1:36686/trace/<traceId>` | 不需要 | 一条链横跨 gateway、sample、UPMS：网关有服务端与客户端 span，sample 有服务端与 Feign 客户端 span，UPMS 有服务端 span |
| Grafana | <http://127.0.0.1:23000> | `admin`，口令是 `dev/.local/mars-lab/credentials.json` 的 `grafana` 字段 | Explore 里选 Loki，查询 `{job=~".+"} \|= "<traceId>"`，应列出三个服务的日志行；展开一行的详情，点 `traceId` 旁的链接，右侧分屏打开同一条链 |

`<traceId>` 取第 2 节可观测性验收的完整值。Grafana 13 的日志列表有三处容易看漏：

1. 默认把 JSON 日志美化成多行，一屏只放得下一条。点右侧竖排工具栏的 `{}` 图标关掉美化，每条日志各占一行。
2. 在左侧「Fields」勾选 `service_name`，每行前面显示服务名。
3. 单击日志行只是切换选中。点行首的 `⋮`，选「Show log details」，详情在该行下方展开，`traceId` 的链接在详情里。

Loki 里只有可观测性验收推送的日志，标签是 `job=verify-observability-e2e` 与本次运行的 `run_id`。手工启动的部署物只把日志写到第 3 节的日志文件，不进 Loki。

## 6. 结束

停止手工启动的部署物：

```bash
for p in 9100 9102 9103 9190; do lsof -tiTCP:$p -sTCP:LISTEN; done | xargs kill
```

部署物收到停止信号后先优雅停机，通常几秒内退出。面板若还有浏览器页面开着，页面保持的事件流连接要等到优雅停机的 30 秒超时才断开，面板因此要多等约 30 秒才退出；先关掉面板页面可以避免。

IDEA 启动的实例在 IDEA 里停止。中间件可以保留供下次使用；停止方法见 [本地中间件说明](../dev/README.md) 的 `down`，它保留数据卷与状态。

## 7. 常见现象

| 现象 | 原因与处理 |
| --- | --- |
| 面板里 gateway、UPMS、sample 的地址与面板自己的地址不同 | 实例地址来自 Nacos 注册信息。面板的业务端口、管理端口与注册地址都取 `SERVER_ADDRESS`（默认 `127.0.0.1`）；另外三个部署物注册的是 Spring Cloud Alibaba 自动选取的网卡地址 |
| 业务请求 `401`，健康检查正常 | 测试签发器没有运行，或令牌已超过 5 分钟有效期，见 IDEA 指南第 5 节 |
| 自动验收报端口被占用 | 先停掉 IDEA 或手工启动的实例，见第 6 节 |
| 面板拒绝启动 | `MONITOR_USERNAME`、`MONITOR_PASSWORD`、`MARS_MANAGEMENT_USERNAME`、`MARS_MANAGEMENT_PASSWORD` 任一为空，见 [面板说明](../mars-cloud-monitor/README.md) |
| Grafana 里一屏只看到一条日志 | JSON 美化显示把每条日志展开成多行，见第 5 节第 1 条 |
