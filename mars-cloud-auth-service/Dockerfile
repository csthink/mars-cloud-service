FROM eclipse-temurin:25-jre
WORKDIR /
COPY target/mars-cloud-auth-service.jar /app.jar
EXPOSE 8101 9101
# Set SERVER_ADDRESS to the instance IPv4 address reachable inside the private network.
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-jar", "/app.jar"]
