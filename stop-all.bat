@echo off
chcp 65001 >nul
setlocal EnableExtensions

echo ================================
echo  Stopping syncLingo test stack
echo ================================
echo.

echo [1/5] Stopping frontend and Teams bot...
:: Kill entire process tree by window title so dotnet.exe parent is also killed.
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Teams Bot" >nul 2>nul
taskkill /F /T /FI "WINDOWTITLE eq syncLingo Frontend" >nul 2>nul
:: Fallback: kill any remaining process listening on those ports.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ports = @(5173,3978); " ^
  "$connections = Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue; " ^
  "$processIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique; " ^
  "foreach ($processId in $processIds) { " ^
  "  $process = Get-Process -Id $processId -ErrorAction SilentlyContinue; " ^
  "  if ($process) { Write-Host ('Stopping PID {0} ({1})' -f $process.Id, $process.ProcessName); Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } " ^
  "}"

echo.
echo [2/5] Stopping speaker recognition service (port 7000)...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$connections = Get-NetTCPConnection -LocalPort 7000 -State Listen -ErrorAction SilentlyContinue; " ^
  "$processIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique; " ^
  "foreach ($processId in $processIds) { " ^
  "  $process = Get-Process -Id $processId -ErrorAction SilentlyContinue; " ^
  "  if ($process) { Write-Host ('Stopping PID {0} ({1})' -f $process.Id, $process.ProcessName); Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } " ^
  "}"

echo.
echo [3/5] Stopping ngrok...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Get-Process ngrok -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ('Stopping PID {0} (ngrok)' -f $_.Id); Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }"

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
pause
