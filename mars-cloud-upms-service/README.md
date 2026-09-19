# mars-cloud-upms-service

授权（AuthZ）的**决策侧**（PDP）：输入 subject / action / resource，输出 allow 或 deny。

- 入口类：`com.mars.cloud.service.upms.UpmsApplication`
- 端口：`8102`；context path：`/upms`
- 决策端点：`POST /upms/v1/decision`
- 健康检查：`GET /upms/actuator/health`
- 错误码区间：`65000–65999`（框架权威分配表里的 `upms-service` 区段）

## 响应契约

控制器显式返回 `UnifyResponse` 信封，并标注 `@IgnoreResponseAnnotation`，
避免信封被二次包装。**deny 是成功的决策，不是错误**：

| 情形 | HTTP | 信封 |
| --- | --- | --- |
| allow | 200 | `success:true` + `result.decision:"allow"` |
| deny | 200 | `success:true` + `result.decision:"deny"`、`result.reason_code` |
| 请求或服务态错误 | 非 200 | `success:false` + 文本码（`missing_field` / `malformed_request` / `snapshot_unavailable` …） |

消费方必须先判断 HTTP status 与 `success` 位，再读决策或错误码：
**deny 不触发 fallback，只有服务态错误（如 `snapshot_unavailable`）才进入 fallback。**

请求体三个字段都是必填且必须规范：`caller_id`、`action`、`resource`；
`resource` 必须是 `<platform>:<capability>` 形式。

```bash
curl -X POST http://127.0.0.1:8102/upms/v1/decision \
  -H 'Content-Type: application/json' \
  -d '{"caller_id":"local-admin","action":"view","resource":"opsdeck:view:domain:kubernetes-ops"}'
```

## 本地启动

```bash
./run-local.sh          # 显式加载 .env（若有），再以 local profile 启动
```

`local` profile 的授权真相来源是**内存快照**，不是外部数据库或 Redis：

- 关掉数据源与 Redis 的自动装配（连同它们的健康指示器），因此**无需任何外部依赖**即可启动
- 启动后发布一份**开发种子快照**（`mars.upms.local-fixture.enabled`，默认打开），
  种子里 `local-admin` 持有 `opsdeck` 平台的全部能力

想复现「无活动快照 → 所有决策请求返回 503」这条服务态路径，把
`UPMS_LOCAL_FIXTURE_ENABLED=false` 传给进程即可。

## 配置：分层与来源

**原则：配置项进版本库，配置项的「环境相关取值」只从环境变量来。**

| 层 | 位置 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `src/main/resources/config/application.yml` | ✅ | 应用名、端口、context path、i18n、错误码区间声明 |
| local profile | `src/main/resources/config/application-local.yml` | ✅ | **仅**行为开关：自动装配排除项、时区、种子快照开关。**刻意不含任何连接信息** |
| 环境取值 | 环境变量（开发时用 `.env` 承载） | ❌ `.env` 忽略；[`.env.example`](../../.env.example) 是模板 | 地址、端口、库名、口令 |

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
java -jar target/mars-cloud-upms-service.jar
```

`.env` 不进版本库（见 [`.gitignore`](../../.gitignore)）；`.env.example` 只列变量名与说明，可安全提交。
local profile 下**不填任何值也能启动**——连接参数只有在切到别的 profile 时才需要。

## 依赖边界

- 依赖框架仓的 `mars-cloud-mvc-spring-boot-starter` 与 `mars-cloud-mysql`，**不直接依赖**框架的 `common` 模块
  （信封与错误码契约由 starter 传递进来）
- 与其他服务之间**不加编译期依赖**，只走 HTTP
