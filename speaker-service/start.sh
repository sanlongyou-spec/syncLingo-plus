#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [ ! -d ".venv" ]; then
  python3 -m venv .venv
fi
source .venv/bin/activate
pip install -q -r requirements.txt

export SPEAKER_MIN_SCORE="${SPEAKER_MIN_SCORE:-0.4}"
export SPEAKER_ONNX_MODEL="${SPEAKER_ONNX_MODEL:-models/campplus_zh.onnx}"
export EMBEDDINGS_FILE="${EMBEDDINGS_FILE:-embeddings.json}"
export PUNCT_MODEL_PATH="${PUNCT_MODEL_PATH:-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12/model.onnx}"
export PUNCT_NUM_THREADS="${PUNCT_NUM_THREADS:-1}"

uvicorn main:app --host "${HOST:-0.0.0.0}" --port "${PORT:-7000}"
