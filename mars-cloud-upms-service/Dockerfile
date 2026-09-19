FROM eclipse-temurin:25-jre
WORKDIR /
COPY target/mars-cloud-upms-service.jar /app.jar
EXPOSE 8102
ENTRYPOINT ["java", "-jar", "/app.jar"]
