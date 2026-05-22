# Speaker Recognition Microservice

Self-hosted speaker identification using [SpeechBrain](https://speechbrain.github.io/) ECAPA-TDNN (`speechbrain/spkrec-ecapa-voxceleb`). No external API key required.

## Quick start

### Windows
```bat
cd speaker-service
start.bat
```

### Linux / macOS
```bash
cd speaker-service
chmod +x start.sh
./start.sh
```

The service starts on `http://localhost:7000` by default.  
First run downloads the pretrained model (~80 MB).

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `7000` | HTTP port |
| `HOST` | `0.0.0.0` | Bind address |
| `SPEAKER_MIN_SCORE` | `0.25` | Cosine similarity threshold (0–1) |
| `EMBEDDINGS_FILE` | `embeddings.json` | Where embeddings are persisted |
| `SPEAKER_MODEL_SOURCE` | `speechbrain/spkrec-ecapa-voxceleb` | HuggingFace model ID |

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/enroll` | Register a speaker with a WAV sample |
| `POST` | `/identify` | Identify the speaker in a WAV sample |
| `DELETE` | `/enroll/{name}` | Remove all enrollments for a speaker |
| `GET` | `/health` | Liveness check |

## Backend integration

Enable in `.env`:
```
SPEAKER_SERVICE_ENABLED=true
SPEAKER_SERVICE_URL=http://localhost:7000
SPEAKER_SERVICE_MIN_SCORE=0.25
```

## Accuracy notes

- Real-world accuracy ~75–88% (ECAPA-TDNN on VoxCeleb benchmark: EER ~0.8%)
- Enroll ≥ 10 s of clean speech per person for best results
- Multiple enrollment calls for the same name accumulate embeddings (averaged at identify time)
