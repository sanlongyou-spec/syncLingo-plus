@echo off
chcp 65001 >nul
echo ================================
echo  启动前端服务 (Vite + React)
echo ================================
cd /d "%~dp0si-frontend"
echo 当前目录: %cd%
echo 正在启动前端服务...
echo.
npm run dev
