# mars-cloud-auth-service

提供 Authorization Code + PKCE S256、OpenID Connect 发现、公钥发布、持久化授权和 Redis 登录会话。业务端口默认 8101，HTTP 管理端口为 9101。没有 context path。

短信验证码登录已接入现有授权码流程。当前只有 local / test 的受保护 mock 发送方；正式短信发送方尚未接入，生产发码请求返回 503，不建立短信挑战。默认口令表单只供显式启用的 local / test 验证；生产没有默认用户。公共客户端续期和定制登录页尚未提供；设备上限与撤销见「设备会话」。

## 端点与客户端

| 端点 | 行为 |
| --- | --- |
| `/.well-known/openid-configuration` | 标准发现文档 |
| `/.well-known/jwks.json` | 当前与退役不足 24 小时的公钥 |
| `/oauth2/authorize`、`/oauth2/token` | 授权码签发与一次兑换，要求 S256 |
| `/userinfo` | Bearer 访问，只返回 `sub` |
| `/connect/logout` | 标准 RP-Initiated Logout，严格校验登出后地址 |
| `/auth/v1/me` | Bearer 身份验证与统一业务响应；Cookie 不替代访问令牌 |
| `/login/sms/send`、`/login/sms/authenticate` | 同源会话内发码与短信登录；成功后继续原授权请求 |
| `/login/captcha` | 按 SEND 或 VERIFY 用途生成一次性 PNG 图形验证码 |
| 管理端口 `/actuator/health/readiness` | 同时检查应用状态、MySQL 和 Redis |

标准协议端点使用协议自己的响应格式。管理端点沿用框架约定，`health` 匿名，其余 Basic。管理账号不能用于用户登录。

六个正式公共客户端均使用 `none` 认证方法，没有客户端密钥。五个浏览器客户端只有 `authorization_code`；`csthink-assistant` 另登记 `refresh_token` 及 30 天轮换策略，公共客户端续期仍需后续接入。浏览器不签发刷新令牌。原生授权每次要求同意，不读取或保存历史同意。

| 客户端 | 访问令牌 audience |
| --- | --- |
| `portal` | `mars-cloud-gateway`、`mars-cloud-auth-service`、`mars-cloud-product-service`、`mars-cloud-order-service`、`mars-cloud-notice-service`、`mars-cloud-support-service` |
| `console` | `mars-cloud-gateway`、`mars-cloud-auth-service`、`mars-cloud-product-service`、`mars-cloud-order-service`、`mars-cloud-notice-service`、`mars-cloud-upms-service` |
| `flippo-book`、`wonder-lab`、`english-word-card`、`csthink-assistant` | `mars-cloud-gateway`、`mars-cloud-auth-service`、`mars-cloud-product-service`、`mars-cloud-notice-service` |

访问令牌有效期 15 分钟，只包含 `iss/sub/aud/exp/iat/jti/client_id/sid/tenant_id/scope`，`tenant_id=default`。浏览器访问令牌的 `sid` 对应登录后 Spring Session 标识；原生访问令牌的 `sid` 是该授权记录的标识，与浏览器会话不同。ID token 的 audience 是客户端，`sid` 使用 Spring Security 的浏览器会话哈希，供协议登出关联。令牌不含手机号、头像或权限清单。

## 设备会话

一个账号最多 3 台设备同时在线。一个浏览器登录会话算一台（门户与各产品站点在同一浏览器共用它，只算一台）；原生客户端的一条授权记录算一台。每台设备在 `sys_session` 有一行：浏览器行在登录成功后写入，签发授权码时更新 `last_seen_at`；原生行在签发授权码时写入，授权记录保存访问令牌（兑换或续期）时更新 `last_seen_at`。

每次准入（登录或原生授权）时，服务先核对已有各行的后备存储：登记超过 60 秒、且浏览器行的 Redis 会话已过期或原生行的授权记录已不存在或令牌全部失效的行，标记 `revoke_reason=EXPIRED`，不占名额；60 秒内登记的行按存在计。仍在用的设备满 3 台时，`last_seen_at` 最早的一台被踢出（发起原生授权的浏览器先记为最近使用，不会被自己的授权踢出），`sys_login_log` 写一行 `SESSION_REVOKED`，`details` 记录原因、被踢会话与触发方类型，不含令牌。

撤销在一个数据库事务内完成：删除原生授权记录（刷新令牌随之失效）、标记 `sys_session` 行、删除浏览器 Spring Session、向 Redis 写入 `mars:auth:revoked:sid:<sid>`（值 `1`，保留 15 分钟，覆盖访问令牌寿命）。写入 Redis 失败时事务回滚，撤销视为未完成。网关在验签后查询该键，被撤销会话的访问令牌在 5 秒内得到 `62007/401`；浏览器站点据此回到登录页，客户端据此清除本地凭据。认证服务与网关必须使用同一个 Redis 库。

撤销后的行保留 30 天，由 `DeviceSessionService.purgeRevoked` 幂等删除（`sys_session.revoked_at` 有索引）；每日调度在任务调度组件接入后配置，当前需要手动或由后续任务触发。禁用账号与管理端踢出复用同一撤销方法，管理接口尚未提供。

## 部署配置

必需输入见根目录 [`.env.example`](../.env.example)。使用受保护环境文件加载：

- MySQL 的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`；连接 URL 带 `preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。
- Redis 的 host、port、database、password，以及 Nacos 和管理凭据。
- `MARS_AUTH_ISSUER`：明确的 HTTPS issuer。正式部署使用 DNS 名；本地只允许与业务端口一致的 `https://127.0.0.1:<port>`。容器不自动采用转发头；仅通过下述可信网关地址段接收规范头。
- `MARS_AUTH_TRUSTED_GATEWAY_CIDRS`：可信网关实例的 IP 地址段，多个 CIDR 用逗号分隔。正式环境必填；local / test 的直连验证可留空。只有 TCP 对端命中这些地址段时，服务才校验并采用网关生成的单一 `X-Forwarded-For`、`X-Forwarded-Host`、`X-Forwarded-Proto`、`X-Forwarded-Port`；缺失或非法返回 400。其他直连请求忽略转发头。管理端口不受该规则影响。
- `MARS_AUTH_JWK_ENCRYPTION_KEY`：随机 32 字节的 Base64 编码；`MARS_AUTH_JWK_ENCRYPTION_KEY_ID`：密钥版本。两项需持久保管，重启时保持一致。RSA 私钥经 AES-GCM 加密保存，数据库仅发布公钥。
- `SPRING_CONFIG_ADDITIONAL_LOCATION`：受控客户端 YAML 文件，包含六个客户端各自的 `redirect-uris`、`post-logout-redirect-uris`。正式浏览器必须精确 HTTPS 地址，原生必须为带明确端口的字面 `127.0.0.1` 地址。
- `MARS_AUTH_SMS_HMAC_KEY`：启用发码前配置独立随机 32 字节的 Base64 编码密钥，各实例使用相同值；不与 JWK 密钥共用。
- `MARS_AUTH_SMS_DAILY_BUDGET`：UTC 自然日发码上限，默认 2000，必须为正数；耗尽后持续停发，运维核对后使用 `scripts/resume_sms_budget.py` 恢复。
- `MARS_AUTH_SMS_CAPTCHA_ERROR_THRESHOLD`：同号及当前会话的错误验证码次数阈值，默认 3，范围为 1 到 5。
- `MARS_AUTH_SMS_MOCK_ENABLED`、`MARS_AUTH_SMS_MOCK_OUTBOX`：仅供 local / test 显式启用；收件箱必须位于受保护的 `dev/.local/` 目录。

客户端配置示例片段：

```yaml
mars:
  auth:
    clients:
      portal:
        redirect-uris: ["https://portal.example/callback"]
        post-logout-redirect-uris: ["https://portal.example/logged-out"]
```

其余五个客户端也必须提供，缺失则启动失败。示例域名用于验证，正式部署按实际接入地址填写。首次迁移保存登记结果，以后修改配置文件不会覆盖数据库；客户端变化应通过新的版本迁移进行。

Flyway 管理九张应用表与自己的历史表，不启用 `clean` 或自动 baseline。使用空库或已有匹配迁移历史的库；未知非空库需要另行处理，不能删除原表来绕过检查。已执行迁移不可改写。

账号绑定按 `(channel, identity_scope, subject)` 完整三元组唯一，区分大小写、重音及 subject 尾部空格；同一账号可有多个渠道绑定。当前账号写入只接受已验证的规范化 E.164 手机号，使用 `SMS/e164`，账号、绑定和审计在同一事务提交。短信登录仅接受 LOGIN 用途；手机号换绑接口尚未提供。调用方提供的渠道资料不能直接作为认证结果。

短信挑战有效期五分钟，同号同用途的新挑战覆盖旧挑战；验证码最多校验五次，成功后原子删除。发码按同号一分钟一次、滚动 24 小时十次，以及来源 IP 滚动一小时十次、滚动 24 小时五十次限制。达到图形验证码阈值时，先验证图形答案再预留发码额度。来源 IP 对可信网关连接取已校验的 `X-Forwarded-For`；其他直连请求取 TCP 对端地址。

日预算暂停后，运维先核对当前 UTC 日期的发送计数与配置上限，再在受保护环境执行恢复命令。恢复操作会先写入请求记录，再原子检查计数并清除暂停标记；计数仍达到上限时拒绝恢复。`--audit-file` 的上级目录须为 `0700`，文件为 `0600`，操作原因不要包含手机号或验证码。

```bash
set -a; . mars-cloud-auth-service/.env; set +a
python3 mars-cloud-auth-service/scripts/resume_sms_budget.py \
  --operator operator-id --reason 'budget reviewed' \
  --audit-file dev/.local/auth-local/sms-budget-audit.jsonl
```

Redis 使用有主体索引的 30 天滑动会话。Cookie 为 `__Host-mars-session`、Secure、HttpOnly、Path=/、SameSite=Lax，不设 Domain。登录更换会话标识，业务 Bearer 请求不修改登录会话。授权码兑换在 MySQL 行锁事务内完成；这保证一次消费，不代表已经完成生产吞吐测试。

## 本地启动

先完成 [中间件准备](../dev/README.md)，准备空的认证数据库、同一环境的 Redis 数据库号，以及非空 Nacos `mars-cloud-auth-service.yaml`。把模块 `.env` 中的连接参数与管理凭据填好，权限设为 `0600`。已有环境生成工具可能重写 `.env`，重写后需重新合入认证私有输入。

从仓库根目录生成一次性本地证书、随机测试账号、加密根密钥和八客户端示例配置：

```bash
python3 mars-cloud-auth-service/scripts/prepare_local.py --port 8101
```

生成文件位于 ignored `dev/.local/auth-local/`，证书有效期七天；不会安装系统信任。把 `local.env` 的变量合入模块 `.env`，保留原有数据库、Redis、Nacos、管理凭据及 Maven 隔离参数。短信 mock 的验证码只写入该目录的 `sms-outbox.txt`。后续复用这些文件，不重新生成已使用的密钥。

```bash
mvn -pl mars-cloud-auth-service package
cd mars-cloud-auth-service
./run-local.sh
```

本地必须启用 HTTPS，并只为测试客户端信任该 CA。浏览器验证使用独立 profile，只允许本次证书公钥；不要关闭全局证书校验。生产配置中测试客户端、测试凭据或测试登录开关任一出现都会拒绝启动。

## 自动验证

`mvn -pl mars-cloud-auth-service test` 不依赖中间件。真实验收需要额外准备三套应用专用数据库，库名含 `_verify_`，账号仅有各自数据库权限。主验证库允许八客户端与随机测试账号；正式配置验证库只有六客户端；非空库探针建立并保留自己的 `preexisting_probe` 表，用来验证 Flyway 拒绝未知库。验收不执行清库。

模块 `.env` 还需设置：

| 变量 | 内容 |
| --- | --- |
| `AUTH_VERIFY_DATABASE` | 主验证库完整名称，与 JDBC URL 一致 |
| `AUTH_VERIFY_CA` | 本次 HTTPS CA 文件 |
| `AUTH_PRODUCTION_ENV_FILE` | 正式配置验证库的受保护环境文件 |
| `AUTH_NONEMPTY_ENV_FILE` | 非空库验证的受保护环境文件 |

后两份文件各含 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`AUTH_VERIFY_DATABASE`，权限均为 `0600`。使用生成器的示例回跳，默认 `http://127.0.0.1:8309/callback`；标准验收固定使用该回跳。主业务端口、其管理端口、业务端口加 10 及其管理端口必须空闲。

```bash
./mars-cloud-auth-service/verify-e2e.sh
```

脚本自行启动和停止打包进程，使用独立 jar 副本，验证短信登录、同源与 CSRF 拒绝、图形 PNG、错误阈值、发码频控、预算暂停及恢复、登录审计、会话更换、PKCE、六客户端 audience、原生同意与取消、并发兑换、MySQL 约束及事务、Redis 主体索引、设备上限的并发准入、撤销副作用与回滚、失效判定、保留期、风控计数有效期、重启、设备踢出后的登录态、协议登出、正式配置和拒绝启动路径；日志只允许已说明的 MySQL/Flyway 版本提示及刻意触发的授权撤销提示。退出码非零表示失败，完整结果保留在 ignored `dev/.local/auth-acceptance-*`。

被踢出设备经网关得到 `62007/401` 的核对需要网关进程，由 `verify-session-e2e.sh` 完成：它用打包后的认证服务与网关（网关以可信代理数量 1、对端 `127.0.0.1/32` 启动，经 Nacos 发现认证服务），运行 `scripts/verify_devices.py --gateway`，核对第 4 台设备登录后被踢设备的令牌在 5 秒内被网关拒绝、其余设备照常通过、同一浏览器的门户与产品站只算一台。运行前须打包两个模块，两份模块 `.env` 齐全，并设置 `SESSION_E2E_CA_FILE`（本次 HTTPS CA）、`SESSION_E2E_TRUST_STORE`（含该 CA 的 PKCS12 信任库）与 `SESSION_E2E_TRUST_PASSWORD`。

```bash
SESSION_E2E_CA_FILE=... SESSION_E2E_TRUST_STORE=... SESSION_E2E_TRUST_PASSWORD=... ./mars-cloud-auth-service/verify-session-e2e.sh
```

浏览器验收可在服务已启动时运行 `scripts/browser_check.py`，通过 `--base`、`--ca`、`--output` 指定本地 issuer、CA 和受保护输出文件；在独立浏览器打开输出文件中的授权 URL，登录并同意后，回跳接收器验证 state 与兑换结果。它只监听回环地址，不打印令牌，不替代自动验收。
