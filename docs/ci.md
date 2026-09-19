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

## 上游框架变更如何触发本仓

框架仓的 CI 在 `main` 构建成功后会向本仓发送 `framework-updated` 事件（配置方法见
[框架仓的 docs/ci.md](https://github.com/csthink/mars-cloud-framework/blob/main/docs/ci.md)）。

本仓这边**无需任何配置**——`repository_dispatch` 是 GitHub 内置触发器，收到事件即按 `main` 构建。
