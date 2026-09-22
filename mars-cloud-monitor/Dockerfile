FROM eclipse-temurin:25-jre
WORKDIR /
COPY target/mars-cloud-monitor.jar /app.jar
EXPOSE 8190 9190
# 第二个端口是管理端口（业务端口加 1000）。本部署物只绑内网地址，
# 两个端口都不对公网开放，访问经跳板转发。
# --sun-misc-unsafe-memory-access=allow：nacos-client 3.1.1 内部 shade 的 Guava 调用
# sun.misc.Unsafe，JDK 24 起默认打印弃用警告（JEP 498）。
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app.jar"]
