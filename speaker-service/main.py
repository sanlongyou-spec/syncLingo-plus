"""
Speaker recognition microservice using a pre-exported ONNX speaker-embedding
model (3D-Speaker CAM++) via sherpa-onnx — no torch/speechbrain at runtime.
Endpoints: POST /enroll, POST /identify, DELETE /enroll/{name}, GET /health
           POST /punctuate (optional, requires sherpa-onnx CT-Transformer punct model)
Embeddings are persisted to disk (embeddings.json) and loaded on startup.
"""
import io
import json
import os
import logging
import time
from pathlib import Path
from typing import Optional

import numpy as np
import sherpa_onnx
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("speaker-service")
punc_log = logging.getLogger("speaker-service.punct")

EMBEDDINGS_FILE = Path(os.environ.get("EMBEDDINGS_FILE", "embeddings.json"))
MODEL_TAG_FILE = EMBEDDINGS_FILE.with_suffix(".model")

# ── 标点还原模型（sherpa-onnx CT-Transformer, 可选）──────────────────────────
# 下载：https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models
# 解压后将 model.onnx 放到此路径（或通过环境变量覆盖）
PUNCT_MODEL_PATH = os.environ.get(
    "PUNCT_MODEL_PATH",
    "models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12/model.onnx"
)
PUNCT_NUM_THREADS = int(os.environ.get("PUNCT_NUM_THREADS", "1"))
MIN_SCORE = float(os.environ.get("SPEAKER_MIN_SCORE", "0.5"))
# Z-norm 门槛：最佳分数相对其他候选人（impostor cohort）的标准分，越高越严格；
# 只在候选≥3 人时生效，仅用于"拒掉对所有人都像"的模糊匹配，默认偏宽松。
ZNORM_MIN = float(os.environ.get("SPEAKER_ZNORM_MIN", "0.8"))
# 最小区分度：最佳与次佳的差(margin)。过小说明"对两个人都同样像"(常见于声纹被污染/重复登记)，
# 此时分不清是谁，宁可判 Unknown 也不要乱归到某个人。
MIN_MARGIN = float(os.environ.get("SPEAKER_MIN_MARGIN", "0.10"))
# 每个人最多保留多少条声纹样本（跨会议追加，超出则丢最旧、保留最新，避免无限增长/漂移）
MAX_EMBEDDINGS_PER_SPEAKER = int(os.environ.get("SPEAKER_MAX_EMBEDDINGS", "20"))
# ONNX 声纹模型路径（CAM++ / WeSpeaker 等，sherpa-onnx 自带特征提取，无需 torch）
MODEL_PATH = os.environ.get("SPEAKER_ONNX_MODEL", "models/campplus_zh.onnx")
NUM_THREADS = int(os.environ.get("SPEAKER_ONNX_THREADS", "1"))

# ── VAD（去静音/非语音帧；webrtcvad 不可用时整体降级为不过滤）──────────────
VAD_AGGRESSIVENESS = int(os.environ.get("SPEAKER_VAD_AGGRESSIVENESS", "2"))  # 0~3，越大越激进
VAD_MIN_SPEECH_SEC = float(os.environ.get("SPEAKER_VAD_MIN_SPEECH_SEC", "1.0"))
try:
    import webrtcvad  # type: ignore
    _vad = webrtcvad.Vad(VAD_AGGRESSIVENESS)
except Exception as _vad_err:  # noqa: BLE001
    _vad = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global extractor, punct_model
    log.info("Loading speaker ONNX model from %s ...", MODEL_PATH)
    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=MODEL_PATH, num_threads=NUM_THREADS, provider="cpu", debug=False
    )
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)
    log.info("Model loaded, embedding dim=%d.", extractor.dim)
    load_embeddings()

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


app = FastAPI(title="Speaker Recognition Service", lifespan=lifespan)

# Global state
extractor: Optional["sherpa_onnx.SpeakerEmbeddingExtractor"] = None
punct_model: Optional["sherpa_onnx.OfflinePunctuation"] = None
# name -> list of embedding arrays (multiple enrollments per person)
embedding_store: dict[str, list[list[float]]] = {}


def _model_tag() -> str:
    return os.path.basename(MODEL_PATH)


def load_embeddings():
    """加载声纹库。换模型(tag 不符)时旧 ECAPA 声纹与新模型不兼容 → 备份后从空开始，
    避免跨模型嵌入污染识别；声纹会在会中自动重新累积。"""
    global embedding_store
    if not EMBEDDINGS_FILE.exists():
        embedding_store = {}
        MODEL_TAG_FILE.write_text(_model_tag(), encoding="utf-8")
        return
    prev_tag = MODEL_TAG_FILE.read_text(encoding="utf-8").strip() if MODEL_TAG_FILE.exists() else ""
    if prev_tag != _model_tag():
        import time as _t
        bak = EMBEDDINGS_FILE.with_suffix(f".json.bak.{int(_t.time())}")
        try:
            EMBEDDINGS_FILE.rename(bak)
            log.warning("Model changed (%s -> %s); existing voiceprints are incompatible. "
                        "Backed up to %s and starting empty (will re-enroll during meetings).",
                        prev_tag or "unknown", _model_tag(), bak.name)
        except Exception as e:  # noqa: BLE001
            log.warning("Failed to back up old embeddings: %s", e)
        embedding_store = {}
        MODEL_TAG_FILE.write_text(_model_tag(), encoding="utf-8")
        return
    try:
        embedding_store = json.loads(EMBEDDINGS_FILE.read_text(encoding="utf-8"))
        log.info("Loaded embeddings for %d speakers from %s", len(embedding_store), EMBEDDINGS_FILE)
    except Exception as e:  # noqa: BLE001
        log.warning("Failed to load embeddings: %s", e)
        embedding_store = {}


def save_embeddings():
    try:
        EMBEDDINGS_FILE.write_text(json.dumps(embedding_store, ensure_ascii=False), encoding="utf-8")
        MODEL_TAG_FILE.write_text(_model_tag(), encoding="utf-8")
    except Exception as e:  # noqa: BLE001
        log.error("Failed to save embeddings: %s", e)


def wav_bytes_to_array(wav_bytes: bytes) -> np.ndarray:
    """Load WAV bytes -> float32 mono [n] at 16 kHz (无 torch/torchaudio)。"""
    import wave
    with wave.open(io.BytesIO(wav_bytes)) as wf:
        n_channels = wf.getnchannels()
        sample_rate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if n_channels > 1:
        samples = samples.reshape(-1, n_channels).mean(axis=1)
    if sample_rate != 16000 and samples.size > 0:
        # 简单线性重采样到 16k（链路本就 16k，仅作兼容兜底）
        ratio = 16000 / sample_rate
        out_len = int(round(samples.size * ratio))
        if out_len > 0:
            idx = np.linspace(0, samples.size - 1, out_len)
            lo = np.floor(idx).astype(np.int64)
            hi = np.minimum(lo + 1, samples.size - 1)
            frac = idx - lo
            samples = (samples[lo] * (1 - frac) + samples[hi] * frac).astype(np.float32)
    return np.ascontiguousarray(samples, dtype=np.float32)


def apply_vad(samples: np.ndarray) -> np.ndarray:
    """保留语音帧、去掉静音/非语音（16kHz mono）。VAD 不可用或语音太少时回退原始音频。"""
    if _vad is None or samples.size == 0:
        return samples
    int16 = np.clip(samples * 32768.0, -32768, 32767).astype(np.int16)
    frame_len = 480  # 30ms @ 16kHz
    kept_frames = []
    for start in range(0, len(int16) - frame_len + 1, frame_len):
        frame = int16[start:start + frame_len]
        try:
            if _vad.is_speech(frame.tobytes(), 16000):
                kept_frames.append(frame)
        except Exception:  # noqa: BLE001
            kept_frames.append(frame)  # VAD 出错就保留该帧
    if not kept_frames:
        return samples
    kept = np.concatenate(kept_frames).astype(np.float32) / 32768.0
    if len(kept) < VAD_MIN_SPEECH_SEC * 16000:
        return samples  # 语音太少（如全是噪声/回灌），用原始音频更稳
    return np.ascontiguousarray(kept, dtype=np.float32)


def get_embedding(samples: np.ndarray) -> np.ndarray:
    stream = extractor.create_stream()
    stream.accept_waveform(16000, samples)
    stream.input_finished()
    if not extractor.is_ready(stream):
        raise HTTPException(status_code=400, detail="audio too short for embedding")
    return np.array(extractor.compute(stream), dtype=np.float32)


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    norm_a = np.linalg.norm(a)
    norm_b = np.linalg.norm(b)
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return float(np.dot(a, b) / (norm_a * norm_b))


def _centroid(stored_embeddings: list[list[float]]) -> np.ndarray:
    """L2 归一化的平均嵌入（质心）：多样本去噪，比单条更稳。"""
    arr = np.array(stored_embeddings, dtype=np.float32)
    mean = arr.mean(axis=0)
    norm = np.linalg.norm(mean)
    return mean / norm if norm > 0 else mean


def best_score_for_speaker(query_emb: np.ndarray, stored_embeddings: list[list[float]]) -> float:
    """质心相似度与单样本最高相似度取较大者：多样本时更准，单样本时不退化。"""
    if not stored_embeddings:
        return 0.0
    best_single = max(cosine_similarity(query_emb, np.array(e)) for e in stored_embeddings)
    centroid_score = cosine_similarity(query_emb, _centroid(stored_embeddings))
    return max(best_single, centroid_score)


class EnrollRequest(BaseModel):
    name: str
    audio_base64: str  # base64-encoded WAV bytes


class IdentifyRequest(BaseModel):
    audio_base64: str  # base64-encoded WAV bytes
    candidates: Optional[list[str]] = None  # if set, restrict to these names


class EnrollResponse(BaseModel):
    name: str
    enrollment_count: int


class IdentifyResponse(BaseModel):
    name: Optional[str]
    score: float
    identified: bool
    second_name: Optional[str] = None
    second_score: float = 0.0
    margin: float = 0.0


@app.post("/enroll", response_model=EnrollResponse)
async def enroll(req: EnrollRequest):
    import base64
    if not req.name or not req.name.strip():
        raise HTTPException(status_code=400, detail="name must not be blank")
    wav_bytes = base64.b64decode(req.audio_base64)
    try:
        samples = wav_bytes_to_array(wav_bytes)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"Invalid audio: {e}")

    samples = apply_vad(samples)  # 去静音/非语音，注册嵌入更干净
    emb = get_embedding(samples)
    name = req.name.strip()
    if name not in embedding_store:
        embedding_store[name] = []
    embedding_store[name].append(emb.tolist())
    # 跨会议追加：超出上限则保留最新 N 条（丢最旧）。
    if len(embedding_store[name]) > MAX_EMBEDDINGS_PER_SPEAKER:
        embedding_store[name] = embedding_store[name][-MAX_EMBEDDINGS_PER_SPEAKER:]
    save_embeddings()
    log.info("Enrolled speaker '%s', total enrollments: %d", name, len(embedding_store[name]))
    return EnrollResponse(name=name, enrollment_count=len(embedding_store[name]))


@app.post("/identify", response_model=IdentifyResponse)
async def identify(req: IdentifyRequest):
    import base64
    t0 = time.time()
    wav_bytes = base64.b64decode(req.audio_base64)
    try:
        samples = wav_bytes_to_array(wav_bytes)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"Invalid audio: {e}")

    audio_dur_s = len(samples) / 16000.0
    log.debug("[identify] audio=%.2fs candidates=%d", audio_dur_s, len(req.candidates) if req.candidates else -1)

    if not embedding_store:
        return IdentifyResponse(name=None, score=0.0, identified=False)

    samples = apply_vad(samples)  # 去静音/非语音，识别更稳
    query_emb = get_embedding(samples)
    candidates = req.candidates if req.candidates else list(embedding_store.keys())

    # Rank candidates so we can return the runner-up (for Top-2 margin disambiguation).
    scored = []
    for name in candidates:
        if name not in embedding_store:
            continue
        scored.append((name, best_score_for_speaker(query_emb, embedding_store[name])))
    scored.sort(key=lambda x: x[1], reverse=True)

    best_name = scored[0][0] if scored else None
    best_score = scored[0][1] if scored else -1.0
    second_name = scored[1][0] if len(scored) > 1 else None
    second_score = scored[1][1] if len(scored) > 1 else 0.0
    margin = best_score - second_score if len(scored) > 1 else best_score

    identified = best_score >= MIN_SCORE
    # 分数归一化（AS-norm 简版）：候选≥3 人时，看最佳分相对其余候选的标准分，
    # 拒掉"对所有人都像"的模糊匹配（best 不够突出）。
    if identified and len(scored) >= 3:
        others = np.array([s for _, s in scored[1:]], dtype=np.float32)
        mu = float(others.mean())
        sigma = float(others.std()) + 1e-6
        znorm = (best_score - mu) / sigma
        if znorm < ZNORM_MIN:
            identified = False
            log.info("Rejected by z-norm, best='%s' score=%.4f z=%.4f < %.4f",
                     best_name, best_score, znorm, ZNORM_MIN)
    # 模糊匹配拒绝：最佳与次佳过于接近(margin 小)说明分不清是谁(多见于声纹被污染/重复登记)，判 Unknown
    if identified and second_name is not None and margin < MIN_MARGIN:
        identified = False
        log.info("Rejected by margin, best='%s' %.4f vs '%s' %.4f margin=%.4f < %.4f",
                 best_name, best_score, second_name, second_score, margin, MIN_MARGIN)
    latency_ms = (time.time() - t0) * 1000
    if identified:
        log.info("Identified speaker '%s' score=%.4f (runner-up '%s' %.4f, margin %.4f) latency=%.1fms audio=%.2fs",
                 best_name, best_score, second_name, second_score, margin, latency_ms, audio_dur_s)
    else:
        log.info("No confident match, best='%s' score=%.4f threshold=%.4f latency=%.1fms audio=%.2fs",
                 best_name, best_score, MIN_SCORE, latency_ms, audio_dur_s)
    return IdentifyResponse(
        name=best_name if identified else None,
        score=best_score,
        identified=identified,
        second_name=second_name,
        second_score=second_score,
        margin=margin,
    )


@app.delete("/enroll/{name}")
async def delete_enrollment(name: str):
    if name in embedding_store:
        del embedding_store[name]
        save_embeddings()
        log.info("Deleted enrollment for '%s'", name)
        return {"deleted": True, "name": name}
    return JSONResponse(status_code=404, content={"deleted": False, "name": name, "detail": "Speaker not found"})


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
        "speakers": len(embedding_store),
        "model_loaded": extractor is not None,
        "punct_model_loaded": punct_model is not None,
    }


if __name__ == "__main__":
    import uvicorn
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "7000"))
    uvicorn.run("main:app", host=host, port=port)
