"""
Speaker service — punctuation restoration only.
The speaker identification/enrollment endpoints have been removed;
Azure ASR speakerId is used directly without AI-based voiceprint matching.

Endpoints:
  POST /punctuate   — CT-Transformer punctuation restoration
  GET  /health      — service health check
"""
import os
import logging
import time
from pathlib import Path

import sherpa_onnx
from contextlib import asynccontextmanager
from fastapi import FastAPI
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("speaker-service")
punc_log = logging.getLogger("speaker-service.punct")

# ── 标点还原模型（sherpa-onnx CT-Transformer, 可选）──────────────────────────
# 下载：https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models
# 解压后将 model.onnx 放到此路径（或通过环境变量覆盖）
PUNCT_MODEL_PATH = os.environ.get(
    "PUNCT_MODEL_PATH",
    "models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12/model.onnx"
)
PUNCT_NUM_THREADS = int(os.environ.get("PUNCT_NUM_THREADS", "1"))


@asynccontextmanager
async def lifespan(app: FastAPI):
    global punct_model
    # ── 标点模型（可选，文件不存在则降级为不加标点）────────────────────────────
    punct_path = Path(PUNCT_MODEL_PATH)
    if punct_path.exists():
        try:
            t0 = time.time()
            punct_cfg = sherpa_onnx.OfflinePunctuationConfig(
                model=sherpa_onnx.OfflinePunctuationModelConfig(
                    ct_transformer=str(punct_path),
                ),
            )
            punct_model = sherpa_onnx.OfflinePunctuation(punct_cfg)
            # 预热：第一次推理会触发 ONNX 图编译，后续正常
            _ = punct_model.add_punctuation("今天开会讨论预算")
            elapsed_ms = int((time.time() - t0) * 1000)
            log.info("[punct] Model loaded and warmed up from %s, elapsed=%dms", punct_path, elapsed_ms)
        except Exception as e:
            log.warning("[punct] Failed to load punct model: %s — running without punctuation", e)
            punct_model = None
    else:
        log.info("[punct] Model file not found at %s — /punctuate will echo input (degraded)", punct_path)
        punct_model = None

    yield


app = FastAPI(title="Speaker Service (punctuation only)", lifespan=lifespan)

# Global state
punct_model = None


class PunctuateRequest(BaseModel):
    text: str


class PunctuateResponse(BaseModel):
    punctuated: str
    latency_ms: float
    model_available: bool


@app.post("/punctuate", response_model=PunctuateResponse)
async def punctuate(req: PunctuateRequest):
    """
    为中文/中英混合文本添加标点。
    - 模型可用时：CT-Transformer 推理（~5-20ms）
    - 模型不可用时：原文原样返回（降级，model_available=false）
    日志格式便于 analyze 脚本扫描：
      [punct] in=... out=... latency=...ms chars_in=N chars_out=M
    """
    text = req.text.strip()
    if not text:
        return PunctuateResponse(punctuated="", latency_ms=0.0, model_available=punct_model is not None)

    t0 = time.time()
    if punct_model is not None:
        try:
            result = punct_model.add_punctuation(text)
            latency_ms = (time.time() - t0) * 1000
            punc_log.info(
                "[punct] in=%r out=%r latency=%.1fms chars_in=%d chars_out=%d",
                text, result, latency_ms, len(text), len(result)
            )
            return PunctuateResponse(punctuated=result, latency_ms=round(latency_ms, 2), model_available=True)
        except Exception as e:
            latency_ms = (time.time() - t0) * 1000
            punc_log.warning("[punct] inference error after %.1fms: %s — returning original", latency_ms, e)
            return PunctuateResponse(punctuated=text, latency_ms=round(latency_ms, 2), model_available=False)
    else:
        latency_ms = (time.time() - t0) * 1000
        punc_log.debug("[punct] degraded (no model), echoing input, chars=%d", len(text))
        return PunctuateResponse(punctuated=text, latency_ms=round(latency_ms, 2), model_available=False)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "punct_model_loaded": punct_model is not None,
    }


if __name__ == "__main__":
    import uvicorn
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "7000"))
    uvicorn.run("main:app", host=host, port=port)
