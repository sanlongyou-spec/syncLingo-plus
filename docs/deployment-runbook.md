# SyncLingo 生产部署与运维手册（当前真实架构）

> 本文为**当前实际运行**的权威说明，取代 `docs/` 下旧的 VoiceMeeter 系列规划文档（那些方案已废弃，不要再参照）。
> 服务器：阿里云 ECS 雅加达 `i-k1ad41c52p8mnilmr6ba`（4 vCPU / 8 GiB / 100G ESSD PL0），包年包月 + 公网按流量计费。
> 密钥/密码不在本文，见服务器 `/opt/syncLingo/backend.env`、bot `appsettings.json` 及本地 gitignore 的 `DEPLOYMENT-RECORD.md`。

---

## 1. 架构总览（已无 VoiceMeeter）

```
浏览器(HTTPS 443) ──Nginx──┬─ /                → /var/www/si (前端静态, Vite 构建)
                           ├─ /api, /ws, /ws/share-audio → 127.0.0.1:8080 (后端)
                           └─ /bot-api/         → 127.0.0.1:3978/ (Bot, 注意尾斜杠!)
后端(Docker, --network host) → 127.0.0.1:3306(MySQL) / 7000(声纹) / 3978(Bot)
微软 Bot Framework ── ngrok(固定域名) → 127.0.0.1:3978
```

- **音频分发**：TTS/原声经后端 **Opus 编码**(48kHz/24kbps) 走 `/ws/share-audio` 扇出到分享页（WebCodecs 解码播放）。**不再用 VoiceMeeter / 浏览器 setSinkId**。
- **管线**：浏览器采麦 → `/ws/asr` → Azure ConversationTranscriber(ASR+说话人分离) → Google 翻译(+LLM 压缩) → Cartesia TTS → Opus 扇出。
- **声纹**：CAM++ / sherpa-onnx（`speaker-service`，无 torch），把 Azure 的 Guest-N 映射到登记的真人 + 克隆音色。

## 2. 组件与运行方式

| 组件 | 运行方式 | 端口 | 备注 |
|---|---|---|---|
| 后端 si-backend | Docker，`--network host`，`--env-file backend.env` | 8080 | 镜像由**根 Dockerfile** 构建 |
| MySQL si-mysql | Docker `mysql:8.0`，发布 127.0.0.1:3306 | 3306 | 数据卷 `/opt/si-mysql` |
| 声纹 si-speaker | **systemd + venv**：`.venv/bin/uvicorn main:app --port 7000` | 7000 | 见坑 §5.3 |
| Bot si-bot | **systemd + .NET6**：`dotnet bin/Release/net6.0/CallingBotSample.dll` | 3978 | 见坑 §5.2 |
| ngrok si-ngrok | systemd，`--url <固定域名>` → 3978 | - | Bot 公网回调 |
| Nginx | systemd，443/80 | 443 | 反代 + 静态 + WS upgrade |

健康检查：后端 **`/api/health`**（不是 `/health`）。

## 3. 更新/部署流程（标准）

```bash
cd /opt/syncLingo && git pull origin final-version

# 后端(改了 si-backend 才需要)
docker build -t si-backend:latest . && docker rm -f si-backend && \
docker run -d --name si-backend --restart=always --network host --env-file /opt/syncLingo/backend.env si-backend:latest
until curl -sf http://127.0.0.1:8080/api/health >/dev/null; do echo waiting...; sleep 2; done

# 前端(改了 si-frontend 才需要)
cd si-frontend && npm install && npm run build && cp -r dist/* /var/www/si/ && cd ..

# 声纹(改了 speaker-service/main.py 才需要)
systemctl restart si-speaker

# Bot(改了 bot/ 才需要)
cd bot/CallingBotSample && dotnet build -c Release && systemctl restart si-bot && cd /opt/syncLingo
```

## 4. 关键配置

- `backend.env`：所有外部 API key、DB 密码、JWT、`BOT_API_URL`、ASR/翻译/TTS 参数。**env 会覆盖 application.yml 默认值**。
  - `AZURE_ASR_MAX_SEGMENT_*`：**不要设**（留空走 yml 默认 0 = 关闭按字符硬切，防句首丢字）。要启用长度切段才设非 0。
  - `BOT_API_URL`：`--network host` 下必须是 `http://127.0.0.1:3978`（**不能** host.docker.internal）。
- bot `appsettings.json`：仅在服务器 `/opt/syncLingo/bot/CallingBotSample/`（含 MicrosoftAppId/密码、ngrok BotBaseUrl、`BackendBaseUrl=http://localhost:8080`）。**不入 git**。
- 声纹阈值：env `SPEAKER_MIN_SCORE`(默认0.5) / `SPEAKER_MIN_MARGIN`(默认0.10)；不设走代码默认。

## 5. 踩过的坑（重装/排障必读）

**5.1 后端镜像必须用 glibc(jammy)，不能 Alpine**
Azure 语音 SDK 自带原生 .so 按 glibc 编译，Alpine(musl) 加载失败 → WS 一连就断(1006)。根 Dockerfile 用 `maven:3.9-eclipse-temurin-21` 构建 + `eclipse-temurin:21-jre-jammy` 运行 + `libssl3 libasound2`。

**5.2 Bot 走 git 部署，但 appsettings 只在服务器**
`bot/CallingBotSample` 源码已入仓库；`appsettings.json`(含密钥) 已 gitignore，**只存服务器**。更新：`git pull` → `dotnet build -c Release` → `systemctl restart si-bot`。
- bot 崩 `ClientSecretCredential ... null` = appsettings 丢了 → 重建该文件。
- nginx `/bot-api/` 必须 `proxy_pass http://127.0.0.1:3978/;`（**带尾斜杠**才剥前缀，否则 bot 收到 `/bot-api/...` 全 404）。

**5.3 声纹 venv 不入 git，丢了服务起不来**
si-speaker 报 `203/EXEC ... .venv/bin/uvicorn No such file` = venv 没了。重建：
```bash
cd /opt/syncLingo/speaker-service
apt -y install python3-venv python3-dev build-essential
rm -rf .venv && python3 -m venv .venv
.venv/bin/pip install -U pip wheel && .venv/bin/pip install -r requirements.txt
systemctl reset-failed si-speaker && systemctl restart si-speaker
```
模型 `models/campplus_zh.onnx` 已入 git；声纹库 `embeddings.json` 只在服务器(勿丢)。

**5.4 前端 dist 不入 git**
`si-frontend/dist/` 已 gitignore（服务器自行 `npm run build`）。早期它被跟踪导致每次 `git pull` 冲突，已移除。若旧机仍冲突：`git checkout -- si-frontend/dist` 再 pull。

**5.5 声纹准确率**
阈值/margin 只是基础；**登记数据干净才是关键**：每个真人用安静近麦、**不外放译音**的 10–20s 单独登记；别给“Translation/虚拟账号”登记声纹。库脏(同人多名/不同人同名)时先在音色克隆页删干净再重登记。

## 6. 运维巡检

```bash
# 服务状态
docker ps --format 'table {{.Names}}\t{{.Status}}'; systemctl is-active si-speaker si-bot si-ngrok nginx
# 日志
docker logs -f si-backend; journalctl -u si-speaker -f; journalctl -u si-bot -f
# 资源/流量(已装 sysstat/vnstat/nethogs)
sar -u; sar -n DEV          # CPU/网络历史
vnstat; vnstat -h           # 流量(GB)
nethogs eth0                # 实时按进程看带宽
free -h                     # 内存/swap(已加 3G swap)
# 延迟分析
docker logs --since 60m si-backend 2>&1 | /opt/syncLingo/speaker-service/.venv/bin/python /opt/syncLingo/tests/analyze_latency.py
# 备份 DB
docker exec si-mysql mysqldump -uroot -p'<见backend.env>' si_backend > ~/backup_$(date +%F).sql
```

- **公网带宽**：峰值默认 10Mbps，实测一场已打满；80 人需在控制台上调到 15–20Mbps（按流量计费，调峰值不增固定费）。
- **swap**：已加 3G（`/swapfile`，已写 fstab）。
- **成本**：定期查“费用→用量明细”看公网流出流量；**及时释放不用的按量实例**（曾有一台 Windows 按量机空跑扣盘费）。

## 7. 故障速查

| 现象 | 原因 | 处置 |
|---|---|---|
| WS `/ws/asr` 一连就 1006 断 | 后端镜像是 Alpine(musl) | 改 jammy 镜像(§5.1) |
| 开始同传后无反应 | si_user 表空导致 FK 失败 | 插入 admin 用户(id=1) |
| si-speaker `203/EXEC` | venv 丢失 | 重建 venv(§5.3) |
| bot 启动崩 ClientSecret null | appsettings 丢失 | 重建 appsettings(§5.2) |
| `/bot-api/*` 全 404 | nginx 少尾斜杠 | `proxy_pass ...:3978/;`(§5.2) |
| 发言摘要发送失败 | 走了 PDF→SharePoint(未配) | 已改纯文本发送 |
| 句首丢字 | 按字符硬切偏移错位 | MAX_SEGMENT=0(§4) |
| 选某语言听到别语言 | 原声按漂移源语言路由 | 已改按配置源语言(固定时) |
| TTS 怪音/音乐声 | 重采样逐块相位重置 | 已改连续相位 |
| 译文出现 SI_TERM_N | 占位符被翻译改坏 | 已加容错还原+兜底清洗 |
