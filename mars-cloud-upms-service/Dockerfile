FROM eclipse-temurin:25-jre
WORKDIR /
COPY target/mars-cloud-upms-service.jar /app.jar
EXPOSE 8102 9102
# 第二个端口是管理端口（业务端口加 1000）：健康探针、指标与日志级别都在它上面，
# 只对内网开放。端口取值由可观测性组件推导并在启动期核验。
# 两个端口都绑定 SERVER_ADDRESS，注册到 Nacos 的也是这个地址。运行时必须设为容器在私网里的地址：
# 默认值 127.0.0.1 在容器外不可达。
# --sun-misc-unsafe-memory-access=allow：nacos-client 3.1.1 内部 shade 的 Guava 调用
# sun.misc.Unsafe，JDK 24 起默认打印弃用警告（JEP 498）。参数写死在 ENTRYPOINT 而不是
# 放进 JAVA_TOOL_OPTIONS，部署时追加自己的 JAVA_TOOL_OPTIONS 不会把它覆盖掉。
# 与 docs/deployment.md 的「JVM 参数」一节保持一致。
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app.jar"]
