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
MODEL_SOURCE = os.environ.get("SPEAKER_MODEL_SOURCE", "speechbrain/spkrec-ecapa-voxceleb")
MODEL_SAVEDIR = os.environ.get("SPEAKER_MODEL_SAVEDIR", "pretrained_models/spkrec-ecapa-voxceleb")

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


def best_score_for_speaker(query_emb: np.ndarray, stored_embeddings: list[list[float]]) -> float:
    scores = [cosine_similarity(query_emb, np.array(e)) for e in stored_embeddings]
    return max(scores) if scores else 0.0



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

    emb = get_embedding(wav_tensor)
    name = req.name.strip()
    if name not in embedding_store:
        embedding_store[name] = []
    embedding_store[name].append(emb.tolist())
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
