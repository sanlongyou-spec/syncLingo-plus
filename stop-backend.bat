@echo off
chcp 65001 >nul
echo Stopping si-backend container...

docker stop si-backend
if %errorlevel% equ 0 (
    echo Container stopped.
) else (
    echo Container was not running.
)

docker rm si-backend
if %errorlevel% equ 0 (
    echo Container removed.
)

echo Done!
