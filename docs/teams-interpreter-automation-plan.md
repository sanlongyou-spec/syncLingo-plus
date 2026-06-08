# Teams 口译账号自动化 + 一键操作方案（待实现）

- 分支：`final-version`
- 记录时间：2026-06-05
- 状态：**方案设计，后续实现**

---

## 1. 背景与目标
**目标**：装一次软件后，操作员每天 **开应用 → 点「开始」** 即可，尽量消除现在繁琐的人工配置。

**当前痛点（每次开会都要手动做）**：
1. 起一堆后台服务（`start-all.bat` 6 步）。
2. 手动开 VoiceMeeter、手动在浏览器里选三个虚拟声卡。
3. 手动让多个 Teams 口译账号登录、入会、各自选麦克风、关扬声器。
4. 参会账号手动关麦。
5. 登录系统、勾声道、点开始。

最麻烦的集中在 **VoiceMeeter 配置** 和 **Teams 账号 / 音频设备配置**。

---

## 2. 总体方案：服务器集中 + 客户端安装包
| | 内容 | 形态 |
|---|---|---|
| **服务器端**（一套，长期运行） | backend(Spring Boot)+MySQL、speaker-service、C# Teams Bot、前端静态、所有云 API(LLM/ASR/TTS) 出口、PDF(LibreOffice) | `docker-compose` + Caddy/Nginx 域名+TLS，**替代 ngrok** |
| **客户端**（每操作员一份） | Chromium 壳 + VoiceMeeter + 口译账号窗口 + 指向服务器的配置 | Electron 安装包(.msi)（音频/多账号控制选 Electron 优于 Tauri） |

> 服务搬到服务器后，客户端不再起 backend/bot/ngrok/speaker —— `start-all` 的 5 步消失。

### 为什么必须有本地端
浏览器用 `AudioContext` 抓麦克风 → WebSocket `/ws/asr` 推到后端；译文 TTS 用 `setSinkId` 路由到 VoiceMeeter 虚拟声卡 → 当作「麦克风」喂进 Teams。这是操作系统级音频路由，必须在跑 Teams 的本机。**VoiceMeeter 是把 TTS 灌进 Teams 麦克风的桥，换不掉**（即便走 Path 2 也需要它）。

---

## 3. 三声道（必须可按会议选）
- 系统**已支持三声道**：中文 / 印尼语 / 英语（`LANGUAGE_OPTIONS`，`zh-CN`/`id-ID`/`en-US`），分别对应 VoiceMeeter **B1 / B2 / B3**（`VoiceMeeter Input` / `Aux Input` / `VAIO3`）。
- `InterpretationView.tsx` 已有**复选框按会议勾选声道**（`enabledLanguages`，持久化）。**每场会议要求不同，必须保留自由选。**
- **现有缺陷（待修）**：设备就绪判定 `areVoiceMeeterSinksReady` **写死了"中+印"**，没跟着勾选的声道走。
  - 修复：改为遍历 `enabledLanguages`，只绑定 + 只校验被勾选声道对应的 B1/B2/B3；自检报错按勾选动态生成（缺哪条报哪条）。
  - 顺带：声道勾选做成每场会议可存的预设，便于快速切换。
- **此项纯前端、低风险，建议优先做。**

---

## 4. 消除 Teams 人工操作 —— 三条路径（主 + 备）

### 路径 1：脚本操控原生 Teams 桌面应用（不推荐）
- AutoHotkey / UI 自动化模拟点击登录、入会、选设备。
- 脆（Teams 更新即失效）、一台机器跑多账号别扭、微软无官方接口。**淘汰。**

### 路径 2：把口译账号装进自己的应用（Electron 内置浏览器窗口）—— **推荐主路**
每个口译账号 = 一个独立 `persist:` 会话分区的内置窗口，登录态隔离、可持久化。
- 自动入会（程序导航到会议链接 → 自动点「加入」）。
- 因为是自己掌控的网页窗口，可绑麦克风(B1/B2/B3)、关扬声器。

**关键前提（已确认我方可改）**：嵌入式 webview 登录那一刻仍走条件访问。微软官方：**"在 Edge 之外托管的 WebView 不满足『受批准客户端应用』策略"**。
→ 需把这几个口译账号**从"受批准客户端 / 合规设备"条件访问策略中排除**（我方有租户权限，可改）。

**登录策略：用户点一次，长期保持（首选，避免存密码/自动填充的脆与风险）**
- 每账号一个 `persist:interp-zh/id/en` 分区 → cookies + 刷新令牌落盘 → 静默续期、自动恢复登录。
- 首次：用户手动点登录 + MFA + 勾「保持登录(KMSI)」。
- "多久重登一次"由 **Entra 刷新令牌** + **条件访问"登录频率"策略**决定，二者我方可调 → 设很长/不强制 → 重登频率压到极低。
- 会话过期时只对该账号弹「请重新登录」，不卡全局。

**麦克风指向 B1/B2/B3**
- **方法 A（推荐，可靠）**：首次在该账号 Teams 设置 → 设备 → 麦克风选 VoiceMeeter 对应设备，Teams 把偏好存在该 `persist:` 分区 → 之后自动。属一次性手动（和登录一样）。前提：VoiceMeeter 设备名固定。
- **方法 B（可选、较脆）**：preload 脚本包住 `getUserMedia`，强制 `audio.deviceId = { exact: 目标VM设备 }`（先 `enumerateDevices()` 按 label 找 id）。能省掉手动选，但 Teams 更新易失效、属 ToS 灰区 → 只当 A 的补充，POC 验证稳不稳。

**关扬声器（干净、程序化）**
- `webContents.setAudioMuted(true)` 一行静掉该账号窗口输出，Electron 官方 API，可靠。

**Electron 结构要点**
- 每账号 `BrowserWindow({ webPreferences: { partition: 'persist:interp-xx' }})`。
- 预放行媒体权限：`ses.setPermissionRequestHandler` / `setPermissionCheckHandler` 返回 true（免每次弹窗）。
- 自动入会：导航会议链接 → DOM 自动点「加入」。

**路径 2 仍需验证（非拦路，POC 测）**
1. 嵌入式浏览器 User-Agent 是否被 Teams 判「不支持」导致通话降级（必要时设 Edge/Chrome UA）。
2. Teams 网页版在 Electron 里长时间会议的音频稳定性。
3. 微软服务条款对嵌入/自动化网页客户端的态度。

### 路径 3：实时媒体机器人（Real-Time Media Bot，终极正解，工程量最大）
- 云端 bot 直接加入会议、收发/注入音频 → **不再需要口译账号、VoiceMeeter、开关麦克风**。
- 代价：较大重构（微软实时媒体平台 + 云媒体 + bot 认证 + Azure），当前 bot `RealTimeMediaEnabled=False`。
- **根本限制**：一个 Teams 会议所有人听同一路混音，bot 注入大家都听到 → 想"分语言只听某语言"单会议做不到。要分语言需 Teams 自带「语言口译」(Teams Premium) 或每语言独立会议/音轨。
- 作为**长期方向**保留。

---

## 5. 自动化程度对照表（声道以"按勾选"为准）
| 操作 | 自动化程度 | 实现方式 |
|---|---|---|
| 起 backend/bot/ngrok/speaker | ✅ 消除 | 全搬服务器 |
| VoiceMeeter 安装+三声道配置 | 🔜 全自动 | 安装包静默装 + Voicemeeter Remote API / `-l config.xml` |
| 浏览器绑三声卡 | 🔜 全自动 | 复用 label 匹配，启动自动绑（按勾选声道） |
| 勾选本场声道(中/印/英) | 👤 手动（保留，每场自选） | 复选框，已存在 |
| 设备就绪自检/修复 | 🔜 全自动 | 跟随 `enabledLanguages`（待修） |
| 登录系统 / 开始 / 结束 | 🔜 自动登录 + 一键开始/结束 | 前端改造 |
| 口译账号登录 | 👤 首次点一次，之后免登 | 路径 2：`persist:` 分区 |
| 口译账号入会 | 🔜 自动 | 路径 2：导航会议链接 + 自动点加入 |
| 口译账号麦克风 B1/B2/B3 | 👤 首次选一次（A）/ 🔜 程序强制（B，待验证） | 路径 2 |
| 口译账号关扬声器 | 🔜 全自动 | `setAudioMuted(true)` |
| 参会账号关麦 | 👤 手动（软件管不到） | 核对清单提醒 |
| 会议通知 / 机器人入会 / PDF 发送 | ✅ 已实现 | 本期已做 |

---

## 6. 路径 2 的 POC 计划（先 1–2 天小成本验证，别直接全做）
1. Electron 开一个窗口（`persist:` 分区），用**放宽策略后**的口译账号点登录、持久化会话。
2. 程序自动导航到会议链接、自动点「加入」。
3. 把该窗口麦克风设为 VoiceMeeter B1（方法 A），`setAudioMuted(true)` 关扬声器；同时试方法 B 的 preload 开关。
4. 接一段 TTS 进 B1，确认会议里其他人**稳定**听到该账号声音。
- 跑通 → 扩到 3 账号(中/印/英) + 接真实同传 TTS + 按勾选声道动态起窗口。
- 任一步过不去及时止损（回到半自动）。

---

## 7. 落地顺序（每步独立见效）
1. **前端：声道跟随勾选 + 一键开始/结束**（纯前端、低风险、最快见效）。
2. **服务搬服务器**（docker-compose + Caddy）→ 砍掉起服务 5 步。
3. **路径 2 POC** → 验证 Teams 内置窗口可登录/入会/控音频。
4. **Electron 安装包**：VoiceMeeter 自动装/配 + 设默认声卡 + 三个口译账号窗口（持久登录 + 自动入会 + 绑声道 + 静音扬声器）+ 一键开始。
5. （长期可选）路径 3 实时媒体 Bot。

---

## 8. 今日决策与待确认
**已定**：
- 主路走**路径 2**（Electron 内置 Teams 网页窗口）。
- 登录用**"用户点一次 + 长期保持"**，不存密码。
- 麦克风主用**方法 A**（设置选一次、持久化），方法 B 作 POC 备选。
- 关扬声器用 `setAudioMuted(true)`。
- 三声道**必须可按会议勾选**；先修 `areVoiceMeeterSinksReady` 跟随勾选。

**待确认 / 待办**：
- 我方在租户里把口译账号从条件访问"受批准客户端/合规设备"中排除，并把登录频率设长。
- POC 验证嵌入式窗口入会/音频稳定性 + UA 是否被降级。
- VoiceMeeter 预置配置（固定设备名 B1/B2/B3）。

## 参考（微软官方）
- Conditional Access – Require approved client app：https://learn.microsoft.com/en-us/entra/identity/conditional-access/policy-all-users-approved-app-or-app-protection
- Conditional Access blocking compliant device in WebView2：https://learn.microsoft.com/en-us/answers/questions/2224351/conditional-access-policy-blocking-compliant-devic
- MSAL.NET 推荐系统浏览器而非内嵌 webview：https://learn.microsoft.com/en-us/entra/msal/dotnet/acquiring-tokens/using-web-browsers
- 新版 Teams 客户端 / WebView2 架构：https://learn.microsoft.com/en-us/microsoftteams/platform/resources/teams-updates
