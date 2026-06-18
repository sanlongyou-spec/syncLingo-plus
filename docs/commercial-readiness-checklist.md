# 商用发布完整性清单

以下按“已补齐到仓库”和“上线前仍需人工确认”区分。

## 已补齐到仓库

- 根目录 `README.md`：当前产品、架构、开发、验证和部署入口。
- `LICENSE`：专有商用许可，避免默认开源误解。
- `SECURITY.md`：漏洞上报、密钥处理和生产安全要求。
- `CONTRIBUTING.md`：变更和验证要求。
- `CHANGELOG.md`：当前发布变更记录。
- `.github/workflows/ci.yml`：后端、前端、Bot、speaker-service 基础 CI。
- `.github/dependabot.yml`：Maven、npm、NuGet、GitHub Actions 依赖更新提醒。
- `deploy/linux/`：Linux env、systemd、Nginx 模板。
- `docs/deployment-runbook.md`：无 ngrok 的生产部署手册。
- `docs/deployment-checklist-aliyun.md`：上线验收清单。
- `bot/CallingBotSample/appsettings.example.json`：Bot 配置模板。
- `.gitignore` / `.dockerignore`：屏蔽密钥、本地模型、构建产物、日志、压缩包和临时音频。

## 上线前必须人工确认

- 公司/项目正式名称、隐私政策 URL、服务条款 URL。
- Teams app manifest 中的 Teams app ID、Azure Bot app ID、域名和图标。
- Azure Bot Messaging endpoint 已设为生产域名 `/api/messages`。
- 所有云服务密钥已生成最小权限版本，并保存在服务器密钥文件或密钥管理服务。
- 曾经暴露在聊天、截图、部署记录里的 token 已全部轮换。
- MySQL 备份和恢复演练完成。
- 日志保留周期、脱敏策略、磁盘告警、CPU/内存/带宽告警已配置。
- 首个管理账号创建和默认密码清理完成。
- 域名 HTTPS 证书自动续期测试通过。
- 真实会议端到端验收通过：同传、分享页、历史录音、Teams 通知、AI 问答、权限边界。

## 不再支持的内容

- ngrok 隧道和 `si-ngrok` systemd。
- 本地 `external/Microsoft-Teams-Samples/...` 作为生产 Bot 源码。
- Teams Bot 独立页面的会议链接填写、自动入会、参会人拉取和 SharePoint PDF 发送链路。
- 会议文件上传后自动生成“AI 总结”的独立页面流程；当前会议文件归入会议模块，只接受 PDF/Word，用于会议资料和问答。
