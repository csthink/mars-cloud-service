# 持续集成

本仓的流水线定义在 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)，在以下时机运行：

| 触发 | 说明 |
| --- | --- |
| push 到 `main` | 常规提交 |
| 指向 `main` 的 pull request | 合并前把关 |
| `repository_dispatch`（`framework-updated`） | 上游框架仓构建成功后自动触发 |
| 手动 | 在 Actions 页面点 Run workflow |

## 流水线做了什么

1. **取依赖**：检出 `csthink/mars-cloud-framework`（两个仓都是公开的，用默认 token 即可）
   并 `mvn clean install`，把框架装进本地仓库
2. **构建 + 测试**：`mvn clean package`（Java 25 / Temurin）
3. **公开安全扫描**：`tools/check-public-safety-generic.sh`
4. **上传测试报告**（无论成败）

## 为什么要先装框架

本仓的服务依赖框架的 `1.0.0-SNAPSHOT`，而快照不在任何公开仓库里。
CI 上必须**从源码装一遍框架**，否则依赖解析必然失败。

框架真发版（`1.0.0` 而非永久 SNAPSHOT）之后，这一步可以换成从制品库拉取。
在那之前，从源码构建是唯一自洽的做法——也让「框架改了服务会不会挂」在 CI 上顺带被验证。

## 为什么安全扫描在 CI 里也要跑

本仓装了 pre-commit hook，但 hook 可以被 `git commit --no-verify` 绕过。
**内容一旦 push 到公开仓，即使随后删除，Git 历史里仍在**——只能改写历史或轮换凭据。
所以 CI 是最后一道，不能只靠本地 hook。

本地随时可以自己跑一遍：

```bash
bash tools/check-public-safety-generic.sh .
```

扫描器拦下的是三类：凭据与私钥、本机绝对路径与家目录引用、内网地址段。
**扫描器只能拦已知模式**——它拦不住「语气里透出的内部判断」，写文档与注释时仍要自己把关。

**因此「CI 绿」不等于「没有内部信息外泄」**：上述三类之外的判断（内部代号与里程碑、
对其他系统的评价、未公开的计划与结论）没有任何自动化检查，只能靠人工复核。
CI 通过只说明它检查过的那三类没问题。

## 上游框架变更如何触发本仓

框架仓的 CI 在 `main` 构建成功后会向本仓发送 `framework-updated` 事件（配置方法见
[框架仓的 docs/ci.md](https://github.com/csthink/mars-cloud-framework/blob/main/docs/ci.md)）。

本仓这边**无需任何配置**——`repository_dispatch` 是 GitHub 内置触发器，收到事件即按 `main` 构建。

> **当前状态**：上游框架仓已配置 `SERVICE_DISPATCH_TOKEN`（细粒度 PAT：只勾本仓 +
> `Contents: Read and write`），**自动触发已生效并实测通过**——框架推送后本仓在数秒内
> 以 `event=repository_dispatch` 启动，无需手动干预。
>
> 想确认是否仍然生效：

```bash
gh run list -R csthink/mars-cloud-service --limit 5   # 看到 event=repository_dispatch 即为已生效
```

> 若哪天该 secret 失效（到期、被撤销），框架仓的流水线**不会失败**，只是跳过那一步；
> 此时可手动触发本仓：`gh workflow run ci.yml -R csthink/mars-cloud-service`
