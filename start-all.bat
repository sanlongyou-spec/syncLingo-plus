@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "ROOT=%~dp0"
set "FRONTEND_DIR=%ROOT%si-frontend"
set "BACKEND_DIR=%ROOT%si-backend"
set "TEAMS_BOT_DIR=%ROOT%external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample"
set "NGROK_URL=https://auction-uncombed-imply.ngrok-free.dev"
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
set "DOCKER_READY=0"
for /L %%i in (1,1,12) do (
    if "!DOCKER_READY!"=="0" (
        docker info >nul 2>nul
        if not errorlevel 1 (
            set "DOCKER_READY=1"
        ) else (
            echo Waiting for Docker Engine to be ready... (attempt %%i/12^)
            timeout /t 5 /nobreak >nul
        )
    )
)
if "!DOCKER_READY!"=="0" (
    echo [ERROR] Docker Engine is not ready after 60 seconds.
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

where ngrok >nul 2>nul
if errorlevel 1 (
    echo [ERROR] ngrok command not found.
    pause
    exit /b 1
)

echo [0/6] Cleaning previous test processes...
:: Kill entire process trees by window title first (releases DLL file locks).
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Teams Bot" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Frontend" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Speaker Service" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo ngrok" >nul 2>nul
:: Fallback: kill any remaining process on those ports (including 8080 for the backend).
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ports = @(5173,3978,7000,8080); " ^
  "$processIds = Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; " ^
  "foreach ($processId in $processIds) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }; " ^
  "Get-Process ngrok -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; " ^
  "netstat -ano | Select-String ':8080' | Select-String 'LISTENING' | ForEach-Object { ($_ -split '\s+')[-1] } | Where-Object { $_ -match '^\d+$' } | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }"
docker rm -f si-backend >nul 2>nul

echo.
echo [1/6] Building Java backend...
cd /d "%BACKEND_DIR%"
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo [ERROR] Maven build failed.
    pause
    exit /b 1
)

echo.
echo [2/6] Building backend Docker image...
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
echo [3/6] Starting backend container...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\export-backend-env.ps1" -OutputPath "%BACKEND_ENV_FILE%"
if errorlevel 1 (
    echo [ERROR] Failed to export backend environment from config files.
    if exist "%BACKEND_ENV_FILE%" del "%BACKEND_ENV_FILE%" >nul 2>nul
    pause
    exit /b 1
)

docker run -d --name si-backend -p 8080:8080 --env-file "%BACKEND_ENV_FILE%" ^
  -e SPEAKER_SERVICE_ENABLED=true ^
  -e SPEAKER_SERVICE_URL=http://host.docker.internal:7000 ^
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
echo [4/6] Starting speaker recognition service...
where python >nul 2>nul
if errorlevel 1 (
    echo [SKIP] Python not found, speaker service will not be started.
    echo        To enable: install Python 3.10+, then run: cd speaker-service ^&^& python main.py
) else (
    start "syncLingo Speaker Service" /D "%ROOT%speaker-service" cmd /k "python main.py"
    echo Speaker service starting at http://localhost:7000
)

echo.
echo [5/6] Building and starting frontend and Teams bot...
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
start "syncLingo Teams Bot" /D "%TEAMS_BOT_DIR%" cmd /k ""C:\Program Files\dotnet\dotnet.exe" run --no-build"

echo.
echo [6/6] Starting ngrok tunnel...
start "syncLingo ngrok" cmd /k "ngrok http 3978 --url %NGROK_URL%"

echo.
echo ================================
echo  Services are starting
echo ================================
echo  Frontend:       http://localhost:5173
echo  Backend:        http://localhost:8080
echo  Speaker Svc:    http://localhost:7000
echo  Teams Bot:      http://localhost:3978
echo  ngrok:          %NGROK_URL%
echo.
echo  Use stop-all.bat to stop this test stack.
echo ================================
pause
