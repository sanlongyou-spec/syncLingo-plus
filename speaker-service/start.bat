@echo off
setlocal

cd /d "%~dp0"

set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
set PIP_DISABLE_PIP_VERSION_CHECK=1

if not exist ".venv" (
    python -m venv .venv
)
call .venv\Scripts\activate.bat
python -m pip install -q -r requirements.txt
if errorlevel 1 (
    echo [ERROR] Failed to install speaker-service requirements.
    exit /b 1
)

if /I "%INSTALL_WTPSPLIT%"=="true" (
    python -m pip install -q -r requirements-optional.txt
    if errorlevel 1 (
        echo [WARN] Failed to install optional wtpsplit dependency. /segment-boundary will run degraded.
    )
)

if not defined SPEAKER_MIN_SCORE set SPEAKER_MIN_SCORE=0.4
if not defined PORT set PORT=7000
if not defined HOST set HOST=0.0.0.0

uvicorn main:app --host %HOST% --port %PORT%
