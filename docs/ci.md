# 持续集成与源码依赖

[CI](../.github/workflows/ci.yml) 在所有分支 push、指向 main 的 pull request、手动触发，以及 `framework-updated` 的 repository_dispatch 时运行。工作流不推送 main。分支与 PR 可在上游候选合入前完成验证。

## 依赖选择

[`.ci/framework-revision`](../.ci/framework-revision) 只包含公开 framework 仓库的一个完整提交 SHA。`tools/resolve-framework.py` 一次读取远端 main，并取得声明提交：

- main 已包含声明提交：选本次读取的确定 main SHA，报告来源为 `main`。
- main 尚未包含它：候选模式选声明 SHA，报告来源为 `candidate`；main 模式拒绝。
- 声明格式错误、提交不存在、无法核实祖先关系：失败，不回退到其他源码或旧 SNAPSHOT。

main 上的运行使用 main 模式，其余分支允许候选模式。上游合入后，重跑 service **同一源提交的 push run** 就会重新解析并取得 main 来源报告，不需要修改 service 提交。新的 attempt 必须重新通过全部步骤。framework 自身的兼容性 CI 则固定 framework 候选 + service main，不经过此 resolver。

解析及检出示例（framework 应为独立、干净的验证 checkout）：

```bash
python3 tools/resolve-framework.py --framework "$FRAMEWORK_DIR" \
  --mode candidate --checkout --output "$RESOLUTION_FILE"
```

将解析输出的 `sha`、`source`、`declared` 传给共享验证入口，不手工换成其他值。

## 统一构建

本地和 CI 都使用所选 framework 提供的构建实现。需要 **Amazon Corretto JDK 25、Apache Maven 3.9.14、Python 3**；Maven 分发包在 CI 下载后核验固定 SHA512。

```bash
export FRAMEWORK_DIR
bash tools/verify.sh --framework-sha "$FRAMEWORK_SHA" \
  --service-sha "$(git rev-parse HEAD)" \
  --framework-source "$FRAMEWORK_SOURCE" --declared-framework "$DECLARED_FRAMEWORK" \
  --cache "$CACHE_DIR" --output "$NEW_REPORT_DIR"
```

两仓路径不必平级；service 委托给显式的 framework 入口。正式验证要求干净提交，使用新建隔离 Maven 仓，第三方缓存可复用但 `com/mars/cloud` 不可复用。framework 执行 clean install，service 执行 clean verify。完整参数、日志规则、报告格式与开发模式见 [framework CI 文档](https://github.com/csthink/mars-cloud-framework/blob/main/docs/ci.md)。

gateway 在 macOS aarch64 上通过 Maven profile 引入 `netty-resolver-dns-native-macos:osx-aarch_64`，版本继续由框架 BOM 管理，避免错误加载另一架构的 DNS 原生库。

## 验证结果

流水线运行工具回归、两仓构建、两仓公开扫描并上传完整报告。artifact 名包含 run ID 与 attempt，保留 30 天。报告记录实际两仓 SHA、测试数量、完整日志和工具版本；PR 临时合并 SHA 与源分支 SHA 分开记录。

必需检查 `构建 + 测试 + 安全扫描` 始终汇总。必要步骤跳过、取消、neutral、缺失或失败都不能通过。未知 WARN / ERROR 阻塞验证；预期负向测试只按精确规则接受。候选报告与 main 来源报告用途不同，不能相互替代。

公开扫描与 hook 调用同一脚本 `tools/check-public-safety-generic.sh`，只能覆盖已知模式，不能代替公开内容人工复核。

## 上游通知

framework 的 main push CI 成功后发送 `framework-updated`；本仓按 main 构建。通知的配置见 [framework 文档](https://github.com/csthink/mars-cloud-framework/blob/main/docs/ci.md)。未配置 secret 时通知步骤跳过；token 失效或请求失败会使通知 job 失败。必要时可手动触发本仓 CI。

push 与 repository_dispatch 按事件分组，不互相取消。跨仓通知是额外回归，不能替代指定源分支 push 的验证报告。
