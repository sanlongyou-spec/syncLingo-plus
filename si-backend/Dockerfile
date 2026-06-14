FROM eclipse-temurin:21-jre

# LibreOffice (headless) + CJK fonts — used to convert the export Word into a faithful PDF.
# libreoffice-writer pulls the core needed for docx→pdf; fonts-noto-cjk renders Chinese text.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-writer fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/si-backend-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -jar -Xms256m -Xmx512m -Dspring.profiles.active=prod app.jar 1>&2"]
