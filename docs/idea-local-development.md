# 在 IntelliJ IDEA 中运行本地服务

本文从安装框架依赖开始，运行当前已实现的 gateway、sample 和 UPMS，并检查服务健康与服务之间的调用。`mars-cloud-framework` 提供库和 starter，完成 Maven `install` 即可，不需要启动应用进程。正式 `auth-service` 尚未提供，本地认证使用项目自带的测试签发器；它不提供登录页面，也不能用于生产。

## 1. 前置条件

| 工具或条件 | 要求 |
| --- | --- |
| JDK | JDK 25，构建基线为 Amazon Corretto；需要包含 `javac` |
| Maven | Apache Maven 3.9.14 |
| IntelliJ IDEA | 以下菜单以 2026.2 为例；支持 Java Application 启动即可，支持 Spring Boot 的版本也可用 Spring Boot 配置 |
| 本地命令行 | Bash、Python 3.10 及以上、curl；macOS / Linux 可按本文执行 |
| 中间件 | Docker Engine 与 Docker Compose，或已准备好配置的 Nacos |
| 源码 | `mars-cloud-framework` 与 `mars-cloud-service`，使用相互兼容的版本 |

本文命令按 macOS / Linux 编写，未给出 Windows 原生启动流程。

两个仓库放在同一个父目录：

```text
workspace/
├── mars-cloud-framework/
└── mars-cloud-service/
```

初次获取源码时，在自选工作目录执行：

```bash
git clone https://github.com/csthink/mars-cloud-framework.git
git clone https://github.com/csthink/mars-cloud-service.git
```

除明确说明外，后文命令均从 `mars-cloud-service` 根目录执行。本文用于本地开发；固定两仓提交的完整构建验证见 [CI 与构建说明](ci.md)。

## 2. 统一 IDEA 与命令行的 JDK、Maven 本地仓库

分别在两个 IDEA 窗口中打开两仓的根 `pom.xml`，作为 Maven 项目导入。两个窗口都检查：

- **Project Structure → Project SDK**：选择 JDK 25。
- **Settings → Build, Execution, Deployment → Build Tools → Maven**：选择 Maven 3.9.14，确认 User settings file 与 Local repository。
- Maven 的 **Importing → JDK for importer**、**Runner → JRE**：使用 JDK 25。
- 后面创建的应用启动配置 **JRE**：同样选择 JDK 25。

两个 IDEA 窗口和命令行必须使用同一个 Maven 本地仓库。默认可用 `$HOME/.m2/repository`；自定义目录也可以，在 IDEA 中填写其实际绝对路径。仅设置 Project SDK，不会同时改变所有 Maven 和应用运行配置。

在 IDEA Terminal 或系统终端检查：

```bash
java -version
javac -version
mvn -version
export LOCAL_MAVEN_REPO="$HOME/.m2/repository"
```

若使用自定义本地仓库，把 `LOCAL_MAVEN_REPO` 改成与 IDEA 相同的目录。此变量需在后续执行命令的终端中保留；新终端要重新设置。若终端版本不正确，先设置 `JAVA_HOME`，并让 `PATH` 指向所选 JDK 和 Maven 的 `bin`，再继续。

## 3. 先安装 framework，再构建 service

从 `mars-cloud-service` 根目录执行：

```bash
mvn -Dmaven.repo.local="$LOCAL_MAVEN_REPO" -f ../mars-cloud-framework/pom.xml clean install
mvn -Dmaven.repo.local="$LOCAL_MAVEN_REPO" clean package
```

两条命令都应以 `BUILD SUCCESS` 结束。第一条必须执行根聚合项目的 `install`，使 BOM、common、各 starter 和测试支持模块进入本地仓库；只执行 `clean` 或 `package` 不够。无需手工把 Jar 添加到 IDEA 的 Libraries。

也可在 framework 的 IDEA Maven 工具窗口中对根项目执行 `clean install`；随后在 service 窗口执行 **Reload All Maven Projects**，再对根项目执行 `clean package`。每次修改 framework 后都要重新 `install`，service 重新加载 Maven 项目并重启服务，才能使用新依赖。

## 4. 准备 Nacos 与模块 `.env`

### 首次建立本地中间件

没有现成环境时，先启动 Docker，再从 service 根目录执行：

```bash
python3 dev/middleware.py up
python3 dev/middleware.py status
python3 dev/middleware.py export-env --slot 0
```

`up` 会拉取镜像、初始化并验证整套中间件，需要一定的内存、磁盘和下载时间。默认 Nacos Console 为 `http://127.0.0.1:28080`，应用连接地址为 `127.0.0.1:28848`，客户端 gRPC 端口为 `29848`。应用配置填 `28848`，不填 Console 或 gRPC 端口。

导出的连接文件默认为 `dev/.local/mars-lab/environment-0.env`，包含随机生成的应用账号凭据，只保存在本机。若文件已存在，命令拒绝覆盖：使用已有文件，或用 `--output` 指定一个新的输出文件。其他部署参数、资源要求与维护命令见 [本地中间件说明](../dev/README.md)。

### 使用已有 Nacos 环境

已有环境应沿用实际地址、应用账号和 Namespace ID，不必重新 `up`，也不要用默认导出值替换已有 Namespace。自定义 project、offset 或 state directory 时，按原初始化参数操作。

三个服务使用同一个非空 Namespace ID，并需要下列配置存在且非空：

| Group | Data ID |
| --- | --- |
| `COMMON` | `shared-common.yaml` |
| `DEFAULT_GROUP` | `mars-cloud-gateway.yaml` |
| `DEFAULT_GROUP` | `mars-cloud-sample-service.yaml` |
| `DEFAULT_GROUP` | `mars-cloud-upms-service.yaml` |

项目中间件入口会初始化这些配置；已有环境的准备要求见各模块 README。Config 与 Discovery 必须指向同一个 Namespace ID，不能把控制台显示名称当作 ID。

### 创建每个模块的配置文件

仅在文件尚不存在时，从 service 根目录复制模板：

```bash
umask 077
for module in mars-cloud-gateway mars-cloud-sample-service mars-cloud-upms-service; do
  if [ ! -e "$module/.env" ]; then
    cp .env.example "$module/.env"
    chmod 600 "$module/.env"
  fi
done
```

在编辑器中打开连接文件，将其中 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE_ID`、`NACOS_USERNAME`、`NACOS_PASSWORD` 的取值填入三个模块各自的 `.env`。使用已有环境时，填写其实际参数。保留其他有效配置，不整份覆盖已有 `.env`。

三个模块均保持 `SPRING_PROFILES_ACTIVE=local`；UPMS 保持 `UPMS_LOCAL_FIXTURE_ENABLED=true`，用于加载演示权限数据。`SERVER_PORT` 保持注释，由各应用使用自己的默认端口。sample 不使用数据库或 Redis，UPMS 的 `local` 配置关闭了这两者的连接与健康检查。

`.env` 已被 Git 忽略。账号密码只放本机文件或运行环境，不写进 `application-local.yml`、共享 IDEA 配置或版本库。

## 5. 启动本地测试签发器

sample 和 UPMS 会校验访问令牌；只配 Nacos 还不能启动它们。二者共用同一个测试签发器，gateway 当前无需配置签发方。

在 service 根目录的 Terminal 中执行以下整段。它使用第 2 节设置的 Maven 本地仓库，通过已有测试依赖启动临时签发器：

```bash
bash <<'BASH'
set -e
: "${LOCAL_MAVEN_REPO:?请先设置与 IDEA 一致的 LOCAL_MAVEN_REPO}"
export SECURITY_TEST_MAVEN_REPO="$LOCAL_MAVEN_REPO"
source scripts/security-test-runtime.sh
trap stop_security_test_issuer EXIT
trap 'exit 130' INT TERM
start_security_test_issuer "$PWD"

printf '\n将以下两行分别填入 sample 和 UPMS 的 .env：\n'
printf 'MARS_SECURITY_ISSUER_URI=%s\n' "$MARS_SECURITY_ISSUER_URI"
printf 'MARS_SECURITY_JWK_SET_URI=%s\n' "$MARS_SECURITY_JWK_SET_URI"
printf '\n业务请求所需的请求头文件路径：%s\n' "$SECURITY_CURL_HEADER"
read -r -p '保持本终端运行；调试结束后按回车停止：' < /dev/tty
BASH
```

将输出的两个地址填入以下文件已有的同名配置项，没有则追加，不要重复定义：

- `mars-cloud-sample-service/.env`
- `mars-cloud-upms-service/.env`

签发器使用动态端口，两个地址分别以 `/issuer` 和 `/keys` 结尾。复制终端实际输出，不使用固定端口示例。它只监听本机回环地址；`local` profile 允许这些 HTTP 地址。

**保持这个 Terminal 运行，然后在 IDEA 中启动应用。** 终端里的环境变量不会自动传入已经打开的 IDEA 应用配置，因此必须按下一节显式加载 `.env`。

测试令牌有效期为 5 分钟。健康检查不使用令牌，不受其过期影响；业务调试超过有效期时，停止并重新启动测试签发器，更新两份 `.env` 的地址、重新启动 sample 和 UPMS，并使用新生成的请求头文件。每次重启都会生成新的密钥和端口。

此签发器只用于本地测试。脚本不打印令牌正文，退出时清理自己创建的进程和临时凭据；不要把测试支持依赖改为应用的运行时依赖。

## 6. 配置 IDEA 的三个 Run / Debug 启动项

在 service 的 IDEA 窗口，分别从三个入口类的 `main` 方法创建启动配置，再进入 **Run → Edit Configurations…** 检查下表。可使用 Spring Boot 配置；没有此配置类型时使用 Java **Application**。

| 配置 | Main class | Use classpath of module | 默认端口 |
| --- | --- | --- | --- |
| GatewayApplication | `com.mars.cloud.service.gateway.GatewayApplication` | `mars-cloud-gateway` | `8100` |
| SampleApplication | `com.mars.cloud.service.sample.SampleApplication` | `mars-cloud-sample-service` | `8103` |
| UpmsApplication | `com.mars.cloud.service.upms.UpmsApplication` | `mars-cloud-upms-service` | `8102` |

每个启动项都配置：

1. **JRE** 选择 JDK 25，**Working directory** 选择相应模块目录。
2. **Environment variables** 中使用 **Browse for .env files and scripts**，选择该模块的 `.env` 的实际绝对路径。三个启动项分别选择三个文件，不选择仓库根的模板。
3. **Modify options → Add VM options**，填写下列 JVM 参数，不放到 Program arguments：

   ```text
   --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED
   ```

4. 若使用 Spring Boot 配置，**Active profiles** 填 `local`；Java Application 由 `.env` 中的 `SPRING_PROFILES_ACTIVE=local` 激活。确认没有其他 VM options 或 Program arguments 把它覆盖为别的 profile。
5. 点击 **Apply**，然后 **Run** 或 **Debug**。

IDEA 2026.2 可直接加载 `.env`；菜单入口见 [JetBrains 环境变量与 JVM 参数说明](https://www.jetbrains.com/help/idea/program-arguments-and-environment-variables.html)。旧版本若没有文件选择入口，可在 Environment variables 表格中逐项填写相同变量。

**Spring Boot 本身不会自动读取 `.env`。** Working directory 指向模块并不等于加载文件。`run-local.sh`、Maven 启动和 IDEA 直接运行 `main` 是不同入口，不能假定前一个入口设置的环境变量或 JVM 参数会传给后一个。

建议先启动 gateway，再启动 sample、UPMS。gateway 可以先于 UPMS 启动；UPMS 注册完成后 gateway 自动发现，无需为此重启。

## 7. 检查结果

### 健康与路由

启动日志应出现 `Started ...Application`，并显示 Nacos 注册成功。依次访问：

| 检查 | URL | 预期 |
| --- | --- | --- |
| gateway | `http://127.0.0.1:8100/actuator/health` | HTTP 200，`status` 为 `UP` |
| sample | `http://127.0.0.1:8103/sample/actuator/health` | HTTP 200，`status` 为 `UP` |
| UPMS | `http://127.0.0.1:8102/upms/actuator/health` | HTTP 200，`status` 为 `UP` |
| gateway 转发到 UPMS | `http://127.0.0.1:8100/upms/actuator/health` | HTTP 200，`status` 为 `UP` |

UPMS 刚启动时，服务发现可能需要片刻；尚未发现实例时 gateway 返回 `503`。当前 gateway 声明了 `/upms/**` 与 `/sample/**` 两条路由，两个服务也都可以按各自端口直接访问。

### 带令牌的业务请求

健康检查允许匿名访问，不能代替认证和业务调用验证。另开终端，把下列占位路径替换为第 5 节打印的请求头文件路径，保持签发器运行并在令牌有效期内执行：

```bash
AUTH_HEADER_FILE='<测试签发器打印的请求头文件路径>'
curl --fail-with-body --header "@$AUTH_HEADER_FILE" \
  http://127.0.0.1:8103/sample/v1/orders/1
```

预期 HTTP 200，响应含 `success:true` 和订单 `id:1`。不带令牌访问业务接口应得到 `401`，无需为本地调试关闭认证。

在同一终端继续验证 sample 经服务发现调用 UPMS：

```bash
curl --fail-with-body --header "@$AUTH_HEADER_FILE" \
  -H 'Content-Type: application/json' \
  -d '{"action":"view","resource":"demo:view:domain:kubernetes-ops"}' \
  http://127.0.0.1:8103/sample/v1/upms/decision
```

预期 HTTP 200，响应含 `success:true`，结果中有 `decision` 与 `decision_id`。该请求使用测试签发器生成的身份，要求 UPMS 已启动、注册到相同 Namespace，并加载本地演示权限数据。

完整行为验证见 [sample 文档](../mars-cloud-sample-service/README.md)。自动验收脚本会自行启动应用进程；运行前先停止 IDEA 中对应的实例以释放端口。只完成上面四个健康检查，不能视为全部端到端验证通过。

## 8. 常见问题

| 现象 | 核对方法 |
| --- | --- |
| service 找不到框架依赖 | framework 根项目执行 `clean install`；确认两个 IDEA 窗口与终端的本地仓库一致，再重新加载 service Maven 项目 |
| `Could not resolve placeholder 'NACOS_NAMESPACE_ID'` | 当前启动项是否加载了正确模块的 `.env`，文件中的 Namespace ID 是否非空 |
| 连接 `127.0.0.1:9848` 被拒绝 | 检查是否遗漏 `NACOS_SERVER_ADDR` 而回退到默认 `8848`；默认编排应填写 `28848`，客户端连接对应的 `29848` |
| Nacos 配置读取失败 | 核对容器状态、应用账号、Namespace ID、Group、Data ID；不要用 `optional:` 掩盖必需配置缺失 |
| `Security issuer and JWK endpoints require HTTPS; loopback HTTP is local/test only` | 空地址也会触发此错误；sample 和 UPMS 都必须填写实际签发器地址，并启用 `local`。不要填写控制台地址 |
| 签发方发现失败或业务令牌验证失败 | 确认测试签发器仍运行，地址与当前进程一致；令牌是否已超过 5 分钟；重启签发器后要同步地址、应用和请求头文件 |
| 业务接口 `401`，健康检查正常 | 使用当前签发器生成的有效令牌；浏览器直接打开业务接口没有自动携带 Bearer 令牌 |
| gateway 访问 UPMS 返回 `503` | 先检查 UPMS 直连健康，再确认二者在同一 Namespace、UPMS 已注册且尚有可用实例 |
| 端口被占用 | 检查 IDEA 是否重复启动，或自动验收脚本仍在运行；停止自己重复启动的实例 |
| `Unsafe` / native access 警告 | 将第 6 节的两个参数添加到应用 VM options；Maven Runner 的参数不会自动进入 IDEA 应用进程 |
| SpringDoc 提示文档端点已启用 | 本地模式下的提示；生产配置按 [部署说明](deployment.md) 单独处理 |

## 9. 结束调试与再次启动

在 IDEA 停止三个应用，再回测试签发器 Terminal 按回车，脚本会停止签发器并删除临时令牌。中间件可以保留供下一次调试使用；需要停止本项目中间件时，按 [中间件说明](../dev/README.md) 执行 `down`，它保留数据卷和状态。

下次调试时，确认中间件可用，重新启动测试签发器，更新 sample 与 UPMS 的两个地址，再启动三个应用。framework 未变化时不必重复安装依赖；变化后按第 3 节重新安装。
