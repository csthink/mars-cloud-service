FROM eclipse-temurin:25-jre
WORKDIR /
COPY target/mars-cloud-upms-service.jar /app.jar
EXPOSE 8102
# --sun-misc-unsafe-memory-access=allow：nacos-client 3.1.1 内部 shade 的 Guava 调用
# sun.misc.Unsafe，JDK 24 起默认打印弃用警告（JEP 498）。参数写死在 ENTRYPOINT 而不是
# 放进 JAVA_TOOL_OPTIONS，部署时追加自己的 JAVA_TOOL_OPTIONS 不会把它覆盖掉。
# 与 docs/deployment.md 的「JVM 参数」一节保持一致。
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app.jar"]
