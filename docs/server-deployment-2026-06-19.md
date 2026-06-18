# Server Deployment Record - 2026-06-19

This document records the production deployment that upgraded the Linux server
from the rollback baseline to the current `final-version` release.

## Result

Deployment status: successful.

Production release:

- Repository path: `/opt/syncLingo`
- Branch: `final-version`
- Initial deployed commit: `23a16d1adff050288f474dc7563f5a4db2b11e18`
- Initial commit title: `merge remote final-version baseline`
- Follow-up deployed commit: `c495a2c9d566da078eb41ed75a3e8eeec2ec1fd2`
- Follow-up commit title: `restore meeting notice notification workflow`
- Rollback baseline: `docs/server-rollback-2026-06-19.md`
- Public domain: `https://julongtongchuan.icu`
- Frontend nginx root: `/var/www/si`
- Current enabled nginx site: `/etc/nginx/sites-enabled/si.conf`

Backend image observed after deployment:

```text
imageId=sha256:30694551925ca2c8971fe9e6c1a40e4c66b24c5ee6da29c0c0eb42c144c85481
imageCreated=2026-06-19T01:42:23.941866954+08:00
containerCreated=2026-06-19T01:42:35+08:00
```

## Pre-Deployment Protection

The rollback point was prepared before deployment:

- Git tag: `rollback-2b3319e-20260615`
- Backend image: `si-backend:rollback-2b3319e-20260615`
- Backup directory: `/opt/backups/synclingo-rollback-2b3319e-20260615`
- Database dump: `/opt/backups/synclingo-rollback-2b3319e-20260615/si_backend.sql`
- Database dump size: `84M`
- Frontend backup: `/opt/backups/synclingo-rollback-2b3319e-20260615/frontend-nginx-root`
- Backend environment backup: `/opt/backups/synclingo-rollback-2b3319e-20260615/backend.env`

## Deployment Steps Performed

Local release commit was pushed to GitHub after merging the existing remote
`final-version` baseline. The server then pulled:

```bash
cd /opt/syncLingo
git fetch --all
git checkout final-version
git pull --ff-only origin final-version
git rev-parse HEAD
```

The server reported:

```text
23a16d1adff050288f474dc7563f5a4db2b11e18
```

Backend was rebuilt and restarted from `si-backend:latest`.
Frontend was rebuilt and synced into `/var/www/si`.
Bot runtime was republished into `/opt/syncLingo/runtime/bot`.
Speaker service and Bot service were restarted.

## Production Fixes Applied During Deployment

### Backend Security Environment

The first backend start failed because production security validation rejected
the old environment:

```text
JWT secret still used the factory default.
audio.record.api-secret(TEAMS_BOT_API_SECRET) was not configured.
```

The server environment was fixed without recording secret values:

- Generated a non-default `JWT_SECRET`.
- Generated `TEAMS_BOT_API_SECRET`.
- Generated `SERVICE_SIGNATURE_DOWNSTREAM_KEY`.
- Generated `SERVICE_SIGNATURE_UPSTREAM_KEY`.
- Set `SERVICE_SIGNATURE_UPSTREAM_REQUIRED=true`.
- Synced the Bot appsettings values:
  - `Bot:BackendApiSecret`
  - `Bot:ServiceSignatureDownstreamKey`
  - `Bot:ServiceSignatureUpstreamKey`
  - `Bot:RequireServiceSignatureDownstream=true`
- Copied `bot/CallingBotSample/appsettings.Production.json` to
  `/opt/syncLingo/runtime/bot/appsettings.Production.json`.
- Applied `chmod 600` to secret-bearing config files.

After the fix, backend logs showed:

```text
[SecurityConfigValidator] security config OK, prod=true
Started SiBackendApplication
```

### Nginx Site Cleanup

The server had a backup file inside `/etc/nginx/sites-enabled`, which nginx
loaded as an active site and produced duplicate `server_name` warnings:

```text
/etc/nginx/sites-enabled/si.conf.bak.2026-06-19_014654
```

That file was moved to:

```text
/etc/nginx/backup-disabled/
```

The active site was corrected:

```text
/etc/nginx/sites-enabled/si.conf
/etc/nginx/sites-available/si.conf
```

The current production requirement is:

```text
location /bot-api/ { proxy_pass http://127.0.0.1:8080; }
```

Do not point browser `/bot-api/**` directly to `127.0.0.1:3978/`. Browser
requests must pass through the Java backend for authorization and service
signature forwarding.

After cleanup:

- `nginx -t` passed.
- `nginx -T` showed `/bot-api/` proxying to `8080`.
- No active config still proxied `/bot-api/` to `3978`.
- New nginx reload logs no longer showed duplicate `server_name` warnings.

### Speaker Voice Gender Model

The speaker service was initially healthy but reported:

```json
"voice_gender_model_loaded": false
```

The model files existed:

```text
models/wav2vec2-large-robust-6-ft-age-gender/model.yaml
models/wav2vec2-large-robust-6-ft-age-gender/model.onnx
```

The cause was an old systemd unit without voice-gender environment variables.
The unit was replaced with `deploy/linux/systemd/si-speaker.service`, preserving
the existing drop-in threshold override:

```text
Environment=HOST=127.0.0.1
Environment=PORT=7000
Environment=VOICE_GENDER_ENABLED=true
Environment=VOICE_GENDER_MODEL_DIR=models/wav2vec2-large-robust-6-ft-age-gender
Drop-In: /etc/systemd/system/si-speaker.service.d/thresholds.conf
Environment=SPEAKER_MIN_SCORE=0.6
```

Python dependencies were present:

```text
audonnx OK 1.0.1
onnxruntime OK 1.23.2
numpy OK 2.2.6
```

After restart, logs showed:

```text
[voice-gender] audonnx model loaded from models/wav2vec2-large-robust-6-ft-age-gender
```

Speaker health then returned:

```json
{
  "status": "ok",
  "punct_model_loaded": true,
  "sat_model_loaded": true,
  "voice_gender_model_loaded": true
}
```

### Follow-Up Release: Meeting Notice and Voice Gender Runtime

The follow-up release restored the meeting-notice workflow on the Meetings page
and completed the backend voice-gender runtime configuration.

The server repository was updated to:

```text
c495a2c (HEAD -> final-version, origin/final-version) restore meeting notice notification workflow
```

The first backend rebuild attempt failed because Maven was not installed on the
server. After Maven was installed, the host Java compiler still could not build
the backend:

```text
Fatal error compiling: error: release version 21 not supported
```

The backend requires Java 21. The successful build therefore used Dockerized
Maven with Temurin 21:

```bash
cd /opt/syncLingo
docker run --rm \
  -v "$PWD":/workspace \
  -v /root/.m2:/root/.m2 \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -DskipTests package -f si-backend/pom.xml

ls -lh si-backend/target/si-backend-1.0.0.jar
```

After the jar existed, the backend image was rebuilt from `si-backend/`:

```bash
NEW_COMMIT=$(git rev-parse HEAD)
NEW_TAG=${NEW_COMMIT:0:7}

cd /opt/syncLingo/si-backend
docker build -t si-backend:$NEW_TAG -t si-backend:latest .

docker rm -f si-backend
docker run -d --name si-backend --restart=always --network host \
  --env-file /opt/syncLingo/backend.env \
  -e JAVA_OPTS="-Xms512m -Xmx3g" \
  si-backend:latest
```

The backend restart verified that the meeting-notice persistence column was
present:

```text
[MeetingService] column already exists: meeting_url
```

The voice-gender backend integration was then enabled in
`/opt/syncLingo/backend.env`:

```bash
VOICE_GENDER_SERVICE_ENABLED=true
VOICE_GENDER_SERVICE_URL=http://127.0.0.1:7000
TTS_VOICE_GENDER_ENABLED=true
CARTESIA_GLOBAL_MALE_VOICE_ID=6eb8965c-e295-47bd-a9e4-3eeebb3abcff
CARTESIA_GLOBAL_FEMALE_VOICE_ID=a053f6bc-7df4-40de-96d4-de026bc47ce8
```

The two voice IDs above are the temporary production test IDs used during this
deployment. Replace them with the final chosen Cartesia global male/female
voice IDs before final voice acceptance.

After restarting `si-backend`, the verification output was:

```text
{"code":200,"message":"success","data":{"service":"si-backend","status":"UP"}}
[VoiceGenderIntegration] enabled=true, url=http://127.0.0.1:7000/voice-gender, timeoutMs=1500
[SpeakerVoiceGenderService] enabled=true, minAudioSeconds=6, maxAudioSeconds=8, queueCapacity=32
[MeetingService] column already exists: meeting_url
Started SiBackendApplication
```

Runtime voice-gender verification command for live meeting tests:

```bash
docker logs -f si-backend 2>&1 | grep -E 'VoiceGenderIntegration|SpeakerVoiceGenderService|detect end|resolveVoiceIdByGender|gender voiceId selected|gender voiceId blank'
```

Expected successful selection logs:

```text
detect end ... accepted=MALE
gender voiceId selected, reason=male ... voiceId=<global-male-voice-id>
detect end ... accepted=FEMALE
gender voiceId selected, reason=female ... voiceId=<global-female-voice-id>
```

## Verification Evidence

Code version:

```text
23a16d1adff050288f474dc7563f5a4db2b11e18
23a16d1 (HEAD -> final-version, origin/final-version) merge remote final-version baseline
```

Backend:

```text
curl http://127.0.0.1:8080/api/health
HTTP/1.1 200
{"code":200,"message":"success","data":{"service":"si-backend","status":"UP"}}
```

Services:

```text
si-speaker active
si-bot active
nginx active
```

Frontend:

```text
/var/www/si/index.html
/var/www/si/assets/index-BbeWKTgU.js
/var/www/si/assets/index-CBQ52vml.css
```

Public checks:

```text
curl -I https://julongtongchuan.icu
HTTP/1.1 200 OK

curl -I https://julongtongchuan.icu/api/health
HTTP/1.1 200
```

Nginx:

```text
root /var/www/si;
location /bot-api/ { proxy_pass http://127.0.0.1:8080; }
```

Speaker:

```text
curl http://127.0.0.1:7000/health
"voice_gender_model_loaded": true
```

## Remaining Manual Acceptance

The service deployment and model loading checks passed. The following browser
and real-workflow checks still need manual confirmation after login:

- Admin account enters the admin console.
- Normal user cannot see user management or security operations.
- Meeting creation works.
- Meeting file upload accepts PDF/Word.
- Interpretation starts and ASR/translation/TTS work in a real session.
- Speaker change with at least 6 seconds of audio triggers gender detection and
  routes TTS to the configured global male/female voices.
- History shows the full-session recording.
- Teams notification result lists successful and failed accounts.

## Operational Notes

- The SSL `bad key share` lines seen in nginx error logs were external TLS
  handshake noise and were not the cause of deployment failure.
- Do not keep `.bak` files in `/etc/nginx/sites-enabled`; nginx loads every
  file in that directory as active configuration.
- Current production uses `/etc/nginx/sites-enabled/si.conf`. Future deployment
  scripts must either update this file or disable it before enabling another
  site file for the same domain.
- Secret values must stay only in server config files and must not be copied
  into Git, tickets, chat logs, or deployment records.
