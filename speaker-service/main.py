"""
Speaker service — punctuation restoration + sentence boundary detection.
The speaker identification/enrollment endpoints have been removed;
Azure ASR speakerId is used directly without AI-based voiceprint matching.

Endpoints:
  POST /punctuate         — CT-Transformer punctuation restoration (zh/en)
  POST /segment-boundary  — wtpsplit sentence boundary detection (id, en, …)
  GET  /health            — service health check
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
sat_log = logging.getLogger("speaker-service.sat")

# ── 标点还原模型（sherpa-onnx CT-Transformer, 可选）──────────────────────────
PUNCT_MODEL_PATH = os.environ.get(
    "PUNCT_MODEL_PATH",
    "models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12/model.onnx"
)
PUNCT_NUM_THREADS = int(os.environ.get("PUNCT_NUM_THREADS", "1"))

# ── 句边界检测模型（wtpsplit SaT，用于印尼语等无标点还原模型的语言）──────────
# sat-3l 支持 85+ 语言(含 id)，约 100MB；首次启动自动从 HuggingFace 下载
# sat-3l-sm 有 ONNX 导出，配合 ort_providers 无需 torch
# 若需要更高精度可改为 sat-3l（同样支持 ort_providers）
SAT_MODEL_NAME = os.environ.get("SAT_MODEL_NAME", "sat-3l-sm")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global punct_model, sat_model
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
            _ = punct_model.add_punctuation("今天开会讨论预算")
            elapsed_ms = int((time.time() - t0) * 1000)
            log.info("[punct] Model loaded and warmed up from %s, elapsed=%dms", punct_path, elapsed_ms)
        except Exception as e:
            log.warning("[punct] Failed to load punct model: %s — running without punctuation", e)
            punct_model = None
    else:
        log.info("[punct] Model file not found at %s — /punctuate will echo input (degraded)", punct_path)
        punct_model = None

    # ── wtpsplit SaT 模型（可选，import 失败或下载失败时降级）──────────────────
    try:
        from wtpsplit import SaT
        t0 = time.time()
        # ort_providers 指定 ONNX Runtime 后端，无需 torch
        sat_model = SaT(SAT_MODEL_NAME, ort_providers=["CPUExecutionProvider"])
        _ = sat_model.split("This is a sentence. This is another one.", lang_code="en")
        elapsed_ms = int((time.time() - t0) * 1000)
        log.info("[sat] Model loaded and warmed up: %s (ONNX), elapsed=%dms", SAT_MODEL_NAME, elapsed_ms)
    except Exception as e:
        log.warning("[sat] Failed to load SaT model (%s): %s — /segment-boundary will return no boundary", SAT_MODEL_NAME, e)
        sat_model = None

    yield


app = FastAPI(title="Speaker Service", lifespan=lifespan)

# Global state
punct_model = None
sat_model = None


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


class SegmentBoundaryRequest(BaseModel):
    text: str
    lang: str
    safe_end: int  # 在此字符位置（含）之内查找句边界


class SegmentBoundaryResponse(BaseModel):
    boundary: int       # safe_end 范围内第一个句边界的字符位置，-1 表示未找到
    latency_ms: float
    model_available: bool


@app.post("/segment-boundary", response_model=SegmentBoundaryResponse)
async def segment_boundary(req: SegmentBoundaryRequest):
    """
    在 safe_end 字符范围内查找第一个句子边界位置。
    用于印尼语等无标点还原模型的语言的语义分段。
    boundary=-1 表示模型不可用或范围内无句边界。
    """
    text = req.text.strip()
    safe_end = req.safe_end
    lang = req.lang

    if not text or safe_end <= 0:
        return SegmentBoundaryResponse(boundary=-1, latency_ms=0.0, model_available=sat_model is not None)

    if sat_model is None:
        return SegmentBoundaryResponse(boundary=-1, latency_ms=0.0, model_available=False)

    t0 = time.time()
    try:
        sentences = sat_model.split(text, lang_code=lang)
        latency_ms = (time.time() - t0) * 1000

        boundary = -1
        pos = 0
        for sent in sentences[:-1]:  # 最后一句之后不是边界
            pos += len(sent)
            # 消费句间空白
            while pos < len(text) and text[pos] == ' ':
                pos += 1
            if 0 < pos <= safe_end:
                boundary = pos
                break

        sat_log.info("[sat] lang=%s inputLen=%d safeEnd=%d boundary=%d latency=%.1fms",
                     lang, len(text), safe_end, boundary, latency_ms)
        return SegmentBoundaryResponse(boundary=boundary, latency_ms=round(latency_ms, 2), model_available=True)
    except Exception as e:
        latency_ms = (time.time() - t0) * 1000
        sat_log.warning("[sat] inference error after %.1fms: %s", latency_ms, e)
        return SegmentBoundaryResponse(boundary=-1, latency_ms=round(latency_ms, 2), model_available=False)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "punct_model_loaded": punct_model is not None,
        "sat_model_loaded": sat_model is not None,
    }


if __name__ == "__main__":
    import uvicorn
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "7000"))
    uvicorn.run("main:app", host=host, port=port)
