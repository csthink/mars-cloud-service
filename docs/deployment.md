# 部署说明

本仓每个模块都是可独立启动的应用。本文说明构建产物、启动方式、配置来源、
健康检查与上线前检查项。**具体服务的行为细节见各自的模块 README。**

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

`run-local.sh` 会加载模块根目录的 `.env`（若存在）并以 local profile 启动。
网关、UPMS 与 sample 都需要其中的 Nacos Namespace 与账号。
sample 与 UPMS 还必须配置 JWT issuer；可显式提供 JWKS 地址，否则通过 issuer 元数据发现。
两服务的 audience 分别为自己的应用名；sample 调用 UPMS 时，访问令牌的 audience 必须同时包含两者。
缺少认证配置时启动失败；健康探针允许匿名访问，业务接口需要合法 Bearer 令牌。
三个服务启动命令中多出的 JVM 参数见下一节。

> **`java -jar` 不会读 `.env`。** Spring Boot 本身没有 `.env` 支持——应用读的是
> **环境变量**；`mvn spring-boot:run`（即 `run-local.sh`）只是恰好会加载模块根目录的
> `.env`。生产环境请把变量真正注入进程（编排平台的 secret、systemd `EnvironmentFile`、
> 容器 env 等），不要指望 `.env` 文件。

## JVM 参数

接入 Nacos 的服务（当前是网关、UPMS 与 sample）在 JDK 24 及以上启动时必须带：

```
--sun-misc-unsafe-memory-access=allow
```

原因：Spring Cloud Alibaba 2025.1 托管的 nacos-client 3.1.1 内部 shade 了 Guava，
后者调用 `sun.misc.Unsafe` 的内存访问方法。JDK 24 起（JEP 498）默认在首次调用时向
标准错误打印一组以 `WARNING: A terminally deprecated method in sun.misc.Unsafe has been called`
开头的弃用警告，后续 JDK 版本会先改为 `debug` 再改为 `deny`。这是 nacos 上游问题
（[nacos#14070](https://github.com/alibaba/nacos/issues/14070)），不是本仓代码调用了 `Unsafe`；
这个参数是 JDK 给出的规避方式，效果是把该次调用当作允许，不再打印警告。

classpath 上带 Netty 平台原生库的服务（当前是网关：Reactor Netty 带来 macOS 的 DNS 解析器与
Linux 的 epoll）还必须带：

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
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、**默认端口**、context path、i18n、错误码区间声明 |
| profile 覆盖 | `src/main/resources/config/application-<profile>.yml` | ✅ | **仅**行为开关（自动装配排除项、时区、功能开关）。**不含任何 host / 端口 / 库名 / 口令** |
| Nacos 动态配置 | 环境 Namespace 下的共享与应用 Data ID | ❌ | 非敏感、需要动态刷新的默认值与应用覆盖值 |
| 环境取值 | 环境变量 | ❌ | 地址、端口、库名、口令 |

「端口」在两处出现，口径是：**默认值进版本库**（让新克隆能直接跑起来），
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
| `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE_ID` | Nacos 地址与环境 Namespace ID |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | Nacos 账号与密码 |
| `MARS_SECURITY_ISSUER_URI` | sample / UPMS 必填的可信 JWT issuer，部署环境使用 HTTPS |
| `MARS_SECURITY_JWK_SET_URI` | 可选的 JWKS 地址，仍校验 issuer；部署环境使用 HTTPS |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_PASSWORD` | 数据源 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD` | Redis |

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

| 端点 | 用途 |
| --- | --- |
| `GET <context-path>/actuator/health` | 存活 / 就绪判定的聚合状态 |
| `GET <context-path>/actuator/health/readiness` | 就绪探针 |
| `GET <context-path>/actuator/health/liveness` | 存活探针 |
| `GET <context-path>/actuator/info` | 应用信息 |
| `GET <context-path>/actuator/prometheus` | 指标（Prometheus 格式，部分服务开放） |

UPMS 的 `/actuator/info` 只增加 `nacos.configRevision`，用于观察动态刷新是否生效；
它不暴露 Namespace、服务地址、配置正文或凭据。

**注意聚合状态与探针可能不一致**：健康指示器的自动装配独立于连接的自动装配，
只排除连接是不够的。若 `/actuator/health/readiness` 是 `UP` 而聚合 `/actuator/health`
是 `DOWN`，说明某个健康指示器仍在尝试连接外部组件。

排查时先看是哪个组件：

```bash
java --sun-misc-unsafe-memory-access=allow -jar mars-cloud-upms-service.jar \
  --management.endpoint.health.show-details=always
```

**上线前建议把 actuator 的暴露面收窄**，只留 health 与 info：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

## 端口与 context path

| 服务 | 端口 | context path |
| --- | --- | --- |
| `mars-cloud-gateway` | 8100 | 无（路径原样转发给目标服务） |
| `mars-cloud-auth-service` | 8101 | （规划中） |
| `mars-cloud-upms-service` | 8102 | `/upms` |
| `mars-cloud-sample-service` | 8103 | `/sample` |

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
注册中心不可用时启动失败）。

**网关与业务服务之间没有顺序要求**：网关先起时，目标服务没有实例的请求返回信封式 503
（`63002`），业务服务上线后网关自动发现，不需要重启。这条由网关的 `verify-e2e.sh`
以真进程按「先网关、后 UPMS」的顺序验证。

sample 到 UPMS 同样只经服务名调用。`mars-cloud-sample-service/verify-feign-e2e.sh` 在隔离端口
启动两个真进程，验证调用成功；随后停止 UPMS，验证 sample 返回 HTTP 503 与自己的错误码 `66104`。

## 上线前检查清单

- [ ] 生产 profile **不在** `mars.env.dev-profiles` 里（否则失败响应会回带调试详情）
- [ ] 数据库、Redis 等连接参数全部来自环境变量，配置文件里没有硬编码
- [ ] Nacos 地址、Namespace 与凭据来自环境变量，配置正文没有明文凭据
- [ ] actuator 暴露面已收窄，Swagger 已按需关闭
- [ ] `SERVER_PORT` 与编排/网关配置一致
- [ ] 已配置优雅停机与足够的终止宽限期
- [ ] 失败响应不泄露内部信息：抽查一个错误响应，确认 `result` 为 `null`
