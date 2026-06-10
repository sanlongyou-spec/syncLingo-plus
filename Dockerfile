FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY si-backend/pom.xml .
COPY si-backend/src ./src
RUN mvn clean package -DskipTests

# 运行镜像必须用 glibc 基础镜像(非 Alpine/musl)：
# Azure 语音 SDK(ConversationTranscriber) 自带的原生库 .so 按 glibc 编译，musl 下无法加载(UnsatisfiedLinkError)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Azure Speech SDK 运行期依赖：OpenSSL + ALSA
RUN apt-get update && apt-get install -y --no-install-recommends \
        libssl3 libasound2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
