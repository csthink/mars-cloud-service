# 部署说明

本仓每个模块都是可独立启动的应用。本文说明构建产物、启动方式、配置来源、
健康检查与上线前检查项。**具体服务的行为细节见各自的模块 README。**

本地开发中间件见 [dev/README.md](../dev/README.md)，包含隔离环境、固定镜像、资源上限与保留数据的停止方式。

## 构建产物

本仓依赖框架仓 `mars-cloud-framework`，而框架尚未发布到制品库，
所以**两个仓必须分别在同一个父目录下**，并把框架先装进本地 Maven 仓库：

```
<任意父目录>/
├── mars-cloud-framework/     # 框架仓（依赖来源，尚未发布 artifact）
└── mars-cloud-service/       # 本仓
```

```bash
# 首次：把框架装进本地 Maven 仓库（框架改动后需重装）
cd ../mars-cloud-framework && mvn clean install

# 构建本仓全部服务
cd ../mars-cloud-service && mvn clean package

# 只构建某一个服务
mvn -pl mars-cloud-upms-service -am clean package
```

每个可部署模块产出一个可执行 fat jar：`<模块目录>/target/<模块名>.jar`
（可部署模块设了 `finalName`，所以文件名不带版本号）。
它已内嵌 Web 容器，**不依赖外置容器**，用 `java -jar` 直接启动。

框架仓的库模块**不带** `finalName`，产物名含版本号——不要照搬上面的命名去本地仓库里找框架 jar。

## 启动

每个模块的产物都是自包含的可执行 jar，**在模块目录下**执行：

| 服务 | 生产式启动 | 本地开发启动 |
| --- | --- | --- |
| `mars-cloud-gateway` | `java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar target/mars-cloud-gateway.jar` | `cd mars-cloud-gateway && ./run-local.sh` |
| `mars-cloud-upms-service` | `java --sun-misc-unsafe-memory-access=allow -jar target/mars-cloud-upms-service.jar` | `cd mars-cloud-upms-service && ./run-local.sh` |
| `mars-cloud-sample-service` | `java --sun-misc-unsafe-memory-access=allow -jar target/mars-cloud-sample-service.jar` | `cd mars-cloud-sample-service && ./run-local.sh` |
| `mars-cloud-monitor` | `java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar target/mars-cloud-monitor.jar` | `cd mars-cloud-monitor && ./run-local.sh` |

`run-local.sh` 会加载模块根目录的 `.env`（若存在）并以 local profile 启动。
网关、UPMS、sample 与监控面板都需要其中的 Nacos Namespace 与账号。
认证服务还需要 MySQL、Redis、持久保存的加密根密钥、明确 HTTPS issuer 和六客户端回跳配置，详见 [认证模块配置](../mars-cloud-auth-service/README.md)。业务端口可使用 HTTPS，管理端口仍使用 HTTP，并通过注册元数据 `management.scheme=http` 明确区分；管理端口只对受控网络开放。

sample 与 UPMS 还必须配置 JWT issuer；可显式提供 JWKS 地址，否则通过 issuer 元数据发现。
两服务的 audience 分别为自己的应用名；sample 调用 UPMS 时，访问令牌的 audience 必须同时包含两者。
缺少认证配置时启动失败；健康探针允许匿名访问，业务接口需要合法 Bearer 令牌。
启动命令中多出的 JVM 参数见下一节。

> **`java -jar` 不会读 `.env`。** Spring Boot 本身没有 `.env` 支持——应用读的是
> **环境变量**；`mvn spring-boot:run`（即 `run-local.sh`）只是恰好会加载模块根目录的
> `.env`。生产环境请把变量真正注入进程（编排平台的 secret、systemd `EnvironmentFile`、
> 容器 env 等），不要指望 `.env` 文件。

## JVM 参数

接入 Nacos 的服务（当前是网关、UPMS、sample 与监控面板）在 JDK 24 及以上启动时必须带：

```
--sun-misc-unsafe-memory-access=allow
```

原因：Spring Cloud Alibaba 2025.1 托管的 nacos-client 3.1.1 内部 shade 了 Guava，
后者调用 `sun.misc.Unsafe` 的内存访问方法。JDK 24 起（JEP 498）默认在首次调用时向
标准错误打印一组以 `WARNING: A terminally deprecated method in sun.misc.Unsafe has been called`
开头的弃用警告，后续 JDK 版本会先改为 `debug` 再改为 `deny`。这是 nacos 上游问题
（[nacos#14070](https://github.com/alibaba/nacos/issues/14070)），不是本仓代码调用了 `Unsafe`；
这个参数是 JDK 给出的规避方式，效果是把该次调用当作允许，不再打印警告。

classpath 上带 Netty 平台原生库的服务（当前是网关与监控面板：两者都带 Reactor Netty，它带来 macOS 的
DNS 解析器与 Linux 的 epoll）还必须带：

```
--enable-native-access=ALL-UNNAMED
```

原因：JDK 24 起（JEP 472）未声明原生访问的模块调用 `System::loadLibrary` 时，会向标准错误打印一组以
`WARNING: A restricted method in java.lang.System has been called` 开头的警告，后续 JDK 版本会改为拒绝。
这个参数是 JDK 给出的声明方式。没有原生库的服务带上无副作用。

参数分别固化在每一类启动入口，不依赖运行者记得：

| 入口 | 固化位置 |
| --- | --- |
| `mvn spring-boot:run`（含 `run-local.sh`） | 框架 BOM `mars-cloud-dependencies` 的 `pluginManagement` 统一给 `spring-boot-maven-plugin` 配置 `jvmArguments`（两个参数都在），本仓不需要再写 |
| 容器 | 模块 `Dockerfile` 的 `ENTRYPOINT`，由各模块的 `NacosIntegrationContractTest` 守护 |
| 手工 `java -jar` | 见上表的启动命令，需要自己写上 |
| IDE 直接运行主类 | IDE 不经过 Maven 插件，需在运行配置的 VM options 里自行加上 |
| 测试 JVM | 框架 BOM 统一给 surefire 的 `argLine` 配置 `--enable-native-access=ALL-UNNAMED`（网关的契约测试会真的发起 HTTP 调用）。Unsafe 那条测试 JVM 不需要：测试明确离线（见「Profile 语义」），Nacos 客户端不会被调用 |
| Maven 进程本身（编译期） | 仓根 `.mvn/jvm.config`。这一处针对的是 Lombok 在 JDK 24 及以上编译期的同一条 Unsafe 警告，与 Nacos 无关；它只作用于 Maven 进程 |

## 配置来源与优先级

**配置项进版本库，配置项的环境相关取值只从环境变量来。**

| 层 | 位置 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、**默认端口**、**默认监听地址**（回环地址）、context path、i18n、错误码区间声明 |
| profile 覆盖 | `src/main/resources/config/application-<profile>.yml` | ✅ | **仅**行为开关（自动装配排除项、时区、功能开关）。**不含任何 host / 端口 / 库名 / 口令** |
| Nacos 动态配置 | 环境 Namespace 下的共享与应用 Data ID | ❌ | 非敏感、需要动态刷新的默认值与应用覆盖值 |
| Nacos 限流规则 | 环境 Namespace 下 `SENTINEL_GROUP` 的 `mars-cloud-gateway-sentinel-gw-api-group-rules.json` 与 `mars-cloud-gateway-sentinel-gw-flow-rules.json` | ❌ | 网关的 API 分组与限流规则，阈值随部署给出；缺少时网关不启动，见网关 README「限流」 |
| 环境取值 | 环境变量 | ❌ | 地址、端口、库名、口令 |

「端口」与「监听地址」在两处出现，口径是：**默认值进版本库**（让新克隆能直接跑起来），
**部署时的实际取值必须由环境变量覆盖**。其余连接类参数则连默认值都不进版本库。

`profile` 覆盖文件之所以能进版本库，是因为它对每个人、每台机器都是同一份。
反过来说：**任何带 host 或口令的配置都不该进仓**。这条约定由各服务的
`LocalConfigHygieneTest` 守护——谁把连接信息写回配置文件，测试就红。

Nacos 内部固定为共享配置先导入、应用配置后导入。环境变量仍用于地址、Namespace 与凭据，
并保持最高优先级。真实凭据不得写入 Nacos 配置正文。

## 环境变量

变量名与说明见仓根 [`.env.example`](../.env.example)；**不要提交 `.env`**（已在 `.gitignore`）。
常用项：

| 变量 | 作用 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 激活的 profile，默认 `local` |
| `SERVER_PORT` | 服务端口，覆盖配置文件里的值 |
| `SERVER_ADDRESS` | 业务端口、管理端口与注册到 Nacos 的地址，缺省 `127.0.0.1`；部署时必填实例的私网 IPv4 地址，见「端口与 context path」 |
| `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE_ID` | Nacos 地址与环境 Namespace ID |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | Nacos 账号与密码 |
| `MARS_SECURITY_ISSUER_URI` | sample / UPMS 必填的可信 JWT issuer，部署环境使用 HTTPS |
| `MARS_SECURITY_JWK_SET_URI` | 可选的 JWKS 地址，仍校验 issuer；部署环境使用 HTTPS |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_PASSWORD` | 数据源 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD` | Redis |
| `MARS_MANAGEMENT_USERNAME` / `MARS_MANAGEMENT_PASSWORD` | 管理端点 Basic 认证的账号；同一环境内各服务相同，监控面板用它读取各实例 |
| `OTLP_TRACING_ENDPOINT` | 调用链导出端点（OTLP over HTTP 的完整地址）；留空则不导出 |
| `MONITOR_USERNAME` / `MONITOR_PASSWORD` | 监控面板的管理员账号，只有 `mars-cloud-monitor` 读取；任一为空即启动失败 |

## Profile 语义

| profile | 用途 | 外部依赖 |
| --- | --- | --- |
| `local`（网关） | 本机开发 | Nacos。路由目标不必先起来 |
| `local`（UPMS） | 本机开发 | Nacos 与可信 JWT 签发方的公钥端点。数据源与 Redis 的自动装配保持关闭 |
| `local`（sample） | 本机开发 | Nacos 与可信 JWT 签发方的公钥端点。调用 UPMS 的端点还需要 UPMS 实例 |
| 其他 | 部署环境 | 需要真实基础设施 |

`mars.env.dev-profiles` 决定哪些 profile 被当作开发/测试环境——**它影响失败响应是否回带
调试详情**（异常详情、请求 IP、URI、请求头）。上线前确认生产 profile **不在**这个列表里。

## 健康检查与可观测性

每个服务引入框架的 observability starter，管理端点（Actuator）不在业务端口上，而在**管理端口 = 业务端口 + 1000**
上，路径不带服务的 context path：

| 端点（管理端口） | 访问 | 用途 |
| --- | --- | --- |
| `GET /actuator/health` | 匿名 | 聚合状态；匿名只返回状态，带凭据返回组件明细 |
| `GET /actuator/health/readiness` | 匿名 | 就绪探针 |
| `GET /actuator/health/liveness` | 匿名 | 存活探针 |
| `GET /actuator/info` | Basic 认证；暴露面收窄为 `health`、`info` 时由服务自己的安全链处理（见下文） | 应用信息 |
| `GET /actuator/prometheus`、`/actuator/metrics` | Basic 认证 | 指标，带 `application` 标签 |
| `/actuator/loggers`、`/actuator/threaddump`、`/actuator/heapdump` | Basic 认证 | 运行期排查 |

认证账号来自 `MARS_MANAGEMENT_USERNAME` / `MARS_MANAGEMENT_PASSWORD`。缺凭据时，开发 profile 只暴露
`health` 与 `info` 并告警，其他 profile 启动失败；服务没有接入 Spring Security 时（例如网关）只暴露 `health` 与 `info`。
这时管理端点没有 Basic 认证链，`info` 由服务自己的安全链处理：网关没有安全链，匿名可读；sample 与 UPMS 要求 Bearer
令牌；监控面板要求登录。没有认证链时显式配置的暴露清单也不能超出 `health` 与 `info`，否则非开发 profile 启动失败。
暴露清单与这些规则见框架仓 observability starter 的说明，需要进一步收窄时设 `mars.observability.management.exposure`。
业务端口上没有 `/actuator/*`。

UPMS 的 `/actuator/info` 只增加 `nacos.configRevision`，用于观察动态刷新是否生效；
它不暴露 Namespace、服务地址、配置正文或凭据。

**注意聚合状态与探针可能不一致**：健康指示器的自动装配独立于连接的自动装配，
只排除连接是不够的。若 `/actuator/health/readiness` 是 `UP` 而聚合 `/actuator/health`
是 `DOWN`，说明某个健康指示器仍在尝试连接外部组件。

排查时带凭据查看组件明细，不需要重启：

```bash
curl -u "$MARS_MANAGEMENT_USERNAME:$MARS_MANAGEMENT_PASSWORD" http://127.0.0.1:9102/actuator/health
```

**调用链与日志**：服务把调用链以 OTLP over HTTP 导出到 `OTLP_TRACING_ENDPOINT`，网关、服务之间的 Feign 调用与
RocketMQ 消息共用一条 W3C trace。控制台日志是 Elastic Common Schema 的 JSON，每行带 `traceId` 与 `spanId`；
部署环境采集容器 stdout 送到日志后端，按 `traceId` 关联调用链。本机 Grafana 的数据源配置
（[`dev/config/datasources.yaml`](../dev/config/datasources.yaml)）已把日志行的 `traceId` 链到 Jaeger。

**实例监控**：`mars-cloud-monitor` 经 Nacos 发现全部实例，按实例的注册地址与元数据里的 `management.port` 读取各实例的管理端点，
实例状态变化与实例被移除都写成日志通知。它只绑内网地址，不经网关，见该模块 README。

## 端口与 context path

| 服务 | 端口 | context path |
| --- | --- | --- |
| `mars-cloud-gateway` | 8100 | 无（路径原样转发给目标服务） |
| `mars-cloud-auth-service` | 8101 | 无（协议端点位于根路径） |
| `mars-cloud-upms-service` | 8102 | `/upms` |
| `mars-cloud-sample-service` | 8103 | `/sample` |
| `mars-cloud-monitor` | 8190 | 无（只绑内网地址，不经网关） |

每个服务的管理端口是业务端口加 1000（9100、9101、9102、9103、9190），同样可随 `SERVER_PORT` 覆盖而跟着变化。

**监听地址**：每个服务的业务端口与管理端口都只绑定 `SERVER_ADDRESS`，注册到 Nacos 的也是这个地址，
其他服务与网关按注册地址调用它。配置文件只写 `server.address: ${SERVER_ADDRESS:127.0.0.1}`；
管理端口的地址由框架的 observability starter、注册地址由 nacos starter 从它推导，见两个 starter 的说明。

- 不设置时是 `127.0.0.1`：本机开发与验收只监听回环地址，同一台机器上的服务互相可达。
- 部署时必须设置为实例在私网里能被其他实例访问的 IPv4 地址。填通配地址（`0.0.0.0`、`::`）或主机名时框架不推导：
  管理端口绑定全部网卡，注册地址由 Spring Cloud Alibaba 选第一块非回环网卡，不一定是这个实例的私网地址。
- 不填 IPv6 地址：Spring Cloud 拼实例地址时不给 IPv6 地址加方括号，以 IPv6 地址注册拼出的实例地址无效。
- 在容器里运行时填容器在私网里的地址，缺省的回环地址在容器外不可达。
- 注册地址需要与绑定地址不同时，另外设置 `SPRING_CLOUD_NACOS_DISCOVERY_IP` 与
  `SPRING_CLOUD_NACOS_DISCOVERY_PORT`，显式配置优先于推导。监控面板按注册地址与元数据里的 `management.port` 读取管理端点。
  `SPRING_CLOUD_NACOS_DISCOVERY_IP` 设为空值也算显式配置，这时注册地址由 Spring Cloud Alibaba 选网卡。

端口一律可用 `SERVER_PORT` 覆盖。**服务间调用绕过网关**，因此每个服务都要自己完成鉴权，
网关只是第一道——部署时不要假设「流量过了网关就一定是可信的」。

## 接口文档

引入 mvc starter 的服务自带 springdoc：

| 端点 | 内容 |
| --- | --- |
| `<context-path>/v3/api-docs` | OpenAPI 3 JSON |
| `<context-path>/swagger-ui/index.html` | Swagger UI |

**生产环境建议关闭**（`springdoc.swagger-ui.enabled: false`），或只在内网入口暴露。

## 优雅停机

Spring Boot 默认在收到 `SIGTERM` 后停止接收新请求并等待在途请求完成：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

容器编排下请给足 `terminationGracePeriodSeconds`，否则进程会在在途请求完成前被强杀。

## 启动顺序

网关、UPMS 与 sample 都接入 Nacos，启动前必须先启动注册中心（三者对 Nacos 的导入都不是 `optional:`，
注册中心不可用时启动失败）。网关另外要求 Namespace 里已有它的两个限流规则配置（`SENTINEL_GROUP`，Data ID 见上表），
缺少、为空白或写错时启动失败。

**网关与业务服务之间没有顺序要求**：网关先起时，目标服务没有实例的请求返回信封式 503
（`63002`），业务服务上线后网关自动发现，不需要重启。这条由网关的 `verify-e2e.sh`
以真进程按「先网关、后 UPMS」的顺序验证。

sample 到 UPMS 同样只经服务名调用。`mars-cloud-sample-service/verify-feign-e2e.sh` 在隔离端口
启动两个真进程，验证调用成功；随后停止 UPMS，验证 sample 返回 HTTP 503 与自己的错误码 `66104`。

## 上线前检查清单

- [ ] 生产 profile **不在** `mars.env.dev-profiles` 里（否则失败响应会回带调试详情）
- [ ] 数据库、Redis 等连接参数全部来自环境变量，配置文件里没有硬编码
- [ ] Nacos 地址、Namespace 与凭据来自环境变量，配置正文没有明文凭据
- [ ] 网关的两个限流规则配置已按生产阈值写入 Namespace 的 `SENTINEL_GROUP`，网关启动日志里有「Sentinel 规则已从 Nacos 装入」
- [ ] `MARS_MANAGEMENT_USERNAME` / `MARS_MANAGEMENT_PASSWORD` 已配置，管理端口只在内网可达；Swagger 已按需关闭
- [ ] `SERVER_PORT` 与编排/网关配置一致
- [ ] 每个实例都设置了 `SERVER_ADDRESS`，取值是实例的私网 IPv4 地址；从另一个实例能按这个地址连到它的业务端口与管理端口
- [ ] 已配置优雅停机与足够的终止宽限期
- [ ] 失败响应不泄露内部信息：抽查一个错误响应，确认 `result` 为 `null`
