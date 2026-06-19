# syncLingo Plus 生产部署与运维手册

本文是当前唯一有效的生产部署说明。生产环境运行在阿里云 Linux 服务器上，不使用 ngrok。

> **当前生产形态（2026-06-20）**
> - **代码仓库已迁移**：当前在用仓库为 `https://github.com/sanlongyou-spec/syncLingo-plus.git`（git remote `railway-deploy`），分支 `final-version`。旧仓库 `ChrisYou666/simultaneous-interpretation` 已废弃，不要再向其推送/拉取。
> - **生产仍在阿里云服务器**，部署/运维按本文执行。
> - **Railway 仅为备用方案，尚未启用**；其配置见 `docs/railway-deployment.md`，在正式切换前不作为生产路径。

## 1. 架构

```text
Browser HTTPS
  -> Nginx /                 -> /var/www/si
  -> Nginx /api/**, /ws/**   -> Java backend :8080
  -> Nginx /bot-api/**       -> Java backend :8080 -> signed call -> C# Bot :3978
  -> Nginx /api/messages     -> C# Bot :3978

Java backend -> MySQL :3306
Java backend -> speaker-service :7000
Java backend -> C# Bot :3978
```

Azure Bot Messaging endpoint:

```text
https://<your-domain>/api/messages
```

不要再安装或启动 `si-ngrok`。

## 2. 服务器准备

建议 Ubuntu 22.04/24.04，4C8G 起步。公网安全组只开放：

- `80/tcp` and `443/tcp`
- `22/tcp` limited to trusted IPs

安装基础依赖：

```bash
apt update
apt -y install git curl unzip nginx certbot python3-certbot-nginx \
  python3 python3-venv python3-dev build-essential docker.io
systemctl enable --now docker nginx
```

安装 Node.js 18+ 和 .NET 6 SDK，版本以 `si-frontend/package.json` 和 `bot/CallingBotSample/CallingBotSample.csproj` 为准。

后端源码要求 Java 21 编译。服务器宿主机可以安装 Maven + JDK 21；如果宿主机
`javac` 低于 21，必须使用本文中的 Dockerized Maven 构建方式，避免
`release version 21 not supported`。

## 3. 代码与密钥

```bash
git clone <your-repo-url> /opt/syncLingo
cd /opt/syncLingo
cp deploy/linux/env/backend.env.example /opt/syncLingo/backend.env
cp deploy/linux/env/appsettings.Production.example.json \
  /opt/syncLingo/bot/CallingBotSample/appsettings.Production.json
chmod 600 /opt/syncLingo/backend.env /opt/syncLingo/bot/CallingBotSample/appsettings.Production.json
```

替换所有 `REPLACE_WITH_*`、`sync.example.com` 和占位 GUID。

生产必须设置：

- `DB_PASSWORD`
- `JWT_SECRET`
- `ADMIN_API_SECRET`
- `TEAMS_BOT_API_SECRET`
- `SERVICE_SIGNATURE_DOWNSTREAM_KEY`
- `SERVICE_SIGNATURE_UPSTREAM_KEY`
- Azure Speech / Google Translate / Cartesia / OpenAI or OpenRouter keys
- `BOT_API_ALLOWED_USER_IDS`：允许从网页发送 Teams 通知的操作员用户 ID 列表

语音性别检测和全局男/女音色需要设置：

```bash
VOICE_GENDER_SERVICE_ENABLED=true
VOICE_GENDER_SERVICE_URL=http://127.0.0.1:7000
VOICE_GENDER_TIMEOUT_MS=5000
TTS_VOICE_GENDER_ENABLED=true
CARTESIA_GLOBAL_MALE_VOICE_ID=<real-cartesia-male-voice-id>
CARTESIA_GLOBAL_FEMALE_VOICE_ID=<real-cartesia-female-voice-id>
```

`CARTESIA_GLOBAL_MALE_VOICE_ID` 和 `CARTESIA_GLOBAL_FEMALE_VOICE_ID` 必须是真实
Cartesia voice ID，不要保留占位文字。否则后端可以检测性别，但 TTS 会因为没有
可用全局男/女音色而回退。

## 4. MySQL

```bash
docker run -d --name si-mysql --restart=always \
  -e MYSQL_ROOT_PASSWORD='<root-password>' \
  -e MYSQL_DATABASE=si_backend \
  -e MYSQL_USER=sync_lingo \
  -e MYSQL_PASSWORD='<app-password>' \
  -p 127.0.0.1:3306:3306 \
  -v /opt/si-mysql:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

首次启动后由 Flyway/初始化 SQL 创建或迁移表结构；生产迁移前先备份。

## 5. 后端

```bash
cd /opt/syncLingo
docker run --rm \
  -v "$PWD":/workspace \
  -v /root/.m2:/root/.m2 \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -DskipTests package -f si-backend/pom.xml

ls -lh si-backend/target/si-backend-1.0.0.jar

cd /opt/syncLingo/si-backend
docker build -t si-backend:latest .
docker rm -f si-backend 2>/dev/null || true
mkdir -p /opt/syncLingo/audio-records
docker run -d --name si-backend --restart=always --network host \
  --env-file /opt/syncLingo/backend.env \
  -e JAVA_OPTS="-Xms512m -Xmx2g" \
  -v /opt/syncLingo/audio-records:/app/audio-records \
  si-backend:latest

curl -sf http://127.0.0.1:8080/api/health
docker logs --tail 260 si-backend 2>&1 | grep -E 'Started|MeetingService|meeting_url|VoiceGenderIntegration|SpeakerVoiceGenderService|ERROR|Exception'
```

> **必须固定带上 `-v /opt/syncLingo/audio-records:/app/audio-records`**：录音写在容器内 `/app/audio-records`，不挂卷则每次 `docker rm`/重建都会丢失，合并会议录音时会出现 `source recording skipped`。`-Xmx2g`（而非 3g）给 8G 机器留内存余量，避免 OOM。

## 6. Speaker Service

```bash
cd /opt/syncLingo/speaker-service
python3 -m venv .venv
.venv/bin/pip install -U pip wheel
.venv/bin/pip install -r requirements.txt
if [ -f requirements-optional.txt ]; then .venv/bin/pip install -r requirements-optional.txt; fi
if [ -f tools/ensure_voice_gender_model.py ]; then .venv/bin/python tools/ensure_voice_gender_model.py; fi
```

声纹和性别模型放在 `speaker-service/models/`，模型下载产物不入库。

```bash
cp /opt/syncLingo/deploy/linux/systemd/si-speaker.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now si-speaker
curl -sf http://127.0.0.1:7000/health
```

## 7. Teams Bot

```bash
cd /opt/syncLingo/bot/CallingBotSample
dotnet publish -c Release -o /opt/syncLingo/runtime/bot
cp appsettings.Production.json /opt/syncLingo/runtime/bot/appsettings.Production.json
cp /opt/syncLingo/deploy/linux/systemd/si-bot.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now si-bot
systemctl status si-bot --no-pager
```

Teams app manifest is in `bot/CallingBotSample/AppManifest/manifest.json`. Replace:

- Teams app `id`
- Bot `botId`
- `webApplicationInfo.id`
- `validDomains`
- developer URLs

Then upload the app package in Teams Admin Center and set Azure Bot Messaging endpoint to `https://<domain>/api/messages`.

## 8. Frontend and Nginx

```bash
cd /opt/syncLingo/si-frontend
npm ci
npm run build
mkdir -p /var/www/si
rsync -a --delete dist/ /var/www/si/
```

```bash
cp /opt/syncLingo/deploy/linux/nginx/synclingo.conf /etc/nginx/sites-available/synclingo.conf
sed -i 's/sync.example.com/<your-domain>/g' /etc/nginx/sites-available/synclingo.conf
ln -sf /etc/nginx/sites-available/synclingo.conf /etc/nginx/sites-enabled/synclingo.conf
nginx -t
certbot --nginx -d <your-domain>
systemctl reload nginx
```

## 9. Release Update

Before deploying a risky release, prepare or verify a rollback point. The
current production rollback baseline is recorded in
`docs/server-rollback-2026-06-19.md`.

The 2026-06-19 production deployment and the exact fixes applied on the server
are recorded in `docs/server-deployment-2026-06-19.md`.

Preferred release flow:

1. Commit the local release code.
2. Push `final-version` to the Git remote.
3. Pull that exact commit on `/opt/syncLingo`.
4. Rebuild backend, frontend, speaker-service dependencies, and Bot runtime.
5. Reload nginx and verify health/logs.

Local release preparation:

```bash
cd <local-syncLingo-plus>

mvn -q test -f si-backend/pom.xml
cd si-frontend && npm run build && cd ..
cd bot/CallingBotSample && dotnet test ../CallingBotSample.Tests/CallingBotSample.Tests.csproj && cd ../..
cd speaker-service && python -m compileall -q . && cd ..

git status --short
git add -A
git commit -m "final: production release"
git push origin final-version
git rev-parse HEAD
```

Use the printed commit as `NEW_COMMIT` on the server.

Server pre-deploy cleanup:

```bash
cd /opt/syncLingo
BACKUP=/opt/backups/synclingo-rollback-2b3319e-20260615

# Keep production secrets and model/runtime directories. Only reset tracked
# files that would block a fast-forward pull.
cp -a si-frontend/package-lock.json "$BACKUP/server-package-lock.before-new-deploy" 2>/dev/null || true
git restore si-frontend/package-lock.json
rm -f FETCH_HEAD
```

```bash
cd /opt/syncLingo
git fetch --all
git checkout final-version
git pull --ff-only origin final-version
NEW_COMMIT=$(git rev-parse HEAD)
NEW_TAG=$(git rev-parse --short=12 HEAD)

docker run --rm \
  -v "$PWD":/workspace \
  -v /root/.m2:/root/.m2 \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -DskipTests package -f si-backend/pom.xml
ls -lh si-backend/target/si-backend-1.0.0.jar

cd /opt/syncLingo/si-backend
docker build -t si-backend:$NEW_TAG -t si-backend:latest .
docker rm -f si-backend
mkdir -p /opt/syncLingo/audio-records
docker run -d --name si-backend --restart=always --network host \
  --env-file /opt/syncLingo/backend.env \
  -e JAVA_OPTS="-Xms512m -Xmx2g" \
  -v /opt/syncLingo/audio-records:/app/audio-records \
  si-backend:latest

cd /opt/syncLingo/speaker-service
.venv/bin/pip install -r requirements.txt
if [ -f requirements-optional.txt ]; then .venv/bin/pip install -r requirements-optional.txt; fi
if [ -f tools/ensure_voice_gender_model.py ]; then .venv/bin/python tools/ensure_voice_gender_model.py; fi

cd /opt/syncLingo/si-frontend
npm ci && npm run build
mkdir -p /var/www/si
rsync -a --delete dist/ /var/www/si/

cd /opt/syncLingo/bot/CallingBotSample
dotnet publish -c Release -o /opt/syncLingo/runtime/bot
cp appsettings.Production.json /opt/syncLingo/runtime/bot/appsettings.Production.json

mkdir -p /etc/nginx/backup-disabled
find /etc/nginx/sites-enabled -maxdepth 1 -type f -name '*.bak*' -exec mv {} /etc/nginx/backup-disabled/ \;

# The current production server already uses si.conf. Keep one enabled site per
# domain to avoid duplicate server_name warnings and ignored config blocks.
cp /opt/syncLingo/deploy/linux/nginx/synclingo.conf /etc/nginx/sites-available/si.conf
sed -i 's/sync.example.com/julongtongchuan.icu/g' /etc/nginx/sites-available/si.conf
ln -sf /etc/nginx/sites-available/si.conf /etc/nginx/sites-enabled/si.conf
nginx -t

systemctl restart si-speaker si-bot
systemctl reload nginx
curl -sf http://127.0.0.1:8080/api/health
docker logs --tail 260 si-backend 2>&1 | grep -E 'Started|MeetingService|meeting_url|VoiceGenderIntegration|SpeakerVoiceGenderService|ERROR|Exception'
systemctl is-active si-speaker si-bot nginx
docker ps --filter name=si-backend --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
echo "deployed commit=$NEW_COMMIT imageTag=$NEW_TAG"
```

Critical nginx requirement for the current release:

```text
location /bot-api/ { proxy_pass http://127.0.0.1:8080; }
```

Do not proxy browser `/bot-api/**` directly to `127.0.0.1:3978`; the Java
backend must enforce authorization and sign the downstream Bot request.

**所有反代到 8080 的 location（`/api/`、`/ws/`、`/bot-api/`）必须转发 `X-Forwarded-Proto`**，否则后端 `request.getScheme()` 取到内网 `http`，HTTPS 下刷新 token 的 origin 校验会误判为 403（`refresh origin rejected, expected=http://...`）。仓库模板 `deploy/linux/nginx/synclingo.conf` 已含该头；**以模板为准，手改 `si.conf` 时不要漏掉**：

```text
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

后端需配合 `server.forward-headers-strategy=framework`（已在 `application.yml`）。

Do not keep backup files under `/etc/nginx/sites-enabled`; nginx loads them as
active server blocks.

## 10. Operations

Health and status:

```bash
docker ps --filter name=si-backend
curl -sf http://127.0.0.1:8080/api/health
systemctl is-active si-speaker si-bot nginx
```

Logs:

```bash
docker logs -f si-backend
journalctl -u si-speaker -f
journalctl -u si-bot -f
tail -f /var/log/nginx/access.log /var/log/nginx/error.log
```

Voice gender runtime check during a real meeting:

```bash
docker logs -f si-backend 2>&1 | grep -E 'VoiceGenderIntegration|SpeakerVoiceGenderService|detect end|resolveVoiceIdByGender|gender voiceId selected|gender voiceId blank'
```

Expected successful selection examples:

```text
detect end ... accepted=MALE
gender voiceId selected, reason=male ... voiceId=<real-cartesia-male-voice-id>
detect end ... accepted=FEMALE
gender voiceId selected, reason=female ... voiceId=<real-cartesia-female-voice-id>
```

If `gender voiceId blank` appears, the backend is detecting gender but the
global male/female voice IDs are missing or still placeholders in `backend.env`.

If live testing shows `resolveGender ... gender=UNKNOWN` followed by
`schedule skipped ... reason=maxRetries`, collect the raw detection result before
changing thresholds:

```bash
docker logs --since "10 minutes ago" si-backend 2>&1 \
  | grep -E 'VoiceGenderIntegration|detect end|accepted=|timeout|gender voiceId selected|resolveVoiceIdByGender|schedule skipped' \
  | tail -n 260
```

Use the result to distinguish timeout from confidence rejection:

- `timeout ... budgetMs=5000`: the local model response is still too slow.
- `detect end ... rawGender=FEMALE ... accepted=UNKNOWN`: the model returned a
  female score, but the configured confidence/margin thresholds rejected it.
- `gender voiceId selected, reason=female`: the backend selected the global
  female Cartesia voice ID and TTS should use the female voice.

Backups:

```bash
mkdir -p /opt/backups
docker exec si-mysql mysqldump -usync_lingo -p'<app-password>' si_backend \
  > /opt/backups/si_backend_$(date +%F_%H%M%S).sql
```

Also back up `speaker-service/embeddings.json` or any production speaker database files.

### 共享链接（分享令牌）策略

- **有效期 6 小时**：所有共享链接自签发起仅 6 小时内有效，过期自动失效（由后端 `expires_at` 控制）。可用 `SHARE_TOKEN_VALIDITY_HOURS` 覆盖（默认 6）。
- **历史链接已全部失效**：6 小时策略上线后，后端启动时会一次性撤销所有"无过期时间"的旧链接（`ShareTokenSchemaInitializer` → `revokeLegacyTokensWithoutExpiry`）。上线前发出去的旧共享链接一律作废。
- **收听并发上限**：分享音频最多 `SHARE_MAX_AUDIO_CONNECTIONS`（默认 130）路同时连接，超出的新听众会被拒绝并提示"人数已满"，用于防止公网带宽/内存被打满（曾在 ~167 并发时触发整机 OOM）。
- 听众超过该规模前，需先提升公网带宽或改用 CDN 分发音频，详见本节"运维注意"。

## 11. Go-Live Checks

- HTTPS opens the frontend.
- Login, refresh-token, logout, and admin-only screens work.
- Operator can create a meeting, upload only PDF/Word meeting files, start/stop interpretation, and view history.
- Operator can upload a meeting notice, parse the participant list and Teams link, and send notifications.
- Viewer cannot see admin/user-management functions.
- `/bot-api/**` rejects unauthorized users.
- Teams notification result lists successful and failed accounts.
- Azure Bot responds through `https://<domain>/api/messages`.
- Meeting full-session recording appears in history and downloads.
- Backend, speaker, bot, and nginx logs contain no secrets.
