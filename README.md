# mars-cloud-service

[![CI](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml/badge.svg)](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml)

mars-cloud 微服务体系的**可部署应用仓**：网关、认证服务与各业务服务。

本仓只放能独立启动的应用（有主类、有端口、有部署配置）；可复用的库与 starter 都在
配套的框架仓 `mars-cloud-framework` 里。

> 上游框架仓 main 的 push 构建成功后会自动触发本仓的 CI，避免「框架改了、服务没跟上」。
> 流水线内容见 [docs/ci.md](docs/ci.md)。

## 快速开始

使用 IntelliJ IDEA 本地调试时，请按 [IDEA 本地运行指南](docs/idea-local-development.md) 完成框架安装、中间件准备、`.env` 加载、测试签发器与三个服务的启动及健康检查。自动验收与浏览器里的面板、调用链、日志检查见 [本地运行与验收](docs/local-acceptance.md)。

需要 **JDK 25** 与 Maven。仓内 `.mvn/jvm.config` 会给 Maven 进程带上
`--sun-misc-unsafe-memory-access=allow`（Lombok 在 JDK 24 及以上编译期需要），无需手动设置；
应用进程自己需要的同名参数见 [`docs/deployment.md`](docs/deployment.md) 的「JVM 参数」一节。
测试 JVM 由框架 BOM 统一配置为以 `-javaagent` 预加载 mockito-core，因此**有测试的模块必须依赖
`spring-boot-starter-test`**（本仓五个模块都已满足）；缺了它测试 JVM 起不来，报错里会显示未解析的
`${org.mockito:mockito-core:jar}`。

本仓依赖框架仓 `mars-cloud-framework`，而框架尚未发布到制品库，
所以需要先构建确定版本的框架源码。下面的开发示例把两个仓克隆到同一个父目录；Maven 通过本地仓解析依赖。正式验证用 `tools/verify.sh` 固定两仓 SHA，不依赖旧 SNAPSHOT，见 [docs/ci.md](docs/ci.md)：

```
<任意父目录>/
├── mars-cloud-framework/     # 框架仓（依赖来源）
└── mars-cloud-service/       # 本仓
```

```bash
# 若还没有框架仓，先克隆到本仓的平级目录
cd .. && git clone https://github.com/csthink/mars-cloud-framework

# 回到本仓，装 pre-commit hook（幂等，只需一次）
cd mars-cloud-service && ./tools/install-hooks.sh

# 先构建并安装框架仓到本地 Maven 仓库（框架改动后需重装）
mvn -f ../mars-cloud-framework/pom.xml clean install

# 再构建本仓
mvn clean install
```

单服务构建：

```bash
mvn -pl mars-cloud-upms-service -am test
```

gateway、sample 与 UPMS 启动前还需设置 `MARS_SECURITY_ISSUER_URI`，可选设置 `MARS_SECURITY_JWK_SET_URI`。网关与两服务分别验证 Bearer 令牌的 audience；经网关访问受保护接口时，令牌还须包含 `mars-cloud-gateway`。sample 调 UPMS 时需要同时包含两者的 audience。健康探针仍允许匿名访问。凭据不写入仓库或 Nacos 配置正文。

`./verify-security-e2e.sh` 通过测试 classpath 启动临时签发器，验证正常打包 jar 的认证与权限行为，并在结束时清理临时令牌。原有三份验收脚本也自动使用同一辅助流程。

`./verify-observability-e2e.sh` 启动网关、UPMS、sample 与监控面板，经网关发起一次到 sample 再到 UPMS 的请求，核对：
追踪后端里这次请求是一条链、三个进程各有 span；三个进程的结构化日志带同一个 `traceId`；日志推送到日志后端后按
`traceId` 能查回三个服务；Grafana 的日志关联字段能从日志行链到这条调用链（用临时容器核对，需要 docker）；监控面板
发现全部实例，并在实例下线时写出日志通知。端口、管理端点与面板的凭据、追踪与日志后端地址从 sample 的 `.env` 读取，
变量见 [`.env.example`](.env.example)。

认证服务的 HTTPS、本地随机测试登录、六客户端配置与独立验收见 [模块 README](mars-cloud-auth-service/README.md)。它的标准协议端点不使用业务响应信封。

本地中间件可由 [dev/README.md](dev/README.md) 的统一入口启动、初始化与验证。

## 服务

| 服务 | 端口 | 错误码区间 | 职责 | 状态 |
| --- | --- | --- | --- | --- |
| `mars-cloud-gateway` | 8100 | `63000–63999` | 系统外部请求的统一入口：路由到各业务服务，验证受保护请求的 JWT，错误响应与业务服务同一种信封。响应式栈 | ✅ 已接入 JWT 验证 |
| `mars-cloud-auth-service` | 8101 | `64000–64999` | 认证（AuthN）：授权码签发、公钥、账号与会话持久化 | ✅ 已提供协议与本地验证，生产短信登录待接入 |
| `mars-cloud-upms-service` | 8102 | `65000–65999` | 授权（AuthZ）：subject / action / resource 决策（PDP） | ✅ 已落地 |
| `mars-cloud-sample-service` | 8103 | `66100–66199` | 框架使用示例：一条命令跑起来的完整接线示范 | ✅ 已落地 |
| `mars-cloud-<biz>-service` | 8104–8179 | `66000–99999` 内自选 | 业务服务，共用 `business` 区段、各自声明一段 | 规划中 |
| `mars-cloud-monitor` | 8190 | 无 | 运行中实例的监控面板（Spring Boot Admin），经 Nacos 发现实例，只绑内网地址、不经网关 | ✅ 已落地 |

各服务逐个加入根聚合 POM 的 `<modules>`，已占用的错误码子区间登记在
[docs/services.md](docs/services.md)。

每个服务的管理端点（Actuator）在「业务端口加 1000」的管理端口上：`health` 匿名可读，其余端点要 Basic 认证；网关也按此规则保护管理端口。
链路追踪、结构化日志与管理端点的约定由框架仓的 observability starter 统一给出。

**想先看看怎么写一个服务**：`mars-cloud-sample-service` 是最小可运行示例，
无数据库、无 Redis，并演示经 Nacos 服务名调用 UPMS：

```bash
mvn -pl mars-cloud-sample-service -am package
cd mars-cloud-sample-service && ./run-local.sh    # 端口 8103，context path /sample
```

sample 与 UPMS 的真进程调用验收使用隔离端口 8203 / 8202：

```bash
cd mars-cloud-sample-service && ./verify-feign-e2e.sh
```

**想从入口走一遍**：先起网关、再起 UPMS，经网关访问 UPMS（需要本机 Nacos，见各模块 README）：

```bash
mvn package
cd mars-cloud-gateway && ./verify-e2e.sh          # 真进程验收，含「网关先起、UPMS 后起」的启动顺序
```

## 文档

- [IDEA 本地运行指南](docs/idea-local-development.md)：从安装 framework 到启动 gateway、sample、UPMS 与可选的监控面板，含启动配置与常见问题
- [本地运行与验收](docs/local-acceptance.md)：自动验收脚本、手工启动四个部署物、命令行检查，以及在浏览器里查看监控面板、Jaeger 与 Grafana
- [docs/services.md](docs/services.md) —— 服务清单、职责边界与错误码子区间占用表
- [docs/deployment.md](docs/deployment.md) —— 构建产物、启动方式、配置来源、健康检查、上线前检查清单
- [docs/ci.md](docs/ci.md) —— 流水线内容与跨仓触发
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 参与本仓开发：硬性约束、服务边界、新增服务的步骤
- 各服务自己的 `README.md` —— 接口契约与本地启动

## 约定

- **服务之间不加编译期依赖**，只通过 HTTP 调用交互。这样将来拆库拆服务是零成本。
- 对外响应统一使用框架提供的响应信封，错误码必须落在本服务声明的区间内。
- 配置分层：配置项进版本库，**环境相关的取值只从环境变量来**——
  profile 文件里不放任何 host / 端口 / 库名 / 口令。见 [docs/deployment.md](docs/deployment.md)。

## 许可

内部项目，暂未公开发布 artifact。
