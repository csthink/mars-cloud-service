# mars-cloud-service

[![CI](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml/badge.svg)](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml)

mars-cloud 微服务体系的**可部署应用仓**：网关、认证服务与各业务服务。

本仓只放能独立启动的应用（有主类、有端口、有部署配置）；可复用的库与 starter 都在
配套的框架仓 `mars-cloud-framework` 里。

> 上游框架仓构建成功后会自动触发本仓的 CI，避免「框架改了、服务没跟上」。
> 流水线内容见 [docs/ci.md](docs/ci.md)。

## 快速开始

需要 **JDK 25** 与 Maven。仓内 `.mvn/jvm.config` 会给 Maven 进程带上
`--sun-misc-unsafe-memory-access=allow`（Lombok 在 JDK 24 及以上编译期需要），无需手动设置；
应用进程自己需要的同名参数见 [`docs/deployment.md`](docs/deployment.md) 的「JVM 参数」一节。

本仓依赖框架仓 `mars-cloud-framework`，而框架尚未发布到制品库，
所以**两个仓必须先克隆到同一个父目录下**，本仓才能通过相对路径拿到框架的构建产物：

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

## 服务

| 服务 | 端口 | 错误码区间 | 职责 | 状态 |
| --- | --- | --- | --- | --- |
| `mars-cloud-gateway` | 8100 | `63000–63999` | 南北向唯一入口：路由、鉴权第一道、全局限流。响应式栈 | 规划中 |
| `mars-cloud-auth-service` | 8101 | `64000–64999` | 认证（AuthN）：令牌签发、登录渠道、短信验证码、账号 | 规划中 |
| `mars-cloud-upms-service` | 8102 | `65000–65999` | 授权（AuthZ）：subject / action / resource 决策（PDP） | ✅ 已落地 |
| `mars-cloud-sample-service` | 8103 | `66100–66199` | 框架使用示例：一条命令跑起来的完整接线示范 | ✅ 已落地 |
| `mars-cloud-<biz>-service` | 8104+ | `66000–99999` 内自选 | 业务服务，共用 `business` 区段、各自声明一段 | 规划中 |

各服务逐个加入根聚合 POM 的 `<modules>`，已占用的错误码子区间登记在
[docs/services.md](docs/services.md)。

**想先看看怎么写一个服务**：`mars-cloud-sample-service` 是最小可运行示例，
只依赖一个 starter，无数据库、无 Redis：

```bash
mvn -pl mars-cloud-sample-service -am package
cd mars-cloud-sample-service && ./run-local.sh    # 端口 8103，context path /sample
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
