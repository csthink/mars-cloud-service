# mars-cloud-sample-service

**框架使用示例**：演示统一响应信封、错误码区间、i18n，以及经 Nacos 服务发现调用 UPMS。

这不是业务服务，而是**可运行的文档**——它参与构建与测试，所以文档里写的每一条行为都有测试钉住，
不会随框架演进而悄悄失效。

## 先跑起来

需要 **JDK 25**、Maven 与本机 Nacos。启动前在环境中设置 `MARS_SECURITY_ISSUER_URI`，可选设置 `MARS_SECURITY_JWK_SET_URI`；信任配置要求 HTTPS，local/test 可使用回环 HTTP。Nacos Namespace 中要有：

- `COMMON/shared-common.yaml`
- `DEFAULT_GROUP/mars-cloud-sample-service.yaml`
- 调用 UPMS 时还需要 `DEFAULT_GROUP/mars-cloud-upms-service.yaml`

本项目的两个仓是**平级目录**——下面的命令用 `../mars-cloud-framework` 引用框架仓，
所以请把两个仓克隆到同一个父目录下：

```
<任意父目录>/
├── mars-cloud-framework/     # 框架仓
└── mars-cloud-service/       # 本仓
```

框架尚未发布到制品库，所以要先把它装进本地 Maven 仓库（框架改动后需重装）：

```bash
# 1) 装框架（只需一次）
cd ../mars-cloud-framework && mvn clean install

# 2) 构建本示例
cd ../mars-cloud-service && mvn -pl mars-cloud-sample-service -am package

# 3) 准备 .env 后启动（默认 local profile）
cd mars-cloud-sample-service && ./run-local.sh
```

服务监听 `8103`，context path 是 `/sample`；管理端点在管理端口 `9103` 上，不带 context path。业务请求使用合法 Bearer 令牌，audience 包含 `mars-cloud-sample-service`，调用 UPMS 时另含 `mars-cloud-upms-service`。下列示例的 `AUTH_HEADER_FILE` 指向权限 0600 的本地文件，内容为 `Authorization: Bearer <合法令牌>`；不要把令牌写入 `.env`、命令行参数或版本库。验证：

```bash
B=http://127.0.0.1:8103/sample

curl -s http://127.0.0.1:9103/actuator/health     # 管理端口，匿名可读
# {"groups":["liveness","readiness"],"status":"UP"}

curl --header "@$AUTH_HEADER_FILE" -s $B/v1/orders/1
# {"success":true,"result":{"id":"1","sku":"demo-sku","quantity":2}}
```

一条命令跑完全部行为验收（脚本会自己起一个进程，跑完关掉）：

```bash
# 先停掉上面手动启动的实例（Ctrl-C），否则端口被占，脚本会拒绝运行
./verify-e2e.sh
```

验证 sample 经 Nacos 服务名调用 UPMS、三个内部身份头的传播，以及 UPMS 停止后的失败映射。
脚本需要 Python 3，临时代理仅监听本机并记录三个固定演示身份头；不记录完整请求或凭据：

```bash
./verify-feign-e2e.sh
```

上面的 `curl` 验证需要**另开一个终端**、并保持服务进程运行。

## 这个示例演示了什么

### 1. 成功响应自动包成信封

控制器只管返回业务对象，包装由 starter 完成：

```java
@GetMapping("/{id}")
public Map<String, Object> get(@PathVariable String id) {
    return Map.of("id", id, "sku", "demo-sku", "quantity", 2);
}
```

```bash
curl --header "@$AUTH_HEADER_FILE" -s $B/v1/orders/1
```

```json
{ "success": true, "result": { "id": "1", "sku": "demo-sku", "quantity": 2 } }
```

信封为 `null` 的字段不参与序列化，所以成功响应里看不到 `code` / `message`。

### 2. 业务拒绝是 HTTP 200，不是 4xx

这是最容易记错的一条：**业务规则拒绝属于成功处理的请求**，HTTP 状态码仍是 200，
由信封的 `success` 与 `code` 表达。只有协议层面的错误（参数不合法、未认证、资源不存在、
服务不可用）才走非 200。

```java
throw new BusinessException(SampleErrorCode.OUT_OF_STOCK);
```

```bash
curl --header "@$AUTH_HEADER_FILE" -s -X POST $B/v1/orders -H 'Content-Type: application/json' \
  -d '{"sku":"out-of-stock","quantity":1}'
```

```json
{ "success": false, "code": "66102", "message": "Out of stock" }
```

消费方要先看 HTTP 状态码与 `success` 位，再决定读 `result` 还是 `code`。

> **本地跑时你会多看到一个 `result`**：上面的示例是**非开发环境**的形状。
> 本模块默认的 `local` profile 在 `mars.env.dev-profiles` 列表里，属开发环境，
> 因此失败响应会额外回带调试详情（异常详情、请求 IP、方法、URI、请求头），
> 例如 `"result":{"detail":"66102","ip":"127.0.0.1","method":"POST","uri":"..."}`。
>
> 这个开关由 `mars.env.dev-profiles` 控制——**上线前务必确认生产 profile 不在该列表里**，
> 否则调试详情会连同错误响应一起发出去。见 [部署说明](../docs/deployment.md)。

### 3. 参数校验失败是 HTTP 400

校验注解写在 DTO 上，控制器加 `@Valid`，业务代码里不写 if 判断：

```java
public record CreateOrderRequest(
        @NotBlank(message = "sku 不能为空") String sku,
        @Min(value = 1, message = "quantity 至少为 1") int quantity) {
}
```

```java
@PostMapping
public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest request) { ... }
```

### 4. 资源不存在是 HTTP 404

`ResourceNotFoundException` 自带状态码，不需要 `@ResponseStatus`，也不需要写异常处理器：

```java
throw new ResourceNotFoundException(SampleErrorCode.RESOURCE_NOT_FOUND);
```

完整的异常到状态码映射表见
[mvc starter 的 README](https://github.com/csthink/mars-cloud-framework/blob/main/mars-cloud-mvc-spring-boot-starter/README.md)。

### 5. 错误文案随 `Accept-Language` 变化

```bash
curl --header "@$AUTH_HEADER_FILE" -s -H 'Accept-Language: zh-CN' $B/v1/orders/missing
# {"success":false,"code":"66101","message":"资源不存在"}

curl --header "@$AUTH_HEADER_FILE" -s -H 'Accept-Language: en-US' $B/v1/orders/missing
# {"success":false,"code":"66101","message":"Resource not found"}
```

见下方「错误码与国际化的接线」。

### 6. 经服务名调用 UPMS

sample 不依赖 UPMS 模块，通过框架 `mars-cloud-security-feign` 提供的 `PdpClient` 调用 UPMS，应用适配器保留本服务 DTO：

```bash
curl --header "@$AUTH_HEADER_FILE" -s -X POST $B/v1/upms/decision \
  -H 'Content-Type: application/json' \
  -d '{"action":"view","resource":"demo:view:domain:kubernetes-ops"}'
```

starter 会经 Nacos 选择 `mars-cloud-upms-service` 实例，并传播已验证令牌中的身份及当前访问令牌。
UPMS 返回 4xx 或无效响应时，sample 返回自己的 `66103`；无实例、连接失败或超时时返回
HTTP 503 + `66104`。下游 message 不会进入 sample 响应。

### 7. 验证身份与方法权限

`GET /v1/security/me` 返回已验证的 subject、clientId、tenantId。`GET /v1/security/decision` 通过真实方法注解检查固定的 `view` / `demo:view:domain:kubernetes-ops` 权限；拒绝时返回 403，权限服务故障时返回 502 或 503。主体不能由请求体或内部头替换。

## 错误码与国际化的接线

三处要配套，缺一处就会在启动期或运行期出问题：

**① 声明本服务的区段**（`src/main/resources/config/application.yml`）：

```yaml
mars:
  error-code:
    validate: true
    framework-layers: [ common, mvc, security ]   # 用到的框架层，区间取自框架内置分配表
    ranges:
      - owner: business
        start: 66100
        end: 66199
```

业务服务共用 `business` 区段（66000–99999），各自声明一段、互不重叠。写错区间、
越出所属区段、或与别的声明重叠，**应用直接拒绝启动**——冲突在启动瞬间暴露。

**② 定义并注册错误码**：

```java
public enum SampleErrorCode implements ErrorCode {
    RESOURCE_NOT_FOUND(66101),
    OUT_OF_STOCK(66102);
    // ...
}

@Component
public class SampleErrorCodeRegistrar implements ErrorCodeRegistrar {
    @Override
    public Collection<? extends ErrorCode> codes() {
        return Stream.concat(Arrays.stream(SampleErrorCode.values()),
                Arrays.stream(SecurityErrorCode.values())).toList();
    }
}
```

**③ 写文案**（`src/main/resources/i18n/`，三份同步维护）：

```properties
error.code.66101=资源不存在
```

并在配置里声明资源位置——**漏了这行会退化成裸错误码**：

```yaml
spring:
  messages:
    basename: i18n/error-code
```

文案查找有**四级**兜底：`error.code.<数字>` → 纯数字 key → `mars.codes` 本地兜底配置 →
数字码本身。完整约定与「哪一级在异常自带文案里生效」见
[错误码约定](https://github.com/csthink/mars-cloud-framework/blob/main/docs/error-code.md)。

## 配置怎么放

**配置项进版本库，配置项的环境相关取值只从环境变量来。**

| 层 | 文件 | 进版本库 | 放什么 |
| --- | --- | --- | --- |
| 默认配置 | `config/application.yml` | ✅ | 应用名、端口、监听地址、context path、i18n、错误码区间声明 |
| profile 覆盖 | `config/application-local.yml` | ✅ | **仅**行为开关；**不含任何 host / 端口 / 库名 / 口令** |
| Nacos 动态配置 | 环境 Namespace | ❌ | 共享默认值与 sample 应用覆盖值 |
| 环境取值 | 环境变量（开发时用 `.env` 承载） | ❌ | 地址、端口、口令 |

`application-local.yml` 能进版本库，是因为它对每个人每台机器都是同一份；
任何带 host 或口令的配置都不该进仓。`LocalConfigHygieneTest` 会守护这条约定。

业务端口与管理端口都只绑定 `SERVER_ADDRESS`（本机缺省 `127.0.0.1`），注册到 Nacos 的也是这个地址；部署时填实例的私网 IPv4 地址，见 [部署说明](../docs/deployment.md) 的「端口与 context path」。

> Spring Boot **本身不读 `.env`**。`./run-local.sh`（即 `mvn spring-boot:run`）会自动读取
> 模块根目录的 `.env`，而 `java -jar` **不会**——后者需要先
> `set -a && . ./.env && set +a` 导出。

## 模块结构

```
src/main/java/com/mars/cloud/service/sample/
├── SampleApplication.java              # 入口
├── error/
│   ├── SampleErrorCode.java            # 错误码定义
│   └── SampleErrorCodeRegistrar.java   # 注册给框架（启动期校验用）
├── upms/
│   ├── UpmsDecisionAdapter.java         # 权限调用结果与 sample 错误码映射
│   └── *Request.java / *Result.java     # sample 自己维护的 HTTP 协议 DTO
├── security/
│   └── SecurityController.java          # 已验证身份与方法权限示例
└── web/
    ├── OrderController.java            # 示例端点
    └── CreateOrderRequest.java         # 带校验注解的请求 DTO
```

生产服务通常还会按 `interfaces / application / domain / infrastructure` 分层，
参见同仓的 [`mars-cloud-upms-service`](../mars-cloud-upms-service/)。

## 依赖边界

本模块依赖框架仓的 mvc、Nacos、security starter、security-feign 适配模块与 observability starter，分别提供响应处理、注册配置、身份验证、权限调用，以及链路追踪、结构化日志与管理端点。Web 运行时由服务自己提供；`spring-boot-starter-security` 由本模块声明，管理端点的 Basic 认证链需要它。

服务之间**不加编译期依赖**，只走 HTTP 调用。

## 部署

见 [服务仓的部署说明](../docs/deployment.md)。
