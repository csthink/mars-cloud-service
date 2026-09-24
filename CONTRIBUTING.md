# 贡献指南

本仓是 mars-cloud 的**可部署应用仓**：每个模块都是有主类、有端口、有部署配置的应用。
可复用的库与 starter 在配套的框架仓 `mars-cloud-framework`。

这份文档说明改本仓时必须守住的约束、以及改动怎么验证。

## 硬性约束

| 约束 | 理由 |
| --- | --- |
| 服务之间**不得**加 Maven 依赖，只能走 HTTP 调用 | 保证将来拆库拆服务是零成本 |
| 认证（AuthN）与授权（AuthZ）**永不合并**为同一个服务 | 故障域与权限模型都要保持独立 |
| 登录渠道返回的组织 / 角色**不得**参与鉴权 | 只做身份确认，权限统一由授权服务的决策接口判定 |
| JWT **只承载身份，不承载权限清单** | 改权限要立即生效，不能等令牌过期 |
| 每个服务自己声明错误码区间，落在区间内且不重复 | 启动时校验，冲突提前暴露 |
| 应用配置里**不放明文密钥**，也不放任何环境相关的取值 | 走环境变量；见 [部署说明](docs/deployment.md) |

## 服务边界

| 服务 | 端口 | 错误码区间 | 状态 |
| --- | --- | --- | --- |
| `mars-cloud-gateway` | 8100 | `63000–63999` | ✅ |
| `mars-cloud-auth-service` | 8101 | `64000–64999` | 协议签发、账号与会话持久化；生产短信登录待接入 |
| `mars-cloud-upms-service` | 8102 | `65000–65999` | ✅ |
| `mars-cloud-sample-service` | 8103 | `66100–66199` | ✅ |
| 业务服务 | 8104–8179 | `66000–99999` 内自选 | 按需新建 |
| `mars-cloud-monitor` | 8190 | 无 | ✅ |

业务服务共用框架分配表里的 `business` 区段，各自声明一段、互不重叠。
实际占用的子区间登记在 [docs/services.md](docs/services.md)——**新建服务时挑一段空白的**，
挑重了启动会失败。

服务内部的代码按职责分层组织（接口层 / 应用层 / 领域层 / 基础设施层）。

## 新增服务

1. 在根聚合 POM 的 `<modules>` 中加入模块
2. `<parent>` 指向框架仓的 `mars-cloud-dependencies`，依赖不写 `<version>`
3. 在 `config/application.yml` 声明本服务的错误码区间，并把占用情况登记回
   [docs/services.md](docs/services.md)
4. 接入统一响应（Servlet 栈引 mvc starter；响应式栈参照 `mars-cloud-gateway` 的 `web` 包，复用 `common` 的信封自行实现）
5. 引入框架的 observability starter，不再自己声明 Actuator、指标注册表与 `management.*` 配置；管理端点要认证时
   声明 `spring-boot-starter-security`；在 `config/application.yml` 显式列出 `mars.env.dev-profiles`；
   `Dockerfile` 同时 `EXPOSE` 业务端口与管理端口（业务端口加 1000），健康探测走管理端口
6. 写模块自己的 `README.md`，并在 [docs/services.md](docs/services.md) 登记职责边界
7. 补一条冒烟验证：健康检查可用、成功与失败路径都返回统一信封
8. 在 `mars-cloud-gateway` 的路由表里加一条到本服务的路由（系统外部请求统一经过 gateway，不自动暴露注册中心里的服务）

第 4 步之前建议先看 `mars-cloud-sample-service`——它是最小的可运行示例，
把信封、异常映射、错误码与 i18n 的接线完整演示了一遍，且自带端到端验收脚本。

## 构建与验证

正式验证使用 **Corretto JDK 25 与 Maven 3.9.14**，通过 `tools/verify.sh` 委托给所选 framework，固定两仓提交并生成完整报告，见 [docs/ci.md](docs/ci.md)。以下 Maven 命令用于开发迭代，示例假定两个仓平级。

```bash
mvn -f ../mars-cloud-framework/pom.xml clean install   # 先装框架（框架改动后需重装）
mvn -pl mars-cloud-upms-service -am test               # 单服务 + 依赖
mvn clean package                                      # 全量
```

## 提交前

本仓是**公开仓**，提交前请确认改动里没有夹带不该公开的内容——
内部主机名、内网地址、本机绝对路径、真实凭据、内部计划或未公开的评审结论。

克隆后装一次 pre-commit 钩子（幂等），它会在每次提交时检查暂存内容：

```bash
./tools/install-hooks.sh
```

钩子只是第一道，且可以被 `git commit --no-verify` 绕过。**内容一旦 push 出去，
即使后续删除也仍留在 Git 历史里**，所以提交前请自己再过一遍。

提交信息只描述改动本身，不要写入与本仓无关的评价或背景。
