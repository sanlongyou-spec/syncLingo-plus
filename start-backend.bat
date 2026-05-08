@echo off
chcp 65001 >nul

echo ===================================
echo  Stopping old Docker container...
echo ===================================
docker stop si-backend 2>nul
docker rm si-backend 2>nul

echo ===================================
echo  Building si-backend project...
echo ===================================
cd /d "%~dp0si-backend"

echo Building Java project with Maven...
call mvn clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] Maven build failed!
    pause
    exit /b 1
)

echo ===================================
echo  Building Docker image...
echo ===================================
cd /d "%~dp0si-backend"
docker build -t si-backend:latest .
if %errorlevel% neq 0 (
    echo [ERROR] Docker build failed!
    pause
    exit /b 1
)

echo ===================================
echo  Starting si-backend container...
echo ===================================
docker run -d --name si-backend -p 8080:8080 ^
    -e SPRING_PROFILES_ACTIVE=prod ^
    -e DB_HOST=host.docker.internal ^
    -e DB_PASSWORD=YOUR_DB_PASSWORD ^
    -e AZURE_SPEECH_KEY=YOUR_AZURE_SPEECH_KEY ^
    -e AZURE_SPEECH_REGION=southeastasia ^
    -e GOOGLE_TRANSLATE_API_KEY=YOUR_GOOGLE_TRANSLATE_API_KEY ^
    -e CARTESIA_API_KEY=YOUR_CARTESIA_API_KEY ^
    -e DASHSCOPE_API_KEY=YOUR_DASHSCOPE_API_KEY ^
    -e COMPRESSION_MODEL=qwen-turbo ^
    si-backend:latest

echo ===================================
echo  Done! Container is starting...
echo ===================================
docker logs si-backend --tail 20
