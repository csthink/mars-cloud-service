# mars-cloud-service

[![CI](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml/badge.svg)](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml)

mars-cloud 微服务体系的**可部署应用仓**：网关、认证服务与各业务服务。

本仓只放能独立启动的应用（有主类、有端口、有部署配置）；可复用的库与 starter 都在
配套的框架仓 `mars-cloud-framework` 里。

> 上游框架仓 main 的 push 构建成功后会自动触发本仓的 CI，避免「框架改了、服务没跟上」。
> 流水线内容见 [docs/ci.md](docs/ci.md)。

## 快速开始

需要 **JDK 25** 与 Maven。仓内 `.mvn/jvm.config` 会给 Maven 进程带上
`--sun-misc-unsafe-memory-access=allow`（Lombok 在 JDK 24 及以上编译期需要），无需手动设置；
应用进程自己需要的同名参数见 [`docs/deployment.md`](docs/deployment.md) 的「JVM 参数」一节。
测试 JVM 由框架 BOM 统一配置为以 `-javaagent` 预加载 mockito-core，因此**有测试的模块必须依赖
`spring-boot-starter-test`**（本仓三个模块都已满足）；缺了它测试 JVM 起不来，报错里会显示未解析的
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

sample 与 UPMS 启动前还需设置 `MARS_SECURITY_ISSUER_URI`，可选设置 `MARS_SECURITY_JWK_SET_URI`。两服务要求合法 Bearer 令牌，令牌 audience 分别包含服务名；sample 调 UPMS 时需要同时包含两者。健康探针仍允许匿名访问。凭据不写入仓库或 Nacos 配置正文。

`./verify-security-e2e.sh` 通过测试 classpath 启动临时签发器，验证正常打包 jar 的认证与权限行为，并在结束时清理临时令牌。原有三份验收脚本也自动使用同一辅助流程。

## 服务

| 服务 | 端口 | 错误码区间 | 职责 | 状态 |
| --- | --- | --- | --- | --- |
| `mars-cloud-gateway` | 8100 | `63000–63999` | 系统外部请求的统一入口：路由到各业务服务，错误响应与业务服务同一种信封。响应式栈 | ✅ 已落地（鉴权第一道与全局限流待后续接入） |
| `mars-cloud-auth-service` | 8101 | `64000–64999` | 认证（AuthN）：令牌签发、登录渠道、短信验证码、账号 | 规划中 |
| `mars-cloud-upms-service` | 8102 | `65000–65999` | 授权（AuthZ）：subject / action / resource 决策（PDP） | ✅ 已落地 |
| `mars-cloud-sample-service` | 8103 | `66100–66199` | 框架使用示例：一条命令跑起来的完整接线示范 | ✅ 已落地 |
| `mars-cloud-<biz>-service` | 8104+ | `66000–99999` 内自选 | 业务服务，共用 `business` 区段、各自声明一段 | 规划中 |

各服务逐个加入根聚合 POM 的 `<modules>`，已占用的错误码子区间登记在
[docs/services.md](docs/services.md)。

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
