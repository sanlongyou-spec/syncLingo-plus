"""
Speaker recognition microservice using SpeechBrain ECAPA-TDNN.
Endpoints: POST /enroll, POST /identify, DELETE /enroll/{name}, GET /health
Embeddings are persisted to disk (embeddings.json) and loaded on startup.
"""
import io
import json
import os
import logging
from pathlib import Path
from typing import Optional

import numpy as np
import torch
import torchaudio
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

try:
    from speechbrain.inference.speaker import SpeakerRecognition
except ImportError:
    from speechbrain.pretrained import SpeakerRecognition  # type: ignore[no-redef]

# Windows: patch SpeechBrain link_with_strategy() to use COPY instead of SYMLINK.
# SYMLINK requires elevated privileges on Windows; COPY works without them.
# Patching link_with_strategy (looked up by name inside fetching.py) is the
# correct level — patching fetch() suffers from self-referential import issues.
if os.name == 'nt':
    import speechbrain.utils.fetching as _sb_fetch_mod
    from speechbrain.utils.fetching import LocalStrategy as _LS
    _orig_link = _sb_fetch_mod.link_with_strategy

    def _win_link_with_strategy(src, dst, strategy):
        if strategy == _LS.SYMLINK:
            strategy = _LS.COPY
        return _orig_link(src, dst, strategy)

    _sb_fetch_mod.link_with_strategy = _win_link_with_strategy

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("speaker-service")

EMBEDDINGS_FILE = Path(os.environ.get("EMBEDDINGS_FILE", "embeddings.json"))
MIN_SCORE = float(os.environ.get("SPEAKER_MIN_SCORE", "0.4"))
# Z-norm 门槛：最佳分数相对其他候选人（impostor cohort）的标准分，越高越严格；
# 只在候选≥3 人时生效，仅用于"拒掉对所有人都像"的模糊匹配，默认偏宽松。
ZNORM_MIN = float(os.environ.get("SPEAKER_ZNORM_MIN", "0.8"))
# 每个人最多保留多少条声纹样本（跨会议追加，超出则丢最旧、保留最新，避免无限增长/漂移）
MAX_EMBEDDINGS_PER_SPEAKER = int(os.environ.get("SPEAKER_MAX_EMBEDDINGS", "20"))
MODEL_SOURCE = os.environ.get("SPEAKER_MODEL_SOURCE", "speechbrain/spkrec-ecapa-voxceleb")
MODEL_SAVEDIR = os.environ.get("SPEAKER_MODEL_SAVEDIR", "pretrained_models/spkrec-ecapa-voxceleb")

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
    global model
    log.info("Loading SpeechBrain model from %s ...", MODEL_SOURCE)
    model = SpeakerRecognition.from_hparams(
        source=MODEL_SOURCE,
        savedir=MODEL_SAVEDIR,
        run_opts={"device": "cpu"},
    )
    log.info("Model loaded.")
    load_embeddings()
    yield


app = FastAPI(title="Speaker Recognition Service", lifespan=lifespan)

# Global state
model: Optional[SpeakerRecognition] = None
# name -> list of embedding arrays (multiple enrollments per person)
embedding_store: dict[str, list[list[float]]] = {}


def load_embeddings():
    global embedding_store
    if EMBEDDINGS_FILE.exists():
        try:
            embedding_store = json.loads(EMBEDDINGS_FILE.read_text(encoding="utf-8"))
            log.info("Loaded embeddings for %d speakers from %s", len(embedding_store), EMBEDDINGS_FILE)
        except Exception as e:
            log.warning("Failed to load embeddings: %s", e)
            embedding_store = {}
    else:
        embedding_store = {}


def save_embeddings():
    try:
        EMBEDDINGS_FILE.write_text(json.dumps(embedding_store, ensure_ascii=False), encoding="utf-8")
    except Exception as e:
        log.error("Failed to save embeddings: %s", e)


def wav_bytes_to_tensor(wav_bytes: bytes) -> torch.Tensor:
    """Load WAV bytes and resample to 16 kHz mono.

    Uses the built-in `wave` module to avoid torchaudio backend dependencies
    (torchcodec, soundfile, sox) that may not be available on all platforms.
    """
    import wave
    with wave.open(io.BytesIO(wav_bytes)) as wf:
        n_channels = wf.getnchannels()
        sample_rate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    waveform = torch.from_numpy(samples.copy())
    if n_channels > 1:
        waveform = waveform.reshape(-1, n_channels).mean(dim=1)
    waveform = waveform.unsqueeze(0)  # (1, n_samples)
    if sample_rate != 16000:
        waveform = torchaudio.functional.resample(waveform, sample_rate, 16000)
    return waveform


def apply_vad(waveform: torch.Tensor) -> torch.Tensor:
    """保留语音帧、去掉静音/非语音（16kHz mono）。VAD 不可用或语音太少时回退原始音频。"""
    if _vad is None:
        return waveform
    samples = waveform.squeeze(0).cpu().numpy()
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
        return waveform
    kept = np.concatenate(kept_frames).astype(np.float32) / 32768.0
    if len(kept) < VAD_MIN_SPEECH_SEC * 16000:
        return waveform  # 语音太少（如全是噪声/回灌），用原始音频更稳
    return torch.from_numpy(kept).unsqueeze(0)


def get_embedding(wav_tensor: torch.Tensor) -> np.ndarray:
    with torch.no_grad():
        emb = model.encode_batch(wav_tensor)
        # shape: (1, 1, emb_dim) -> (emb_dim,)
        return emb.squeeze().cpu().numpy()


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
        wav_tensor = wav_bytes_to_tensor(wav_bytes)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid audio: {e}")

    wav_tensor = apply_vad(wav_tensor)  # 去静音/非语音，注册嵌入更干净
    emb = get_embedding(wav_tensor)
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
    wav_bytes = base64.b64decode(req.audio_base64)
    try:
        wav_tensor = wav_bytes_to_tensor(wav_bytes)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid audio: {e}")

    if not embedding_store:
        return IdentifyResponse(name=None, score=0.0, identified=False)

    wav_tensor = apply_vad(wav_tensor)  # 去静音/非语音，识别更稳
    query_emb = get_embedding(wav_tensor)
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
    if identified:
        log.info("Identified speaker '%s' score=%.4f (runner-up '%s' %.4f, margin %.4f)",
                 best_name, best_score, second_name, second_score, margin)
    else:
        log.info("No confident match, best='%s' score=%.4f threshold=%.4f", best_name, best_score, MIN_SCORE)
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


@app.get("/health")
async def health():
    return {"status": "ok", "speakers": len(embedding_store), "model_loaded": model is not None}


if __name__ == "__main__":
    import uvicorn
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "7000"))
    uvicorn.run("main:app", host=host, port=port)
