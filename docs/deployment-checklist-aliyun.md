# 阿里云 ECS 上线检查清单

本文是 `docs/deployment-runbook.md` 的执行清单版本。生产部署不使用 ngrok。

## 1. 基础设施

- [ ] ECS 规格满足当前负载，建议 4C8G 起步。
- [ ] 系统为 Ubuntu 22.04/24.04。
- [ ] 安全组只开放 `80/443`，`22` 仅允许可信 IP。
- [ ] 域名 A 记录指向 ECS 公网 IP。
- [ ] Nginx 已签发 HTTPS 证书。
- [ ] 不安装、不启动 `si-ngrok`。

## 2. 代码与密钥

- [ ] 仓库克隆到 `/opt/syncLingo`。
- [ ] `/opt/syncLingo/backend.env` 从 `deploy/linux/env/backend.env.example` 复制并替换占位值。
- [ ] Bot `appsettings.Production.json` 从模板复制并替换占位值。
- [ ] `backend.env` 与 Bot 配置 `chmod 600`。
- [ ] 没有真实密钥写入 Git。
- [ ] 已轮换曾经暴露过的密钥或 token。
- [ ] `backend.env` 中 `VOICE_GENDER_SERVICE_ENABLED=true`、`VOICE_GENDER_TIMEOUT_MS=3000`、`TTS_VOICE_GENDER_ENABLED=true`。
- [ ] `CARTESIA_GLOBAL_MALE_VOICE_ID` 和 `CARTESIA_GLOBAL_FEMALE_VOICE_ID` 已填真实 Cartesia voice ID，未保留占位文字。

## 3. 数据库

- [ ] MySQL 8 容器只绑定 `127.0.0.1:3306`。
- [ ] 数据卷在 `/opt/si-mysql`。
- [ ] 首次迁移/升级前已备份。
- [ ] 已验证可恢复备份。

## 4. 服务

- [ ] `si-backend` Docker 容器 `--network host` 运行。
- [ ] 后端 jar 使用 Java 21 构建；宿主机 JDK 不满足时使用 `maven:3.9.9-eclipse-temurin-21` Docker 构建。
- [ ] `si-speaker` systemd 运行在 `127.0.0.1:7000`。
- [ ] `si-bot` systemd 运行在 `127.0.0.1:3978`。
- [ ] Nginx 路由：
  - `/api/**` -> Java backend
  - `/ws/**` -> Java backend
  - `/bot-api/**` -> Java backend
  - `/api/messages` -> C# Bot
- [ ] Azure Bot Messaging endpoint 为 `https://<domain>/api/messages`。

## 5. 权限与安全

- [ ] 管理账号只能进入用户管理/安全运维界面。
- [ ] 使用者账号看不到用户管理和安全运维。
- [ ] 只有管理账号可以设置或重置密码。
- [ ] `BOT_API_ALLOWED_USER_IDS` 仅包含允许发送 Teams 通知的操作员。
- [ ] `SERVICE_SIGNATURE_DOWNSTREAM_KEY` 和 `SERVICE_SIGNATURE_UPSTREAM_KEY` 已配置。
- [ ] `/bot-api/**` 未授权访问返回 401/403。

## 6. 功能验收

- [ ] 登录、刷新登录、退出登录正常。
- [ ] 会议创建、会议文件上传只接受 PDF/Word。
- [ ] 会议通知上传后能解析参会名单、Teams 会议链接，并能发送通知。
- [ ] 同传开始/停止、ASR、翻译、TTS、分享页收听正常。
- [ ] 男/女音色按检测结果使用全局男声/女声。
- [ ] 术语路由和术语命中日志正常。
- [ ] 历史记录展示整场会议合并录音。
- [ ] 发送 Teams 通知后显示成功账号和失败账号。
- [ ] AI 问答能引用会议文件/历史记录来源。

## 7. 运维

- [ ] `docker logs si-backend` 无启动错误。
- [ ] `docker logs si-backend` 显示 `meeting_url` 字段检查、`VoiceGenderIntegration enabled=true`、`SpeakerVoiceGenderService enabled=true`。
- [ ] `journalctl -u si-speaker` 无模型加载错误。
- [ ] `journalctl -u si-bot` 无 Graph/Bot Framework 配置错误。
- [ ] 日志滚动和磁盘告警已配置。
- [ ] 数据库和 speaker 数据定期备份。
- [ ] 上线版本记录包含 Git commit、镜像 ID、部署时间和回滚方案。
