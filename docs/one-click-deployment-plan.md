# syncLingo-plus 一键化部署方案（服务器集中 + 客户端安装包）

- 分支：`final-version` · 版本：`final`
- 记录时间：2026-06-05

## 目标
装一次软件后，操作员每天 **开应用 → 点「开始」** 即可，不再手动开 VoiceMeeter、不配 Teams 音频、不起一堆服务。

## 现状的繁琐点（对应当前代码）
- 起一堆服务：`start-all.bat` 6 步（backend / bot / ngrok / speaker / 前端）。
- 手动开 VoiceMeeter、手动在浏览器下拉里选三个虚拟声卡 → `InterpretationView.tsx` 的 `applyVoiceMeeterSinks` / `areVoiceMeeterSinksReady` 要选对设备才放行。
- 手动把 Teams 的麦克风 / 扬声器设成 VoiceMeeter。
- 每次选会议、选语言 / 音色再点开始。

## 总体架构（天然两分）
除了「抓 Teams 声音 + 把译文灌回 Teams 麦克风」必须在操作员本机，其余全部可上服务器。

| | 内容 | 部署形态 |
|---|---|---|
| **服务器端**（一套） | backend(Spring Boot)+MySQL、speaker-service、C# Teams Bot、前端静态、所有云 API(LLM/ASR/TTS) 出口、PDF(LibreOffice) | `docker-compose` + Caddy/Nginx 域名 + TLS，**替代 ngrok** |
| **客户端**（每操作员一份） | Chromium 壳 + VoiceMeeter + 指向服务器的配置 | Tauri/Electron 安装包(.msi) |

> 客户端不再跑 backend/bot/ngrok/speaker —— `start-all` 的 5 步直接消失。

### 为什么必须有本地端
`InterpretationView.tsx` + `lib/audioCapture.ts`：浏览器用 `AudioContext` 抓麦克风 → WebSocket `/ws/asr` 推到后端；译文 TTS 用 `setSinkId` 按设备名路由到 **VoiceMeeter Input / Aux / VAIO3**，再由 VoiceMeeter 当作「麦克风」喂进 Teams。这是操作系统级音频路由，服务器做不了，必须在跑 Teams 的那台 Windows 机器上。

## 一次性：安装阶段自动配好（向导走一遍，终身一次）
1. 静默安装 **VoiceMeeter**（随包附带安装器）。
2. 灌入**预置 VoiceMeeter 路由配置**（固定虚拟声卡 + 把 Teams 播放声路由给 app）：用 Voicemeeter Remote API（`VoicemeeterRemote64.dll`）或命令行 `-l config.xml` 加载；设备名对齐代码里的 `VoiceMeeter Input / Aux / VAIO3`。
3. 设 **Windows 默认通信设备 = VoiceMeeter**（`nircmd setdefaultsounddevice` 或 `SoundVolumeView /SetDefault`）→ Teams 选「默认」即自动用上。
4. 预置服务器地址：打包时设 `VITE_API_BASE_URL=https://域名`、`VITE_WS_BASE_URL=wss://域名`（前端已支持，见 `api/constants.ts`）+ 预授麦克风权限。
5. 自检：回环测试确认 Teams 麦克风电平有反应。

## 每天：真正的一键
应用启动后台自动：连服务器(WSS) → **自动绑定三个虚拟声卡**（复用现有 label 匹配）→ 自检并自动修复（VoiceMeeter 没开就拉起 + 加载配置；设备缺失就重载；默认声卡不对就重设）→ 全绿后只剩一个 **「开始同传」**。
- 点「开始」= 自动建会话 + 绑音频 + 推流（语言/音色用持久化默认预设）。
- 点「结束」= 停止 + 自动保存 + 可选自动生成总结 / 发送。

## 需要改动
- **前端**：把「手动选设备才放行」改成「启动自动绑定 + 预检自动修复」；首页收敛成 `开始/结束` 一个主控件 + 一个状态灯。在现有 `InterpretationView.tsx` 上改，非重写。
- **桌面壳**：Tauri（推荐，体积小）/ Electron + 一个本机 helper（调 VoiceMeeter Remote API、设默认声卡、拉起 VoiceMeeter）。
- **打包**：VoiceMeeter 安装器 + 预置配置 + helper + 前端 → 签名 `.msi`。
- **服务器**：`docker-compose`（backend+mysql+speaker+bot+caddy）+ 域名证书；Teams Bot messaging endpoint 改域名（替代 ngrok）。

## 约束 / 注意
- VoiceMeeter 是第三方，只能「随包引导安装 + 脚本自动配置」，不能内联进源码。
- 必须 HTTPS / WSS（`getUserMedia`、`setSinkId` 只在安全上下文工作）。
- 公网部署需 TLS + 真鉴权 + 限流；音频/转写经服务器，敏感会议注意传输加密与存储合规。
- 若公司策略禁改默认声卡，退一步：向导里一次性在 Teams 选 VoiceMeeter（之后 Teams 记住）。
- 并发与成本：后端已按 `userId`/session 隔离；多操作员同时跑实时 ASR/TTS，要评估服务器规格与云 API 并发费用。延迟 ≈ 操作员↔服务器 RTT + 云 ASR/TTS。

## 落地顺序（每步独立见效）
1. **服务搬服务器**（docker-compose + Caddy）→ 砍掉 5 步起服务。
2. **前端自动绑设备 + 一键开始/结束** → 砍掉选设备和多步操作（纯前端、风险低、最快见效）。
3. **Tauri 壳 + VoiceMeeter 自动安装/配置 + 设默认声卡** → 砍掉本机音频配置。

完成后操作员体验：**装一次 → 每天开应用 → 点「开始」**。

## 进阶选项
若想连本地 VoiceMeeter 都去掉，可改造成 **Teams 实时媒体 Bot（RealTimeMedia）**，让 Bot 在云端直接收发会议音频 —— 但这是较大的重构（当前 Bot `RealTimeMediaEnabled=False`），作为后续方向。
