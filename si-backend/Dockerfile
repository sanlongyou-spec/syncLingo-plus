FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/si-backend-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -jar -Xms256m -Xmx512m -Dspring.profiles.active=prod app.jar 1>&2"]
