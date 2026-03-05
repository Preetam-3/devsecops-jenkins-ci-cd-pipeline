FROM eclipse-temurin:21

WORKDIR /app

COPY target/devsecops-demo-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
