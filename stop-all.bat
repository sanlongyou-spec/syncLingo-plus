@echo off
chcp 65001 >nul
setlocal EnableExtensions

echo ================================
echo  Stopping syncLingo test stack
echo ================================
echo.

:: Close all named CMD windows (this kills the window + its entire child process tree)
echo [1/5] Stopping all syncLingo services...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$titles = @('syncLingo Teams Bot','syncLingo Frontend','syncLingo Speaker Service','syncLingo ngrok'); " ^
  "foreach ($t in $titles) { " ^
  "  $procs = Get-Process | Where-Object { $_.MainWindowTitle -eq $t } -ErrorAction SilentlyContinue; " ^
  "  foreach ($p in $procs) { " ^
  "    Write-Host ('Closing window: ' + $t + ' [PID ' + $p.Id + ']'); " ^
  "    taskkill /F /T /PID $p.Id 2>$null | Out-Null " ^
  "  } " ^
  "}"
:: Fallback: kill by window title string match in taskkill (handles title changes)
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Teams Bot"    >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Frontend"     >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Speaker Service" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo ngrok"        >nul 2>nul
:: Fallback: kill any remaining process on known ports
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ports = @(5173,3978,7000); " ^
  "$pids = Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; " ^
  "foreach ($id in $pids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }; " ^
  "Get-Process ngrok -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue"

echo.
echo [2/5] Speaker service stopped (covered above).
echo.
echo [3/5] ngrok stopped (covered above).

echo.
echo [4/5] Stopping backend container...
docker rm -f si-backend >nul 2>nul
if errorlevel 1 (
    echo Backend container was not running.
) else (
    echo Backend container stopped.
)

echo.
echo [5/5] Done.
echo ================================
echo  Test stack stopped
echo ================================
timeout /t 3 /nobreak >nul
