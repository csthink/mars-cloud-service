# mars-cloud-service —— 开发约定

本仓是 mars-cloud 的**可部署应用仓**：每个模块都是有主类、有端口、有部署配置的应用。

## 硬性约束

| 约束 | 理由 |
| --- | --- |
| 服务之间**不得**加 Maven 依赖，只能走 HTTP 调用 | 保证将来拆库拆服务是零成本 |
| 认证（AuthN）与授权（AuthZ）**永不合并**为同一个服务 | 故障域与权限模型都要保持独立 |
| 登录渠道返回的组织 / 角色**不得**参与鉴权 | 只做身份确认，权限统一由授权服务的决策接口判定 |
| JWT **只承载身份，不承载权限清单** | 改权限要立即生效，不能等令牌过期 |
| 每个服务自己声明错误码区间，落在区间内且不重复 | 启动时校验，冲突提前暴露 |
| 应用配置里**不放明文密钥** | 走环境变量或本地挂载 |

## 服务边界

| 服务 | 端口 | 错误码区间 |
| --- | --- | --- |
| `mars-cloud-gateway` | 8100 | `63000–63999` |
| `mars-cloud-auth-service` | 8101 | `64000–64999` |
| `mars-cloud-upms-service` | 8102 | `65000–65999` |
| 业务服务 | 8104+ | `66000+` |

服务内部的代码按职责分层组织（接口层 / 应用层 / 领域层 / 基础设施层），
具体到模块时以该模块自己的 `AGENTS.md` 为准。

## 新增服务

1. 在根聚合 POM 的 `<modules>` 中加入模块
2. `<parent>` 指向框架仓的 `mars-cloud-dependencies`，依赖不写 `<version>`
3. 声明本服务的错误码区间
4. 接入统一响应（Servlet 栈引 mvc starter）
5. 写本模块的 `README.md` 与 `AGENTS.md`
6. 补一条冒烟验证：健康检查可用、成功与失败路径都返回统一信封

## 构建与验证

```bash
mvn -f ../mars-cloud-framework/pom.xml clean install   # 先装框架
mvn clean install
mvn -pl mars-cloud-upms-service -am test               # 单服务 + 依赖
```

## 提交前

本仓是**公开仓**，提交前必须确认没有夹带内部信息：

```bash
# 在仓库根目录执行完整版扫描器（脚本位于私有设计库的工具目录）
<path-to-private-repo>/tools/check-public-safety.sh .
```

提交信息只描述改动本身，不要写入与本仓无关的评价或背景。
