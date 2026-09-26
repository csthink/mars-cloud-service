# mars-cloud-gateway

系统外部请求的**统一入口**：把外部请求路由到各业务服务。响应式栈（Spring Cloud Gateway + WebFlux）。

- 入口类：`com.mars.cloud.service.gateway.GatewayApplication`
- 端口：`8100`；管理端口 `9100`；无 context path（路径原样转发给目标服务）
- 健康检查：管理端口上的 `GET /actuator/health`（`http://127.0.0.1:9100/actuator/health`）
- 错误码区间：`63000–63999`（框架权威分配表里的 `gateway` 区段）

## 路由

路由在 `config/application.yml` 里**显式声明**，目标用服务名走注册中心的客户端负载均衡：

| 入口 Host | 路径 | 目标 | 使用环境 |
| --- | --- | --- | --- |
| `auth.flippoabc.com` | `/oauth2/**`、`/.well-known/**`、`/login`、`/login/**`、`/logout`、`/connect/logout`、`/userinfo` | `lb://mars-cloud-auth-service` | 全部 |
| `api.flippoabc.com` | `/auth/**` | `lb://mars-cloud-auth-service` | 全部 |
| `api.flippoabc.com` | `/product/**` | `lb://mars-cloud-product-service` | 全部 |
| `api.flippoabc.com` | `/order/**` | `lb://mars-cloud-order-service` | 全部 |
| `api.flippoabc.com` | `/notice/**` | `lb://mars-cloud-notice-service` | 全部 |
| `api.flippoabc.com` | `/upms/**` | `lb://mars-cloud-upms-service` | local / test |
| `api.flippoabc.com` | `/sample/**` | `lb://mars-cloud-sample-service` | local / test |

路径原样转发，不去掉前缀。local / test 且网关实际监听回环地址时，也接受回环 Host；其他环境只接受表中的正式 Host。未知 Host、认证域名上的业务路径及非本地环境中的 `/upms/**`、`/sample/**` 返回 `63001/404`。已匹配路由但目标暂无实例时返回 `63002/503`。

## JWT 验证

网关从 `MARS_SECURITY_ISSUER_URI` 读取签发方地址，可选从 `MARS_SECURITY_JWK_SET_URI` 读取公钥集合地址；启动前必须配置签发方。受保护请求的 Bearer 令牌须通过签名、签发方、时效与 `mars-cloud-gateway` audience 校验。网关使用响应式安全链，验证失败时返回 security starter 的统一错误信封；业务服务仍验证自己的 audience 并执行权限判断。

认证 Host 的签发方端点清单允许不带访问令牌；其中发码、验证码校验、令牌与授权端点按来源地址限流，见「限流」。API Host 的 `/auth/**`、`/order/**`、`/upms/**`、`/sample/**` 需要令牌；`/product/**` 与 `/notice/**` 的 GET 允许匿名，但其 `v1/me` 和 `v1/admin` 路径需要令牌，其他请求方法也需要令牌。未知 Host 和未匹配路径仍返回 404。

对 API 请求中已验证的 Bearer 令牌，网关要求合法的 `sid` 声明，并通过响应式 Redis 读取会话撤销状态。已撤销或 `sid` 无效时返回 `62002/401`；撤销状态最多缓存 5 秒。Redis 不可用且没有未过期缓存时返回 `63005/503`，请求不会转发给业务服务。匿名公开读取和认证 Host 的签发方端点无需查询 Redis。Redis 地址与库号由标准 `SPRING_DATA_REDIS_*` 环境变量提供；管理端口的健康检查包含 Redis 状态。

`/*/v1/admin/**` 需要已验证令牌中的 `client_id=console`，且请求来源 IP 必须位于 `mars.gateway.admin.allowed-cidrs`。该键放在 Nacos 的 `DEFAULT_GROUP/mars-cloud-gateway.yaml`，填写逗号分隔的 IP 字面量或 CIDR，不在版本库中保存具体地址。未配置或配置为空时，全部管理请求返回 `62003/403`。Nacos 配置刷新后，网关只在整份列表解析成功时替换白名单；非法更新产生告警并保留上一份有效规则。来源 IP 取自网关已核对的 TCP 对端或可信代理链，外部自报的 `X-Forwarded-For` 不直接参与判断。路径含编码斜杠、编码点、`..` 段、重复斜杠或分号参数时，在安全链匹配前返回 400。

API 跨域只对 API Host 的表内路径生效。允许的正式页面来源为 `https://flippoabc.com`、`https://word.flippoabc.com`、`https://console.flippoabc.com`；允许 GET、POST、PUT、PATCH、DELETE、OPTIONS，以及 Authorization、Content-Type、Accept、Idempotency-Key 请求头。未列入的来源被拒绝，不提供凭据型跨域许可。local / test 如需浏览器开发服务器跨域，用 `MARS_GATEWAY_CORS_LOCAL_ORIGINS` 提供以逗号分隔的完整回环 origin，例如 `http://127.0.0.1:5173`；正式环境不接受该配置。认证域名及 `/userinfo` 不提供跨域许可。

## 来源地址与请求头

网关在路由与跨域处理前读取 TCP 对端，并删除外部请求中的 `Forwarded`、全部 `X-Forwarded-*` 和三个内部身份头 `X-Mars-Subject`、`X-Mars-Client-Id`、`X-Mars-Tenant-Id`。转发给业务服务时，转发头只保留网关核对后生成的 `X-Forwarded-For`、`X-Forwarded-Host`、`X-Forwarded-Proto`、`X-Forwarded-Port`；认证签发路径保留已核对的原始 Host。

`MARS_GATEWAY_TRUSTED_HOP_COUNT` 默认 `0`，此时来源 IP 是 TCP 对端，外部转发链不参与判定。接入可信负载均衡后，将它设为可信代理数量，并用 `MARS_GATEWAY_DIRECT_PEER_CIDRS` 列出可直接连接网关的代理 IP 地址段，多个 CIDR 用逗号分隔。非可信对端返回 403；缺失、重复或非法的转发链返回 400。正式环境中，可信代理还须提供单一的 HTTPS `X-Forwarded-Proto`。业务端口应仅允许配置的负载均衡连接；独立管理端口不要求这些头。

刻意**不开** `discovery.locator`：开了以后注册中心里的每个服务都会被自动暴露，
网关就不再是「显式声明的唯一入口」。新增业务服务时在这里加一条路由。

## 限流

网关经 Sentinel 组件按路由、API 分组与客户端地址限流。规则只从 Nacos 读取，Group 为 `SENTINEL_GROUP`，与应用配置同一个命名空间：

| Data ID | 内容 |
| --- | --- |
| `mars-cloud-gateway-sentinel-gw-api-group-rules.json` | API 分组：认证入口 `auth-entry`（`/login/sms/send`、`/login/sms/authenticate`、`/oauth2/token`、`/oauth2/authorize`）与回调路径 `/order/v1/callbacks/**` 组成的 `order-callbacks` |
| `mars-cloud-gateway-sentinel-gw-flow-rules.json` | 网关限流规则：按路由 ID 或分组名，可按客户端地址分别计数 |

- 两个配置缺少、为空白或写错时，网关启动失败；某类规则不需要时写 `[]`。
- 运行中修改后不重启即生效；写错、删除或内容改为空白时保留上一批规则，并写一行 ERROR。`[]` 是合法内容，会被接受并清空该类规则。
- 按客户端地址计数用「来源地址与请求头」一节核对后的地址（`GatewayIngressFilter` 写入的交换属性），不用 TCP 对端地址。
  路由暴露检查先于限流执行：不该暴露的路由返回 404，不计入限流。
- 被拒绝的请求返回 429 与 `63006`，只写 DEBUG 日志，不逐条写 WARN；次数计入指标 `mars.sentinel.requests.blocked`，
  经管理端口的 `prometheus` 端点以 Basic 凭据读取（见「管理端点与可观测性」），Prometheus 文本里的名字是 `mars_sentinel_requests_blocked_total`，标签 `resource` 是路由 ID 或分组名。

本机基线在 service 仓的 `dev/config/sentinel/`：七条路由各一条按客户端地址的规则（每个地址每秒 50 次），
`auth-entry` 分组一条按客户端地址的规则（每个地址每分钟 30 次，四个认证入口共用这一个计数；auth-service 自己的发码频控仍然生效），
`order-callbacks` 分组一条不区分地址的总量规则（每秒 100 次，支付渠道的回调来源地址不固定）。生产阈值随部署配置给出。
本机中间件初始化把这两个文件写进基准 Namespace（类型 `json`），并在所选的每个编号环境的 Namespace 里补建缺失的规则配置，
已有的不比较、不覆盖（见 `dev/README.md`）；已有环境用下面的命令把基线写进 `.env` 指定的 Namespace（同名覆盖）：

```bash
python3 sentinel-rules.py --env-file .env publish
```

规则格式、校验与指标见框架仓 `mars-cloud-sentinel-spring-boot-starter` 的使用说明。

## 响应契约

网关与业务服务返回**同一种信封**（`mars-cloud-common` 的 `UnifyResponse`），
外部调用方不需要区分「错误是网关产生的还是服务产生的」：

| 情形 | 谁产生 | HTTP | 信封 |
| --- | --- | --- | --- |
| 路由成功，目标服务正常返回 | 业务服务 | 目标服务的状态 | **原样透传**，网关不改写、不二次包装（含目标服务自己的失败信封） |
| 路径没有匹配任何路由 | 网关 | 404 | `success:false`、`code:"63001"` |
| 目标服务在注册中心里没有可用实例 | 网关 | 503 | `success:false`、`code:"63002"` |
| 已选中实例但连接失败（拒绝连接、主机名解析失败） | 网关 | 502 | `success:false`、`code:"63003"` |
| 连接已建立但目标服务在规定时间内未响应 | 网关 | 504 | `success:false`、`code:"63004"` |
| 无法读取会话撤销状态且没有有效缓存 | 网关 | 503 | `success:false`、`code:"63005"` |
| 请求被限流规则拒绝（见「限流」） | 网关 | 429 | `success:false`、`code:"63006"` |
| 其他无法归类的异常 | 网关 | 对应状态 | `code` 为 HTTP 状态码本身（如 `"500"`），与业务服务的兜底约定一致 |

`message` 走 i18n（`i18n/error-code*.properties`，按请求的 `Accept-Language` 选择）。
`mars.env.dev-profiles` 命中时 `result` 回带调试详情（`detail` / `ip` / `method` / `uri` / `headers`），
键与业务服务一致；其他环境下 `result` 缺席。

```bash
# UPMS 未启动时经网关访问：
curl -i -X POST -H "Authorization: Bearer $ACCESS_TOKEN" http://127.0.0.1:8100/upms/v1/decision
# HTTP/1.1 503
# {"success":false,"code":"63002","message":"目标服务当前没有可用实例"}
```

这里的 `ACCESS_TOKEN` 须包含 `mars-cloud-gateway` audience；没有有效令牌时网关先返回 401。

## 管理端点与可观测性

框架的 observability starter 给出管理端口 `9100`（业务端口加 1000）、链路追踪与结构化日志：

- 业务端口与管理端口都只绑定 `SERVER_ADDRESS`（本机缺省 `127.0.0.1`），注册到 Nacos 的也是这个地址；部署时填实例的私网 IPv4 地址，见 [部署说明](../docs/deployment.md) 的「端口与 context path」。
- 网关已接入 Spring Security；提供管理端点凭据后，`health` 允许匿名访问，`prometheus` 等端点要求 Basic 认证。缺少凭据时开发环境只暴露 `health` 与 `info`，正式环境启动失败。
- 每个经网关转发的请求在追踪后端里有网关的服务端与客户端两个 span，`traceparent` 随请求转发给目标服务，
  下游进程接续同一条 trace。
- 控制台日志是带 `traceId` 的 JSON。网关是响应式栈，请求处理会在 Reactor 线程之间切换，
  starter 打开了 Reactor 的自动上下文传播，切换线程后的日志行仍带当前请求的 `traceId`。

## 本地启动

```bash
cp ../.env.example .env  # 填写本机 Nacos Namespace 与账号
./run-local.sh           # 显式加载 .env，再以 local profile 启动
```

先启动 Nacos，并在同一 Namespace 下准备：

- `COMMON/shared-common.yaml`
- `DEFAULT_GROUP/mars-cloud-gateway.yaml`
- `SENTINEL_GROUP/mars-cloud-gateway-sentinel-gw-api-group-rules.json`
- `SENTINEL_GROUP/mars-cloud-gateway-sentinel-gw-flow-rules.json`

四条都必须存在（网关不接受 `optional:` 导入，缺少限流规则时也不启动）。两条限流规则用「限流」一节的命令写入。应用配置里可以只放一个非敏感的版本标记：

```yaml
mars:
  gateway:
    nacos:
      config-revision: revision-1
```

路由目标（UPMS）**不必先起来**：没有实例时网关返回信封式 503，UPMS 上线后网关自动发现，不需要重启。

### `java -jar` 启动

```bash
set -a && . ./.env && set +a
java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -jar target/mars-cloud-gateway.jar
```

两个 JVM 参数的原因与各入口的固化位置见 [`docs/deployment.md`](../docs/deployment.md) 的「JVM 参数」一节：
`run-local.sh`（`mvn spring-boot:run`）由框架 BOM 统一配好，容器由 `Dockerfile` 的 `ENTRYPOINT` 带上，
手工 `java -jar` 需要自己写。

## 端到端验收

`verify-e2e.sh` 用**真进程**验证打包出来的 jar：先起网关、后起 UPMS，逐条核对上表承诺的行为，
包括「网关先于业务服务启动，业务服务上线后网关自动发现」这条启动顺序约定。

`verify-revocation-e2e.sh` 使用打包后的网关、`dev/images.lock.json` 指定的独立 Redis 容器与本地 HTTP 上游，核对会话撤销、无效 `sid`、Redis 停止后的 `63005/503` 和恢复。运行前需打包 gateway 与 sample 模块，并在本机准备已锁定的 Redis 镜像；脚本使用 `local,test` profile，不连接 Nacos，也不停止共用中间件；限流规则只从 Nacos 读取，所以这个进程关闭限流组件，限流由 `verify-sentinel-e2e.sh` 单独验收。

真实 Nacos 白名单刷新可在独立 Namespace 中显式运行 `GatewayAdminNacosRefreshCheck`。先加载本模块的 `.env`，再执行 `MARS_REAL_NACOS_TEST=true mvn -Preal-nacos-check -Dtest=GatewayAdminNacosRefreshCheck test`。该 profile 才会编译真实 Nacos 验收类。检查会暂时更新该 Namespace 的网关应用配置，并在结束前写回原内容、等待读回一致；默认测试流程不运行这项会修改 Nacos 配置的检查。

```bash
mvn -f .. package                 # 网关与 UPMS 都需要 package
./verify-e2e.sh                   # 需要本机 Nacos 与本目录的 .env
```

`verify-sentinel-e2e.sh` 启动网关的真实进程，经 Nacos 下发限流规则并核对：缺少规则配置时启动失败、修改后不重启生效、
按客户端地址分别计数、回调分组计总数、认证入口分组按客户端地址计数、拦截次数经管理端口凭据可读而匿名不可读、
写错与删除都保留上一批、重启后规则仍在、被拒绝的响应是 429 与 `63006`、只监听业务端口与管理端口、Sentinel 不写文件。
它以可信代理数量 1 启动网关，只请求不需要访问令牌的路径，放行的请求打到没有实例的 product 或 auth 服务、以 503 结束，不需要其他服务；
不核对令牌，也不查询 Redis：签发方地址取 `.env` 的 `MARS_SECURITY_ISSUER_URI`，未提供时用回环占位地址，本进程关闭 Redis 健康检查项。
脚本会改写 `.env` 所指 Namespace 里的两个规则配置，结束时把 `dev/config/sentinel/` 的基线写回，写回失败时退出码非零。

```bash
mvn -f .. package -pl mars-cloud-gateway -am
./verify-sentinel-e2e.sh          # 需要本机 Nacos 与本目录的 .env（含管理端点凭据）；SENTINEL_E2E_ENV_FILE 可另指环境文件
```

`verify-ingress-e2e.sh` 另外启动网关、auth-service 和 sample 的真实进程，验证可信代理数量为 1 时的登录路由、缺失或非法转发头、非可信对端、无令牌的 sample 请求及独立管理端口。运行前准备三个模块的受保护 `.env`、auth-service 的专用空数据库和本地 HTTPS CA，并把网关实例的回环地址加入本地 auth-service 的 `MARS_AUTH_TRUSTED_GATEWAY_CIDRS`。用 `keytool` 将该 CA 导入独立的 PKCS12 信任库，再提供 `INGRESS_E2E_CA_FILE`、`INGRESS_E2E_TRUST_STORE`、`INGRESS_E2E_TRUST_PASSWORD`。默认业务端口为 8301、8300、8303；可分别用 `INGRESS_E2E_AUTH_PORT`、`INGRESS_E2E_GATEWAY_PORT`、`INGRESS_E2E_SAMPLE_PORT` 调整。脚本只创建并停止本次进程，不清理数据库。

## 配置：分层与来源

**原则：配置项进版本库，配置项的「环境相关取值」只从环境变量来。**

| 层 | 位置 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、端口、监听地址、路由表、i18n、开发环境 profile 列表 |
| local profile | `src/main/resources/config/application-local.yml` | ✅ | **仅**行为开关（时区）。**刻意不含任何连接信息** |
| Nacos 共享配置 | `COMMON/shared-common.yaml` | ❌ | 跨服务的非敏感动态默认值 |
| Nacos 应用配置 | `DEFAULT_GROUP/mars-cloud-gateway.yaml` | ❌ | 网关的非敏感动态覆盖值 |
| Nacos 限流规则 | `SENTINEL_GROUP/mars-cloud-gateway-sentinel-gw-*-rules.json` | ❌ | API 分组与网关限流规则，见「限流」 |
| 环境取值 | 环境变量（开发时用 `.env` 承载） | ❌ `.env` 忽略；[`.env.example`](../.env.example) 是模板 | 地址、Namespace、账号 |

`application-local.yml` 能进版本库，是因为它**不含任何环境相关的取值**；这条由 `LocalConfigHygieneTest` 守护。

## 依赖边界

- 直接依赖框架仓的 `mars-cloud-common`（信封与错误码契约）、`mars-cloud-nacos-spring-boot-starter`、
  `mars-cloud-observability-spring-boot-starter` 与 `mars-cloud-security-spring-boot-starter`；管理端点认证使用 `spring-boot-starter-security`，会话撤销查询使用 `spring-boot-starter-data-redis`
  **不引入** Servlet 栈的 `mars-cloud-mvc-spring-boot-starter`——它的统一响应、异常处理与错误码校验都是 Servlet 实现，
  网关在 `web` 包里为响应式栈单独实现了同一契约
- 错误码区间的启动期校验器也在 mvc starter 里，网关引不了，改由 `GatewayErrorCodeTest` 守住区间与 i18n 完整性
- `lb://` 路由需要 `spring-cloud-starter-loadbalancer`（nacos-discovery starter 不传递它）；实例缓存用 Caffeine
- 与其他服务之间**不加编译期依赖**，只走 HTTP
