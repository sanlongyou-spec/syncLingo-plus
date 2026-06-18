FROM eclipse-temurin:21-jre

# LibreOffice headless + CJK fonts are used for Word/PDF export rendering.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-writer fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/si-backend-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--Xms256m -Xmx512m} -jar app.jar 1>&2"]
