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
if not defined VOICE_GENDER_ENABLED set VOICE_GENDER_ENABLED=true
if not defined VOICE_GENDER_MODEL_DIR set VOICE_GENDER_MODEL_DIR=models/wav2vec2-large-robust-6-ft-age-gender

if /I "%VOICE_GENDER_ENABLED%"=="true" (
    python tools\ensure_voice_gender_model.py
    if errorlevel 1 (
        echo [ERROR] Failed to prepare voice gender model.
        exit /b 1
    )
)

uvicorn main:app --host %HOST% --port %PORT%
