@echo off
setlocal

cd /d "%~dp0"

if not exist ".venv" (
    python -m venv .venv
)
call .venv\Scripts\activate.bat
pip install -q -r requirements.txt

if not defined SPEAKER_MIN_SCORE set SPEAKER_MIN_SCORE=0.25
if not defined PORT set PORT=7000
if not defined HOST set HOST=0.0.0.0

uvicorn main:app --host %HOST% --port %PORT%
