#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [ ! -d ".venv" ]; then
  python3 -m venv .venv
fi
source .venv/bin/activate
pip install -q -r requirements.txt

export SPEAKER_MIN_SCORE="${SPEAKER_MIN_SCORE:-0.25}"
export SPEAKER_MODEL_SOURCE="${SPEAKER_MODEL_SOURCE:-speechbrain/spkrec-ecapa-voxceleb}"
export EMBEDDINGS_FILE="${EMBEDDINGS_FILE:-embeddings.json}"

uvicorn main:app --host "${HOST:-0.0.0.0}" --port "${PORT:-7000}"
