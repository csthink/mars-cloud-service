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
| `mars-cloud-upms-service` | `java -jar target/mars-cloud-upms-service.jar` | `cd mars-cloud-upms-service && ./run-local.sh` |
| `mars-cloud-sample-service` | `java -jar target/mars-cloud-sample-service.jar` | `cd mars-cloud-sample-service && ./run-local.sh` |

`run-local.sh` 会加载模块根目录的 `.env`（若存在）并以 local profile 启动。

> **`java -jar` 不会读 `.env`。** Spring Boot 本身没有 `.env` 支持——应用读的是
> **环境变量**；`mvn spring-boot:run`（即 `run-local.sh`）只是恰好会加载模块根目录的
> `.env`。生产环境请把变量真正注入进程（编排平台的 secret、systemd `EnvironmentFile`、
> 容器 env 等），不要指望 `.env` 文件。

## 配置来源与优先级

**配置项进版本库，配置项的环境相关取值只从环境变量来。**

| 层 | 位置 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、**默认端口**、context path、i18n、错误码区间声明 |
| profile 覆盖 | `src/main/resources/config/application-<profile>.yml` | ✅ | **仅**行为开关（自动装配排除项、时区、功能开关）。**不含任何 host / 端口 / 库名 / 口令** |
| 环境取值 | 环境变量 | ❌ | 地址、端口、库名、口令 |

「端口」在两处出现，口径是：**默认值进版本库**（让新克隆能直接跑起来），
**部署时的实际取值必须由环境变量覆盖**。其余连接类参数则连默认值都不进版本库。

`profile` 覆盖文件之所以能进版本库，是因为它对每个人、每台机器都是同一份。
反过来说：**任何带 host 或口令的配置都不该进仓**。这条约定由各服务的
`LocalConfigHygieneTest` 守护——谁把连接信息写回配置文件，测试就红。

覆盖优先级（后者覆盖前者）：`application.yml` → `application-<profile>.yml` → 环境变量。

## 环境变量

变量名与说明见仓根 [`.env.example`](../.env.example)；**不要提交 `.env`**（已在 `.gitignore`）。
常用项：

| 变量 | 作用 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 激活的 profile，默认 `local` |
| `SERVER_PORT` | 服务端口，覆盖配置文件里的值 |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_PASSWORD` | 数据源 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD` | Redis |

## Profile 语义

| profile | 用途 | 外部依赖 |
| --- | --- | --- |
| `local` | 本机开发 | **无**。各服务在 local 下关掉数据源与 Redis 的自动装配，克隆下来直接能起 |
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

**注意聚合状态与探针可能不一致**：健康指示器的自动装配独立于连接的自动装配，
只排除连接是不够的。若 `/actuator/health/readiness` 是 `UP` 而聚合 `/actuator/health`
是 `DOWN`，说明某个健康指示器仍在尝试连接外部组件。

排查时先看是哪个组件：

```bash
java -jar mars-cloud-upms-service.jar --management.endpoint.health.show-details=always
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
| `mars-cloud-gateway` | 8100 | （规划中） |
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

后续引入注册中心与网关后，**先起注册中心，再起网关，最后起业务服务**。
网关先于注册中心启动时服务发现可能失败——这一条已列入网关相关工作项的验收条件，
不是「理论上可能」。

## 上线前检查清单

- [ ] 生产 profile **不在** `mars.env.dev-profiles` 里（否则失败响应会回带调试详情）
- [ ] 数据库、Redis 等连接参数全部来自环境变量，配置文件里没有硬编码
- [ ] actuator 暴露面已收窄，Swagger 已按需关闭
- [ ] `SERVER_PORT` 与编排/网关配置一致
- [ ] 已配置优雅停机与足够的终止宽限期
- [ ] 失败响应不泄露内部信息：抽查一个错误响应，确认 `result` 为 `null`
