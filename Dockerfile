FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace/si-backend
COPY si-backend/pom.xml .
COPY si-backend/src ./src
RUN mvn -q -DskipTests package

# Azure Speech SDK needs a glibc-based runtime image; do not switch to Alpine/musl.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Runtime dependencies for Azure Speech SDK.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libssl3 libasound2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/si-backend/target/*.jar app.jar
RUN mkdir -p /app/logs
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar app.jar"]
