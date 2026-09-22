# mars-cloud-gateway

系统外部请求的**统一入口**：把外部请求路由到各业务服务。响应式栈（Spring Cloud Gateway + WebFlux）。

- 入口类：`com.mars.cloud.service.gateway.GatewayApplication`
- 端口：`8100`；管理端口 `9100`；无 context path（路径原样转发给目标服务）
- 健康检查：`GET /actuator/health`
- 错误码区间：`63000–63999`（框架权威分配表里的 `gateway` 区段）

## 路由

路由在 `config/application.yml` 里**显式声明**，目标用服务名走注册中心的客户端负载均衡：

| 路径 | 目标 | 说明 |
| --- | --- | --- |
| `/upms/**` | `lb://mars-cloud-upms-service` | UPMS 自带 context path `/upms`，路径不改写 |
| `/sample/**` | `lb://mars-cloud-sample-service` | 示例服务自带 context path `/sample`，路径不改写 |

刻意**不开** `discovery.locator`：开了以后注册中心里的每个服务都会被自动暴露，
网关就不再是「显式声明的唯一入口」。新增业务服务时在这里加一条路由。

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
| 其他无法归类的异常 | 网关 | 对应状态 | `code` 为 HTTP 状态码本身（如 `"500"`），与业务服务的兜底约定一致 |

`message` 走 i18n（`i18n/error-code*.properties`，按请求的 `Accept-Language` 选择）。
`mars.env.dev-profiles` 命中时 `result` 回带调试详情（`detail` / `ip` / `method` / `uri` / `headers`），
键与业务服务一致；其他环境下 `result` 缺席。

```bash
# UPMS 未启动时经网关访问：
curl -i http://127.0.0.1:8100/upms/actuator/health
# HTTP/1.1 503
# {"success":false,"code":"63002","message":"目标服务当前没有可用实例"}
```

## 本地启动

```bash
cp ../.env.example .env  # 填写本机 Nacos Namespace 与账号
./run-local.sh           # 显式加载 .env，再以 local profile 启动
```

先启动 Nacos，并在同一 Namespace 下准备：

- `COMMON/shared-common.yaml`
- `DEFAULT_GROUP/mars-cloud-gateway.yaml`

两条都必须存在（网关不接受 `optional:` 导入）。应用配置里可以只放一个非敏感的版本标记：

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

```bash
mvn -f .. package                 # 网关与 UPMS 都需要 package
./verify-e2e.sh                   # 需要本机 Nacos 与本目录的 .env
```

## 配置：分层与来源

**原则：配置项进版本库，配置项的「环境相关取值」只从环境变量来。**

| 层 | 位置 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、端口、路由表、i18n、开发环境 profile 列表 |
| local profile | `src/main/resources/config/application-local.yml` | ✅ | **仅**行为开关（时区）。**刻意不含任何连接信息** |
| Nacos 共享配置 | `COMMON/shared-common.yaml` | ❌ | 跨服务的非敏感动态默认值 |
| Nacos 应用配置 | `DEFAULT_GROUP/mars-cloud-gateway.yaml` | ❌ | 网关的非敏感动态覆盖值 |
| 环境取值 | 环境变量（开发时用 `.env` 承载） | ❌ `.env` 忽略；[`.env.example`](../.env.example) 是模板 | 地址、Namespace、账号 |

`application-local.yml` 能进版本库，是因为它**不含任何环境相关的取值**；这条由 `LocalConfigHygieneTest` 守护。

## 依赖边界

- 直接依赖框架仓的 `mars-cloud-common`（信封与错误码契约）与 `mars-cloud-nacos-spring-boot-starter`；
  **不引入** Servlet 栈的 `mars-cloud-mvc-spring-boot-starter`——它的统一响应、异常处理与错误码校验都是 Servlet 实现，
  网关在 `web` 包里为响应式栈单独实现了同一契约
- 错误码区间的启动期校验器也在 mvc starter 里，网关引不了，改由 `GatewayErrorCodeTest` 守住区间与 i18n 完整性
- `lb://` 路由需要 `spring-cloud-starter-loadbalancer`（nacos-discovery starter 不传递它）；实例缓存用 Caffeine
- 与其他服务之间**不加编译期依赖**，只走 HTTP
