@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT=%~dp0"
set "FRONTEND_DIR=%ROOT%si-frontend"
set "BACKEND_DIR=%ROOT%si-backend"
set "TEAMS_BOT_DIR=%ROOT%bot\CallingBotSample"
set "BACKEND_ENV_FILE=%TEMP%\si-backend-%RANDOM%-%RANDOM%.env"

echo ================================
echo  Starting syncLingo test stack
echo ================================
echo.

where docker >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Docker command not found.
    pause
    exit /b 1
)

:: 等待 Docker Engine 真正就绪（最多 60 秒）
:: docker info 检查的是进程是否存在，但 Linux 引擎可能还在启动中
docker info >nul 2>nul
if errorlevel 1 (
    set "DOCKER_DESKTOP_EXE=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
    if exist "!DOCKER_DESKTOP_EXE!" (
        echo Docker Engine is not ready. Starting Docker Desktop...
        start "" "!DOCKER_DESKTOP_EXE!"
    )
)

:: Wait until Docker Engine is really ready. Docker Desktop can be running
:: while the Linux engine pipe is still unavailable.
set "DOCKER_READY=0"
for /L %%i in (1,1,24) do (
    if "!DOCKER_READY!"=="0" (
        docker info >nul 2>nul
        if not errorlevel 1 (
            set "DOCKER_READY=1"
        ) else (
            echo Waiting for Docker Engine to be ready... (attempt %%i/24^)
            timeout /t 5 /nobreak >nul
        )
    )
)
if "!DOCKER_READY!"=="0" (
    echo [ERROR] Docker Engine is not ready after 120 seconds.
    echo         Please start Docker Desktop and wait for it to fully initialize.
    pause
    exit /b 1
)

where npm >nul 2>nul
if errorlevel 1 (
    echo [ERROR] npm command not found.
    pause
    exit /b 1
)

where dotnet >nul 2>nul
if errorlevel 1 (
    echo [ERROR] dotnet command not found.
    pause
    exit /b 1
)

echo [0/5] Cleaning previous test processes...
:: Kill entire process trees by window title first (releases DLL file locks).
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Teams Bot" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Frontend" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Speaker Service" >nul 2>nul
:: Stop the backend through Docker first. Killing the local 8080 owner can
:: terminate Docker Desktop's port proxy (com.docker.backend).
docker rm -f si-backend >nul 2>nul
:: Fallback: kill any remaining local process on non-Docker service ports.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ports = @(5173,3978,17000); " ^
  "$processIds = Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; " ^
  "foreach ($processId in $processIds) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }"

echo.
echo [1/5] Building Java backend...
cd /d "%BACKEND_DIR%"
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo [ERROR] Maven build failed.
    pause
    exit /b 1
)

echo.
echo [2/5] Building backend Docker image...
docker build -t si-backend:latest .
if errorlevel 1 (
    echo Retrying Docker build in 5 seconds...
    timeout /t 5 /nobreak >nul
    docker build -t si-backend:latest .
    if errorlevel 1 (
        echo [ERROR] Docker build failed.
        echo         Check Docker Desktop is running and has the Linux engine active.
        pause
        exit /b 1
    )
)

echo.
echo [3/5] Starting backend container...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\export-backend-env.ps1" -OutputPath "%BACKEND_ENV_FILE%" -Profile dev
if errorlevel 1 (
    echo [ERROR] Failed to export backend environment from config files.
    if exist "%BACKEND_ENV_FILE%" del "%BACKEND_ENV_FILE%" >nul 2>nul
    pause
    exit /b 1
)
set "TEAMS_BOT_API_SECRET_VALUE="
for /F "tokens=1,* delims==" %%A in ('findstr /B "TEAMS_BOT_API_SECRET=" "%BACKEND_ENV_FILE%" 2^>nul') do set "TEAMS_BOT_API_SECRET_VALUE=%%B"

docker run -d --name si-backend -p 8080:8080 --env-file "%BACKEND_ENV_FILE%" ^
  -e SPRING_PROFILES_ACTIVE=dev ^
  -e APP_HOST=0.0.0.0 ^
  -e SPEAKER_SERVICE_ENABLED=true ^
  -e SPEAKER_SERVICE_URL=http://host.docker.internal:17000 ^
  -e BOT_API_URL=http://host.docker.internal:3978 ^
  si-backend:latest
set "RUN_RESULT=%errorlevel%"
if exist "%BACKEND_ENV_FILE%" del "%BACKEND_ENV_FILE%" >nul 2>nul
if not "%RUN_RESULT%"=="0" (
    echo [ERROR] Docker run failed.
    pause
    exit /b %RUN_RESULT%
)

echo.
echo [4/5] Starting speaker recognition service...
where python >nul 2>nul
if errorlevel 1 (
    echo [SKIP] Python not found, speaker service will not be started.
    echo        To enable: install Python 3.10+, then run: cd speaker-service ^&^& python main.py
) else (
    start "syncLingo Speaker Service" /D "%ROOT%speaker-service" cmd /k "set PORT=17000&& set HOST=0.0.0.0&& call start.bat"
    echo Speaker service starting at http://localhost:17000
)

echo.
echo [5/5] Building and starting frontend and Teams bot...
echo Building Teams bot (dotnet build)...
cd /d "%TEAMS_BOT_DIR%"
dotnet build -c Debug -v quiet
if errorlevel 1 (
    echo [ERROR] Teams bot build failed. Fix compile errors before starting.
    pause
    exit /b 1
)
echo Teams bot build OK.
start "syncLingo Frontend" /D "%FRONTEND_DIR%" cmd /k npm run dev
start "syncLingo Teams Bot" /D "%TEAMS_BOT_DIR%" cmd /k "set Bot__BackendApiSecret=%TEAMS_BOT_API_SECRET_VALUE%&& dotnet run --no-build"

echo.
echo ================================
echo  Services are starting
echo ================================
echo  Frontend:       http://localhost:5173
echo  Backend:        http://localhost:8080
echo  Speaker Svc:    http://localhost:17000
echo  Teams Bot:      http://localhost:3978
echo  Bot public URL: configure your production domain in Azure Bot. No ngrok is started.
echo.
echo  Use stop-all.bat to stop this test stack.
echo ================================
pause
