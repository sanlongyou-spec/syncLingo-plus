FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build
COPY pom.xml .
# 先下载依赖（利用 Docker 层缓存，只有 pom.xml 变动才重新下载）
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

FROM eclipse-temurin:21-jre

# LibreOffice (headless) + CJK fonts — used to convert the export Word into a faithful PDF.
# libreoffice-writer pulls the core needed for docx→pdf; fonts-noto-cjk renders Chinese text.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-writer fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/target/si-backend-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -jar -Xms256m -Xmx512m -Dspring.profiles.active=prod app.jar 1>&2"]
