# mars-cloud-monitor

运行中实例的**监控面板**：基于 Spring Boot Admin，经 Nacos 发现全部实例，展示健康状态、指标、日志级别与线程信息，
实例状态变化或实例被移除时写出日志通知。被监控的服务不需要装客户端，也不需要改配置。

- 入口类：`com.mars.cloud.service.monitor.MonitorApplication`
- 端口：`8190`；管理端口 `9190`；无 context path
- 业务端口与管理端口都只绑内网地址（`SERVER_ADDRESS`，本机默认 `127.0.0.1`），注册到 Nacos 的也是这个地址；
  不经网关，也不在网关路由表里
- 不对外提供业务接口，没有错误码区间

## 访问

面板自带表单登录，只有一个管理员账号，来自环境变量：

| 变量 | 作用 |
| --- | --- |
| `MONITOR_USERNAME` / `MONITOR_PASSWORD` | 面板管理员账号。任一为空即启动失败：面板能读到全部实例的管理端点，不能用默认口令或匿名开放 |
| `MARS_MANAGEMENT_USERNAME` / `MARS_MANAGEMENT_PASSWORD` | 面板读取各实例管理端点时使用的凭据，与各服务的管理端点凭据相同 |
| `SERVER_ADDRESS` | 业务端口、管理端口与注册到 Nacos 的地址。部署时填私网地址，不要绑公网；在容器里运行时填容器在私网里的地址，默认的 `127.0.0.1` 在容器外不可达 |

浏览器访问 `http://127.0.0.1:8190/` 会被带到登录页；非浏览器的未登录请求得到 401。面板也接受 Basic 认证，
例如用管理员账号读取实例列表：

```bash
curl -u "$MONITOR_USERNAME:$MONITOR_PASSWORD" -H 'Accept: application/json' http://127.0.0.1:8190/applications
```

## 实例发现

面板经 `spring-boot-admin-server-cloud` 从 Nacos 读取实例，按实例元数据里的 `management.port` 找到 Actuator 端点。
这个元数据由各服务引入的 observability starter 在注册时写入；缺少它时面板会去业务端口找 Actuator 端点，而业务端口上没有。

面板停机时先停止实例发现，再由 Nacos 注销面板并关闭客户端。Nacos 注销后还要等
`spring.cloud.nacos.discovery.graceful-shutdown-wait-time`（默认 10 秒）才真正停机；若这段时间里照常发现，
Nacos 客户端会被重新创建，面板也会把自己当作被移除的实例。

## 通知

面板用 Spring Boot Admin 的日志通知，每条通知写成一行 INFO。两种情况都会写：

- 实例状态变化，例如 `UP` 变为 `OUT_OF_SERVICE` 或 `OFFLINE`：

  ```
  Instance mars-cloud-sample-service (13631fc3079b) is OUT_OF_SERVICE
  ```

- 实例被移除：实例从 Nacos 注销（正常停止或进程退出）后，面板在下一次发现刷新时把它移除：

  ```
  Instance mars-cloud-sample-service (13631fc3079b) DEREGISTERED
  ```

两种都写的原因：实例停止时先从 Nacos 注销，面板的状态轮询（`spring.boot.admin.monitor.status-interval`，默认 10 秒）
与发现刷新（`spring.cloud.nacos.discovery.watch-delay`，默认 30 秒）互不等待。发现刷新先到时，实例在状态轮询发现
它下线之前就被移除；Spring Boot Admin 自带的日志通知只写状态变化，这时实例会从面板上消失而没有任何通知。
移除在注销后一个发现刷新间隔内必然发生，所以每次实例下线至少有其中一行。滚动发布时，每个被替换的旧实例也会留下
一行移除记录。

日志随控制台输出一起被采集，是事后排查的依据。钉钉群机器人通知暂不支持：面板自带的钉钉通知器把签名参数编码了两次，
与钉钉要求的单次编码不一致。设置 `spring.boot.admin.notify.dingtalk.webhook-url`（含空值）时面板启动失败，
不会装配一个发不出去的通知器。

## 版本

Spring Boot Admin 固定为 4.0.x（对应 Spring Boot 4.0）；4.1.x 对应 Spring Boot 4.1，不能用。模块 POM 的依赖禁止规则
在构建期拒绝 4.1 及以上的版本，测试再从运行时 classpath 确认一次。

## 本地启动

```bash
cp ../.env.example .env  # 填写本机 Nacos、面板账号与管理端点凭据
./run-local.sh           # 显式加载 .env，再以 local profile 启动
```

先启动 Nacos，并在同一 Namespace 下准备 `COMMON/shared-common.yaml` 与 `DEFAULT_GROUP/mars-cloud-monitor.yaml`。

### `java -jar` 启动

```bash
set -a && . ./.env && set +a
java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar target/mars-cloud-monitor.jar
```

JVM 参数的原因见 [`docs/deployment.md`](../docs/deployment.md) 的「JVM 参数」一节。

## 验收

仓根的 `verify-observability-e2e.sh` 同时启动网关、UPMS、sample 与面板，核对面板发现四个应用且状态为 `UP`，
并在停止 sample 后 60 秒内为 sample 写出状态变化或移除的日志通知。

## 依赖边界

- 依赖框架仓的 `mars-cloud-nacos-spring-boot-starter` 与 `mars-cloud-observability-spring-boot-starter`，
  以及 `spring-boot-admin-starter-server`（它自带 server、ui 与经注册中心发现实例的 cloud 模块）
- 面板自己的表单登录与 Basic 认证由 `spring-boot-starter-security` 提供
- 与其他服务之间**不加编译期依赖**，只经注册中心与各实例的管理端点交互
