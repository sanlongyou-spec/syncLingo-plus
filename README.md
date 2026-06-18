# syncLingo Plus

syncLingo Plus is a commercial simultaneous interpretation and meeting knowledge platform. It combines browser audio capture, Azure ASR diarization, terminology-aware translation, Cartesia TTS, shared-listening audio, speaker identity mapping, meeting files, meeting history, AI Q&A, permission management, audit logging, and Microsoft Teams notifications.

## Current Production Shape

- Frontend: React + Vite static files served by Nginx.
- Backend: Spring Boot 3 / Java 21, packaged as a Docker image from the root `Dockerfile`.
- Database: MySQL 8.
- Speaker service: Python FastAPI service for speaker identity and voice-gender detection.
- Teams Bot: C# Bot Framework service under `bot/CallingBotSample`.
- Public ingress: Linux server + Nginx + HTTPS.
- No ngrok is used in production. Azure Bot Messaging endpoint must point to `https://<domain>/api/messages`.

## Repository Layout

| Path | Purpose |
|---|---|
| `si-backend/` | Java backend, database migrations, authorization, ASR/translation/TTS/RAG integrations |
| `si-frontend/` | React frontend |
| `speaker-service/` | Python speaker recognition, punctuation, segmentation, and voice-gender helper service |
| `bot/CallingBotSample/` | C# Teams Bot service |
| `deploy/linux/` | Production Linux templates for env, systemd, and Nginx |
| `docs/deployment-runbook.md` | Canonical production deployment and operations runbook |
| `docs/commercial-readiness-checklist.md` | Commercial release checklist and remaining manual items |

## Local Development

Prerequisites: Docker Desktop, Java 21/Maven, Node.js 18+, Python 3.10+, and .NET SDK matching the bot project target.

```powershell
.\start-all.bat
```

This starts the local backend container, frontend, speaker service, and C# Teams Bot. It does not start ngrok. For real Teams callbacks, use the production domain and Azure Bot configuration described in `docs/deployment-runbook.md`.

## Verification

```powershell
cd si-backend
mvn test

cd ..\si-frontend
npm run build

cd ..\bot\CallingBotSample
dotnet build CallingBotSample.csproj

cd ..\CallingBotSample.Tests
dotnet test CallingBotSample.Tests.csproj

cd ..\..\speaker-service
python -m compileall -q .
```

UI changes also require real browser verification across the affected workflows.

## Deployment

Start with:

- `docs/deployment-runbook.md`
- `docs/deployment-checklist-aliyun.md`
- `deploy/linux/README.md`

Do not commit `backend.env`, bot `appsettings.json`, database dumps, model downloads, token files, local deployment records, or generated app packages.

## License

This project is proprietary commercial software. See `LICENSE`.
