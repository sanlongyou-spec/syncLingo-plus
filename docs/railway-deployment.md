# Railway Railpack Deployment Guide

This project should be deployed to Railway as a monorepo with multiple Railpack services. Do not deploy it as one service.

Railway services:

- `si-backend`: Spring Boot API and WebSocket service.
- `si-frontend`: Vite/React frontend.
- `speaker-service`: FastAPI punctuation and voice-gender service.
- `MySQL`: Railway managed MySQL database.
- Optional `si-bot`: only if the Teams Bot workflow is still required.

Railway's default builder is Railpack. The repository includes per-service `railway.json` and `railpack.json` files so Railway uses Railpack even though legacy Dockerfiles still exist for Linux server deployment.

## GitHub Setup

Repository:

```text
https://github.com/ChrisYou666/syncLingo-plus.git
```

Use the `final-version` branch unless you rename it later.

For each Railway application service:

1. Create service from GitHub repo.
2. Select the same repository and branch.
3. Set the service root directory.
4. Confirm Builder is `Railpack`.
5. Enable GitHub autodeploy.
6. Set watch paths.

Recommended service settings:

| Service | Root Directory | Builder | Watch Paths |
|---|---|---|---|
| `si-backend` | `/si-backend` | Railpack | `/si-backend/**` |
| `si-frontend` | `/si-frontend` | Railpack | `/si-frontend/**` |
| `speaker-service` | `/speaker-service` | Railpack | `/speaker-service/**` |

## 1. Create MySQL

In Railway:

1. New service -> Database -> MySQL.
2. Keep it in the same Railway project as the app services.

The backend should use Railway reference variables instead of copied credentials.

## 2. Deploy `speaker-service`

Create a GitHub service:

- Root Directory: `/speaker-service`
- Builder: Railpack
- Healthcheck Path: `/health`
- Public Networking: off by default

Variables:

```env
PORT=7000
HOST=0.0.0.0
LOG_LEVEL=INFO

SPEAKER_ONNX_MODEL=models/campplus_zh.onnx
PUNCT_MODEL_PATH=/app/models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12/model.onnx

VOICE_GENDER_ENABLED=true
VOICE_GENDER_MODEL_DIR=/app/models/wav2vec2-large-robust-6-ft-age-gender
VOICE_GENDER_MIN_SECONDS=6
VOICE_GENDER_MAX_SECONDS=8
VOICE_GENDER_CONFIDENCE=0.75
VOICE_GENDER_MARGIN=0.15
VOICE_GENDER_NUM_THREADS=1
OMP_NUM_THREADS=1
```

Models:

- `models/campplus_zh.onnx` is tracked in git.
- The punctuation and voice-gender models are not tracked because they are large.
- Attach a Railway volume to `speaker-service` at `/app/models`, then upload or download these directories once.

If the large models are missing, the service can still start, but health will report degraded model availability.

## 3. Deploy `si-backend`

Create a GitHub service:

- Root Directory: `/si-backend`
- Builder: Railpack
- Healthcheck Path: `/api/health`
- Public Networking: on

Core variables:

```env
SPRING_PROFILES_ACTIVE=prod
JAVA_OPTS=-Xms512m -Xmx2g

DB_HOST=${{MySQL.MYSQLHOST}}
DB_PORT=${{MySQL.MYSQLPORT}}
DB_NAME=${{MySQL.MYSQLDATABASE}}
DB_USERNAME=${{MySQL.MYSQLUSER}}
DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}

SPEAKER_SERVICE_ENABLED=true
SPEAKER_SERVICE_URL=http://speaker-service.railway.internal:7000

VOICE_GENDER_SERVICE_ENABLED=true
VOICE_GENDER_SERVICE_URL=http://speaker-service.railway.internal:7000
VOICE_GENDER_TIMEOUT_MS=5000
TTS_VOICE_GENDER_ENABLED=true

AUDIO_RECORD_DIR=/app/audio-records
SHARE_MAX_AUDIO_CONNECTIONS=130
```

Add production secrets from the current server `backend.env`, especially:

- `JWT_SECRET`
- Azure Speech variables
- OpenAI variables
- Cartesia variables and voice IDs
- Teams/Bot secrets if still used

Persistent recordings:

- Attach a Railway volume to `si-backend` at `/app/audio-records`.
- Without this, history recordings can be lost on redeploy.

After deployment, generate a backend public domain.

## 4. Deploy `si-frontend`

Create a GitHub service:

- Root Directory: `/si-frontend`
- Builder: Railpack
- Healthcheck Path: `/`
- Public Networking: on

Variables:

```env
VITE_API_BASE_URL=https://<backend-public-domain>
VITE_WS_BASE_URL=https://<backend-public-domain>
```

`VITE_*` variables are baked into the frontend at build time, so redeploy the frontend after changing them.

After deployment, generate the frontend public domain.

## 5. Set CORS

After the frontend domain exists, update backend variables:

```env
CORS_ALLOWED_ORIGINS=https://<frontend-public-domain>
```

Redeploy `si-backend`.

## Verification

Backend:

```bash
curl -sf https://<backend-public-domain>/api/health
```

Speaker service:

```bash
curl -sf https://<speaker-public-domain>/health
```

If public networking is off for `speaker-service`, check it through Railway logs and backend startup logs instead.

Frontend:

```bash
curl -I https://<frontend-public-domain>
```

End-to-end checks:

- Login works.
- Start interpretation succeeds.
- Share page connects and plays audio.
- Meeting upload/download works.
- History recording download works after the backend volume is attached.

## Common Railpack Notes

- `si-backend/railpack.json` pins Java 21 and adds runtime apt packages for Azure Speech SDK, LibreOffice, and Chinese fonts.
- `speaker-service/railpack.json` pins Python 3.11 and adds runtime libraries for ONNX/audio dependencies.
- `si-frontend/package.json` has a production `start` script using `serve`, because Vite's dev server is not the production server.

