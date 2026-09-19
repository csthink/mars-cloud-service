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
./run-local.sh          # 等价于 SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

`local` profile 的授权真相来源是**内存快照**，不是外部数据库或 Redis：

- 关掉数据源与 Redis 的自动装配（连同它们的健康指示器），因此无需任何外部依赖即可启动
- 启动后发布一份**开发种子快照**（`mars.upms.local-fixture.enabled`，默认随 local profile 打开），
  种子里 `local-admin` 持有 `opsdeck` 平台的全部能力

需要连接外部基础设施或接入真实快照来源时，覆盖
`SPRING_DATASOURCE_*` / `SPRING_DATA_REDIS_*` 并把 `UPMS_LOCAL_FIXTURE_ENABLED` 设为 `false`；
注册中心/配置中心接入见 `docs/services.md`。

## 依赖边界

- 依赖框架仓的 `mars-cloud-mvc-spring-boot-starter` 与 `mars-cloud-mysql`，**不直接依赖**框架的 `common` 模块
  （信封与错误码契约由 starter 传递进来）
- 与其他服务之间**不加编译期依赖**，只走 HTTP
