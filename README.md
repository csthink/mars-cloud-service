# mars-cloud-service

[![CI](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml/badge.svg)](https://github.com/csthink/mars-cloud-service/actions/workflows/ci.yml)

mars-cloud 微服务体系的**可部署应用仓**：网关、认证服务与各业务服务。

本仓只放能独立启动的应用（有主类、有端口、有部署配置）；可复用的库与 starter 都在
配套的框架仓 `mars-cloud-framework` 里。

> 上游框架仓构建成功后会自动触发本仓的 CI，避免「框架改了、服务没跟上」。
> 流水线内容见 [docs/ci.md](docs/ci.md)。

## 快速开始

```bash
# 克隆后先装 pre-commit hook（幂等，只需一次）
./tools/install-hooks.sh

# 先构建并安装框架仓
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
| `mars-cloud-<biz>-service` | 8104+ | `66000+` | 业务服务，每服务 1000 或 2000 一段 | 规划中 |

各服务逐个加入根聚合 POM 的 `<modules>`，规划细节见 [docs/services.md](docs/services.md)。

## 约定

- **服务之间不加编译期依赖**，只通过 HTTP 调用交互。这样将来拆库拆服务是零成本。
- 对外响应统一使用框架提供的响应信封，错误码必须落在本服务声明的区间内。
- 应用配置分层：公共配置进配置中心，密钥类走环境变量或本地挂载，不进版本库。

## 许可

内部项目，暂未公开发布 artifact。
