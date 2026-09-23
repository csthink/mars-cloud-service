FROM eclipse-temurin:25-jre
WORKDIR /
COPY target/mars-cloud-monitor.jar /app.jar
EXPOSE 8190 9190
# 第二个端口是管理端口（业务端口加 1000）。本部署物只绑内网地址，
# 两个端口都不对公网开放，访问经跳板转发。
# --sun-misc-unsafe-memory-access=allow：nacos-client 3.1.1 内部 shade 的 Guava 调用
# sun.misc.Unsafe，JDK 24 起默认打印弃用警告（JEP 498）。
# --enable-native-access=ALL-UNNAMED：面板经 Reactor Netty 读取各实例的管理端点，Netty 加载平台原生库
# （Linux 的 epoll）时，JDK 24 起（JEP 472）会打印 restricted method 警告，后续版本会改为拒绝。
# 参数写死在 ENTRYPOINT 而不是放进 JAVA_TOOL_OPTIONS，部署时追加自己的 JAVA_TOOL_OPTIONS
# 不会把它们覆盖掉。与 docs/deployment.md 的「JVM 参数」一节保持一致。
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-jar", "/app.jar"]
