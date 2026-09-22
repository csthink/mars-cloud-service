# 本地中间件

需要 Docker Engine、Docker Compose、Python 3.10 及以上、JDK 25（含 javac）。从仓库根目录执行：

```bash
python3 dev/middleware.py up
python3 dev/middleware.py status
python3 dev/middleware.py verify
python3 dev/middleware.py export-env --slot 0
python3 dev/middleware.py down
```

`up` 拉取 `images.lock.json` 中的固定镜像，构建 Jaeger 健康探测镜像，并为不含 shell 的 Loki 挂载同一固定 BusyBox 探测程序，初始化并验证 Nacos、RocketMQ、MySQL、Redis、Jaeger、Loki、Grafana 与 xxl-job-admin。启动返回成功才表示实际读写探测通过。Java 消息探测在宿主机运行，使用 NameServer 返回的 Broker 地址。Jaeger 派生镜像按构建输入和平台复用，同机同用户的并发构建由共享文件锁协调。

默认项目名 `mars-lab`，端口为组件基础端口加 20000，全部发布到回环地址。Nacos Console 为 28080，HTTP 为 28848；Grafana 为 23000，Jaeger UI 为 36686，xxl-job-admin 为 28083。全部长期容器限制 CPU、内存、swap、线程数量和日志轮转，并设置 `restart: always`。Docker Desktop 在用户登录后启动时恢复容器；尚未登录时的可用性取决于宿主机服务管理方式。

运行另一套环境：

```bash
python3 dev/middleware.py up --project mars-lab-check --offset 30000
python3 dev/middleware.py verify --project mars-lab-check --offset 30000
python3 dev/middleware.py down --project mars-lab-check --offset 30000
```

项目名必须是 `mars-lab` 或以 `mars-lab-` 开头。第二套保持第一套运行并使用独立网络和数据卷。项目名、状态目录、端口与 Namespace 参数首次使用后绑定，后续命令使用相同参数。端口占用、同名外部资源、初始化冲突或迁移摘要变化都会失败并保留数据。

状态与随机凭据在 ignored `dev/.local/<项目名>/`，目录权限 0700，凭据文件权限 0600。可通过 `--state-dir` 指定另外的私有目录；备份时同时保存状态目录与对应数据卷。管理员用户名分别为 Nacos 的 `nacos`、Grafana 与 xxl-job 的 `admin`，密码在受保护的 `credentials.json` 对应字段中。不要提交、粘贴或公开这些文件。连接变量由 `export-env` 写入新文件，拒绝覆盖已有文件；`--output` 可另选输出路径。

默认建立一个基准 Namespace 和编号 1 到 6 的环境，编号由 `--slots` 明确选择，名称由 `--base-namespace` 与 `--namespace-prefix` 指定。每个 Namespace 初始化共享配置及 gateway、UPMS、sample 应用配置。已有内容不一致时入口停止，需确认配置来源再处理。MySQL 为 auth、upms、product、order、notice 各建独立数据库与账号，编号环境使用 `_sN` 后缀；这不代表相应应用或业务表已经实现。Redis 数据库号隔离数据，不构成账号权限边界。

迁移已有 Nacos 配置时，先停用其他配置写入并备份、逐项确认需要保留的内容。把确认结果写入仅当前用户可读写的 JSON 文件，再运行 `python3 dev/middleware.py up --nacos-config-file <文件>`。示例格式如下，内容字符串保留原有空白与换行：

```json
{"schema":1,"configurations":[{"namespace":"previous-environment","group":"COMMON","data_id":"shared-common.yaml","type":"yaml","content":"feature: enabled\n"}]}
```

清单中的条目替代对应的默认初始化内容，也可增加原环境的 Namespace 和配置；未指定的默认项照常初始化。清单及其摘要原子写入本项目受保护的 `binding.json`，之后不带该参数的 `up` 自动使用保存副本。显式传入不同清单、记录丢失或摘要不符会失败。`binding.json` 现在可能包含配置正文，应与凭据一同保护和备份。

该参数不执行覆盖：入口先检查全部目标，已有内容或类型不同即停止。配置导入需在单独核对源和目标之后执行，保留原配置与备份；再次启动只补充缺失项并逐项读回。`verify` 同样检查完整清单，不能以其他探测成功代替原配置仍存在的证明。

初始化另外建立本项目专用的 Nacos 客户端账号。`export-env` 使用该账号的独立凭据，不输出管理员凭据；旧状态需成功执行新版 `up` 后才能导出。客户端可读写已初始化 Namespace 的配置并注册、发现服务，也可列出 Namespace；账号、角色和权限管理仍由管理员执行。原环境的客户端连接应保留对应 Namespace ID，不能直接用默认编号 0 的导出值替换。

xxl-job 使用官方 AMD64 镜像，在 ARM64 宿主机上需要 Docker 模拟支持；初始化只建立停止状态的本地验证任务，没有业务执行器。Jaeger 保留 48 小时追踪，Loki 保留 48 小时日志；查询同时覆盖从写前日志恢复的内存数据，避免长时间停机后等待刷盘期间漏查。镜像版本、digest、平台与初始化来源分别见 `images.lock.json` 和 `init/NOTICE.md`。Compose 文件采用 JSON 形式的 YAML，便于 Python 标准库和 Compose 共同读取同一份资源配置。

`verify` 检查容器健康、实际资源限制、配置读写、账号隔离、消息往返、追踪与日志查询、数据源及登录。探测数据带独立标识，第一次成功后后续验证先读回这些数据，报告放在状态目录；数据超过保留期时明确失败，不将过期数据作为重启保留证据。

`verify --new-verification-cycle` 将旧周期文件归档后生成新的探测数据；新周期报告不会声称已经证明重启保留。

现有服务验收脚本支持 `E2E_ENV_FILE` 选择导出的连接文件，并继续用 `GATEWAY_PORT`、`UPMS_PORT`、`SAMPLE_PORT` 设置测试进程端口。显式指定的文件缺失会失败，不会改用其他环境。

`down` 移除本项目容器及网络，始终保留卷和状态。重新 `up` 复用原数据与凭据。不要用 `docker compose down -v` 或清库处理初始化错误；失败详情在受保护的 `last-error.log`，恢复前保留状态与卷。长期停用使用 `down`；手动停止容器不表示 Docker 重启后仍停用。
