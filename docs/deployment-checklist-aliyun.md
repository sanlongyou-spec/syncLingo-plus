# SyncLingo 完整部署清单（阿里云雅加达 · Ubuntu 22.04 · 4C8G）

> ⚠️ **部分内容已过时**（bot 路径、nginx /bot-api、Dockerfile、dist 等）。当前真实部署与运维以
> **[`deployment-runbook.md`](./deployment-runbook.md)** 为准；本文仅作初装参考。

> 目标：一台 ECS 上跑起**全部功能** —— 同传 + 分享页音频(Opus) + 会议/纪要 + 声纹识别(新 CAM++/sherpa-onnx) + 术语/热词 + 成本 + RAG 问答 + **Teams Bot**。
> 架构：外部只暴露 443/80/22；Nginx(443) 反代到本机后端(8080)/Bot(3978)；MySQL(3306)/声纹(7000) 仅本机。

---

## 0. 前置
- ECS：4vCPU/8GB、100G ESSD、Ubuntu 22.04、雅加达(ap-southeast-5)、公网IP（按流量 10Mbps）。
- 安全组入方向：**443(0.0.0.0/0)、80(0.0.0.0/0)、22(你的IP)**；不开 8080/3306/7000/3978。
- 域名：一条 A 记录（或子域名）指向 ECS 公网IP（海外免备案）。例：`si.example.com`。
- 准备好密钥：Azure 语音、Google 翻译、Cartesia、OpenRouter(OPENAI)、JWT、DB 密码、Bot Framework AppId/Password、ngrok authtoken。

---

## 1. 基础环境
```bash
ssh root@<EIP>
apt update && apt -y upgrade
# Docker
curl -fsSL https://get.docker.com | sh && systemctl enable --now docker
# Nginx + 证书 + 工具
apt -y install nginx certbot python3-certbot-nginx git curl unzip
# Python(声纹服务) + venv
apt -y install python3 python3-venv python3-pip
# Node(前端构建)
curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && apt -y install nodejs
# .NET(Teams Bot, 版本以 bot .csproj 的 <TargetFramework> 为准, 默认 8)
apt -y install dotnet-sdk-8.0
# ngrok(Bot 公网回调)
curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
echo "deb https://ngrok-agent.s3.amazonaws.com buster main" > /etc/apt/sources.list.d/ngrok.list
apt update && apt -y install ngrok
ngrok config add-authtoken <你的ngrok_authtoken>
# 代码
git clone <你的仓库地址> /opt/syncLingo && cd /opt/syncLingo
```

---

## 2. MySQL（Docker）
```bash
docker run -d --name si-mysql --restart=always \
  -e MYSQL_ROOT_PASSWORD='<DB强密码>' -e MYSQL_DATABASE=si_backend \
  -p 127.0.0.1:3306:3306 -v /opt/si-mysql:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
sleep 25
docker exec -i si-mysql mysql -uroot -p'<DB强密码>' si_backend < si-backend/sql/schema.sql
```

---

## 3. 后端（Spring Boot · Docker）
新建 `/opt/syncLingo/backend.env`（换成真实值）：
```env
SPRING_PROFILES_ACTIVE=prod
APP_PORT=8080
DB_HOST=host.docker.internal
DB_PORT=3306
DB_NAME=si_backend
DB_USERNAME=root
DB_PASSWORD=<DB强密码>
JWT_SECRET=<32位以上随机串>
AZURE_SPEECH_KEY=<...>
AZURE_SPEECH_REGION=southeastasia
GOOGLE_TRANSLATE_API_KEY=<...>
CARTESIA_API_KEY=<...>
OPENAI_API_KEY=<OpenRouter key>
OPENAI_BASE_URL=https://openrouter.ai/api/v1
OPENAI_COMPRESSION_MODEL=anthropic/claude-haiku-4.5
OPENAI_SUMMARY_MODEL=deepseek/deepseek-v4-pro
OPENAI_DOCUMENT_SUMMARY_MODEL=deepseek/deepseek-v4-pro
OPENAI_EMBEDDING_MODEL=openai/text-embedding-3-small
SPEAKER_SERVICE_ENABLED=true
SPEAKER_SERVICE_URL=http://host.docker.internal:7000
BOT_API_URL=http://host.docker.internal:3978
TEAMS_BOT_API_SECRET=<与bot一致的密钥>
ADMIN_API_SECRET=<管理接口密钥>
CORS_ALLOWED_ORIGINS=https://si.example.com
JAVA_OPTS=-Xmx3g
```
构建并运行：
```bash
cd /opt/syncLingo
docker build -t si-backend:latest .
docker run -d --name si-backend --restart=always \
  --add-host=host.docker.internal:host-gateway \
  -p 127.0.0.1:8080:8080 --env-file /opt/syncLingo/backend.env \
  si-backend:latest
curl http://127.0.0.1:8080/health   # {"status":"UP"}
```

---

## 4. 声纹服务（新 CAM++ / sherpa-onnx · 无 torch）
```bash
cd /opt/syncLingo/speaker-service
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt          # 已无 torch, 安装快
# 模型(若仓库未带, 下载 CAM++ ONNX 到 models/)
mkdir -p models && [ -f models/campplus_zh.onnx ] || curl -L -o models/campplus_zh.onnx \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
```
做成 systemd 常驻 `/etc/systemd/system/si-speaker.service`：
```ini
[Unit]
Description=SyncLingo Speaker Service
After=network.target
[Service]
WorkingDirectory=/opt/syncLingo/speaker-service
Environment=PORT=7000 HOST=127.0.0.1 SPEAKER_ONNX_MODEL=models/campplus_zh.onnx
ExecStart=/opt/syncLingo/speaker-service/.venv/bin/uvicorn main:app --host 127.0.0.1 --port 7000
Restart=always
[Install]
WantedBy=multi-user.target
```
```bash
systemctl daemon-reload && systemctl enable --now si-speaker
curl http://127.0.0.1:7000/health   # {"status":"ok",...}
```

---

## 5. Teams Bot（.NET）+ ngrok
```bash
cd /opt/syncLingo/external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample
# 配置 appsettings.json: MicrosoftAppId / MicrosoftAppPassword / 与后端一致的密钥
dotnet build -c Release
```
systemd 跑 Bot `/etc/systemd/system/si-bot.service`：
```ini
[Unit]
Description=SyncLingo Teams Bot
After=network.target
[Service]
WorkingDirectory=/opt/syncLingo/external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample
ExecStart=/usr/bin/dotnet run -c Release --no-build --urls http://127.0.0.1:3978
Restart=always
[Install]
WantedBy=multi-user.target
```
systemd 跑 ngrok（暴露 bot 给微软）`/etc/systemd/system/si-ngrok.service`：
```ini
[Unit]
Description=ngrok for Teams Bot
After=network.target
[Service]
ExecStart=/usr/bin/ngrok http 3978 --url <你的固定ngrok域名>
Restart=always
[Install]
WantedBy=multi-user.target
```
```bash
systemctl daemon-reload && systemctl enable --now si-bot si-ngrok
```
> **Azure 门户**：把 Bot 的"消息端点"设为 `https://<你的ngrok域名>/api/messages`（与代码路由一致）。
> 备选：不用 ngrok，可在 Nginx 443 上反代 `/api/messages` 到 3978，消息端点填 `https://si.example.com/api/messages`。

---

## 6. 前端 + Nginx + HTTPS
```bash
cd /opt/syncLingo/si-frontend
npm install && npm run build
mkdir -p /var/www/si && cp -r dist/* /var/www/si/
```
`/etc/nginx/sites-available/si.conf`：
```nginx
server {
  listen 80;
  server_name si.example.com;
  root /var/www/si; index index.html;
  location / { try_files $uri /index.html; }
  location /api/     { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  location /bot-api/ { proxy_pass http://127.0.0.1:3978; }
  location /ws/ {                       # 同传/分享文本/分享音频 全走这
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
  }
  client_max_body_size 20m;
}
```
```bash
ln -s /etc/nginx/sites-available/si.conf /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
certbot --nginx -d si.example.com      # 自动配 443 + 自动续期
```

---

## 7. 验证（逐功能）
- `https://si.example.com` 打开、登录。
- **同传**：选会议→开始→讲话，出现识别/翻译文本。
- **分享音频**：另一设备(Chrome/Edge)开 `https://si.example.com/#/share/user/<userId>` → 选语言 → 听到音频。
- **声纹**：会中多人说话，日志 `docker logs si-backend | grep speaker` 有识别；`systemctl status si-speaker` 正常。
- **会议/纪要/术语/热词/成本/RAG**：各页面功能可用。
- **Teams Bot**：在 Teams 里 @bot 提问/入会，`systemctl status si-bot si-ngrok` 正常；微软消息端点可达。

---

## 8. 运维
- 日志：`docker logs -f si-backend`；`journalctl -u si-speaker -f`；`journalctl -u si-bot -f`。
- 重启：容器 `--restart=always` 自启；服务 `systemctl restart si-speaker|si-bot|si-ngrok`。
- 备份：`docker exec si-mysql mysqldump -uroot -p'***' si_backend > backup.sql`（定期传 OSS）；声纹库 `speaker-service/embeddings.json`。
- 更新代码：`git pull` → 重建对应组件（后端 `docker build`+换容器；前端 `npm run build`+拷 dist；bot `dotnet build`+重启；声纹改了则重启 si-speaker）。

---

## 资源占用预估（4C8G）
后端JVM ~3G + MySQL ~1G + 声纹(CAM++) ~0.18G + Bot(.NET) ~0.3G + Nginx/系统/ngrok ~0.8G ≈ **~5.3G/8G**，可承载全部功能；想要余量可上 4C16G。

## 安全
- 仅开 443/80/22；8080/3306/7000/3978 不对公网。
- 密钥放 `backend.env`/`appsettings.json` 并 `chmod 600`；SSH 限源IP 或用密钥对。
- `*.pem`(证书私钥) 已在 .gitignore，勿入库。
