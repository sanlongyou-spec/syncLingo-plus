@echo off
chcp 65001 >nul
echo ================================
echo  Start Frontend and Backend (Docker Backend)
echo ================================
echo.

REM Check if Docker is running
docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker is not running, please start Docker Desktop
    pause
    exit /b 1
)

REM Start backend (Docker)
echo [1/2] Starting backend (Docker)...
call "%~dp0start-backend.bat"

REM Wait for backend
timeout /t 2 /nobreak >nul

REM Start frontend
echo [2/2] Starting frontend...
start "Frontend" cmd /k "cd /d "%~dp0si-frontend" && npm run dev"

echo.
echo ================================
echo  Services starting...
echo  Frontend: http://localhost:5173
echo  Backend: http://localhost:8080
echo ================================
echo  Check the new window for startup logs
pause
