FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY si-backend/pom.xml .
COPY si-backend/src ./src
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
