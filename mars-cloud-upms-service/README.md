# mars-cloud-upms-service

授权（AuthZ）的**决策侧**（PDP）：输入 subject / action / resource，输出 allow 或 deny。

- 入口类：`com.mars.cloud.service.upms.UpmsApplication`
- 端口：`8102`；context path：`/upms`；管理端口 `9102`（管理端点不带 context path）
- 决策端点：`POST /upms/v1/decision`
- 健康检查：管理端口上的 `GET /actuator/health`，匿名可读
- Nacos 配置版本：管理端口上的 `GET /actuator/info`，带管理端点凭据
- 错误码区间：`65000–65999`（框架权威分配表里的 `upms-service` 区段）

## 身份验证

设置 `MARS_SECURITY_ISSUER_URI` 与可选的 `MARS_SECURITY_JWK_SET_URI`。除健康探针外，请求必须携带合法 Bearer 令牌，audience 包含 `mars-cloud-upms-service`；`caller_id` 必须等于已验证的 sub，否则返回 403 / 62006。UPMS 只验证身份，不向自身发起权限查询。

示例的 `AUTH_HEADER_FILE` 指向权限 0600 的本地请求头文件，包含 `Authorization: Bearer <合法令牌>`。令牌不进入 `.env`、Nacos 或版本库。

## 响应契约

控制器显式返回 `UnifyResponse` 信封，并标注 `@IgnoreResponseAnnotation`，
避免信封被二次包装。**deny 是成功的决策，不是错误**：

| 情形 | HTTP | 信封 |
| --- | --- | --- |
| allow | 200 | `success:true` + `result.decision:"allow"` |
| deny | 200 | `success:true` + `result.decision:"deny"`、`result.reason_code` |
| 认证或主体错误 | 401 / 403 | `success:false` + security 数字错误码 |
| 请求或服务态错误 | 非 200 | `success:false` + 文本码（`missing_field` / `malformed_request` / `snapshot_unavailable` …） |

消费方必须先判断 HTTP status 与 `success` 位，再读决策或错误码：
**deny 是有效拒绝；协议失败或服务不可用同样阻止继续执行业务，不返回默认 allow。**

请求体三个字段都是必填且必须规范：`caller_id`、`action`、`resource`；
`resource` 必须是 `<platform>:<capability>` 形式。

```bash
curl --header "@$AUTH_HEADER_FILE" -X POST http://127.0.0.1:8102/upms/v1/decision \
  -H 'Content-Type: application/json' \
  -d '{"caller_id":"local-admin","action":"view","resource":"demo:view:domain:kubernetes-ops"}'
```

## 本地启动

```bash
cp ../.env.example .env # 填写本机 Nacos Namespace 与账号
./run-local.sh          # 显式加载 .env，再以 local profile 启动
```

先启动 Nacos，并在同一 Namespace 下准备：

- `COMMON/shared-common.yaml`
- `DEFAULT_GROUP/mars-cloud-upms-service.yaml`

`local` profile 的授权真相来源仍是**内存快照**，不是外部数据库或 Redis：

- 关掉数据源与 Redis 的自动装配（连同它们的健康指示器）
- 从 Nacos 加载共享配置与应用配置，并注册 `mars-cloud-upms-service`
- 启动后发布一份**开发种子快照**（`mars.upms.local-fixture.enabled`，默认打开），
  种子里 `local-admin` 持有 `demo` 平台的全部能力

想复现「无活动快照 → 所有决策请求返回 503」这条服务态路径，把
`UPMS_LOCAL_FIXTURE_ENABLED=false` 传给进程即可。

## 配置：分层与来源

**原则：配置项进版本库，配置项的「环境相关取值」只从环境变量来。**

| 层 | 位置 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、端口、context path、i18n、错误码区间声明 |
| local profile | `src/main/resources/config/application-local.yml` | ✅ | **仅**行为开关：自动装配排除项、时区、种子快照开关。**刻意不含任何连接信息** |
| Nacos 共享配置 | `COMMON/shared-common.yaml` | ❌ | 跨服务的非敏感动态默认值 |
| Nacos 应用配置 | `DEFAULT_GROUP/mars-cloud-upms-service.yaml` | ❌ | UPMS 的非敏感动态覆盖值 |
| 环境取值 | 环境变量（开发时用 `.env` 承载） | ❌ `.env` 忽略；[`.env.example`](../.env.example) 是模板 | 地址、端口、库名、口令 |

`application-local.yml` 之所以能进版本库，是因为它**不含任何环境相关的取值**——
它对每个人、每台机器都是同一份。反过来，任何带 host / 口令的配置都不该进仓。

### `.env` 怎么用

**Spring Boot 本身不读 `.env`** —— 应用读的是**环境变量**，`.env` 只是承载变量名的便利文件。
是否被自动加载取决于启动方式：

```bash
cd mars-cloud-upms-service

# 方式一：mvn spring-boot:run（run-local.sh 走这条）—— 会自动读取本目录的 .env
cp ../../.env.example .env && ./run-local.sh

# 方式二：java -jar —— 不会自动读，需先导出
cp ../../.env.example .env
set -a && . ./.env && set +a
java --sun-misc-unsafe-memory-access=allow -jar target/mars-cloud-upms-service.jar
```

方式二多出的 `--sun-misc-unsafe-memory-access=allow` 是 JDK 24 及以上运行 nacos-client 3.1.1
所需的 JVM 参数；方式一由框架 BOM 统一给 `spring-boot:run` 配好，容器由 `Dockerfile` 的
`ENTRYPOINT` 带上。原因与各入口的固化位置见
[`docs/deployment.md`](../docs/deployment.md) 的「JVM 参数」一节。

`.env` 不进版本库（见 [`.gitignore`](../.gitignore)）；`.env.example` 只列变量名与说明，可安全提交。
local profile 下必须填写 `NACOS_NAMESPACE_ID`、`NACOS_USERNAME` 与 `NACOS_PASSWORD`。
真实凭据不写入 Nacos 配置正文。

### 动态刷新怎么观察

应用配置可放一个非敏感版本标记：

```yaml
mars:
  upms:
    nacos:
      config-revision: revision-1
```

修改并发布后，带管理端点凭据请求管理端口的 `/actuator/info`，`nacos.configRevision` 应在进程不重启的情况下更新：

```bash
curl -u "$MARS_MANAGEMENT_USERNAME:$MARS_MANAGEMENT_PASSWORD" http://127.0.0.1:9102/actuator/info
```

该端点只暴露版本标记，不暴露 Namespace、地址、配置正文或凭据。

## 依赖边界

- 依赖框架仓的 `mars-cloud-nacos-spring-boot-starter`、`mars-cloud-mvc-spring-boot-starter`、
  `mars-cloud-mysql` 与 `mars-cloud-observability-spring-boot-starter`，**不直接依赖**框架的 `common` 模块
  （信封与错误码契约由 starter 传递进来）
- 声明 `spring-boot-starter-security`：管理端点的 Basic 认证链需要 Spring Boot 的 Web 安全模块
- 与其他服务之间**不加编译期依赖**，只走 HTTP
