# Teams 集成进度与后续计划

> 本文用于持续记录 Microsoft Teams 集成的当前进度、技术选择、后续任务和关键决策。后续只要 Teams 集成有新的配置、验证、阻塞或实现结果，都必须更新本文，而不是只停留在聊天记录中。

## 1. 文档规则

1. 本文是 Teams 集成的唯一持续进度文档。
2. 每次 Teams 集成有新进展，都要更新：
   - `当前状态`
   - `已完成`
   - `下一步`
   - `进度日志`
3. 如果路线选择发生变化，必须同步更新 `方案比较` 和 `当前采用方案`。
4. 如果有真实代码实现或测试验证，再把对应周度优化和测试结论回写到：
   - `docs/optimization-implementation-plan.md`
   - `docs/optimization-validation-test-plan.md`
5. 文档中不得记录明文 secret、token、密码。已泄露的 secret 必须视为失效并及时轮换。

## 2. 当前结论

**2026-05-22 更新：主动消息管道已彻底重写（ContinueConversationAsync + ClaimsIdentity），修复 DLL 文件锁导致旧代码运行的问题，新增会议聊天发消息能力，等待重启后端到端验证。**

当前实际运行方案（同传主线）：

```text
浏览器 getDisplayMedia()（用户选择标签页 / 窗口 / 桌面）
  -> AudioContext 16kHz ScriptProcessor
  -> Float32 → PCM16 → Base64
  -> WebSocket /ws/asr
  -> Java 后端 ASR / 翻译 / 压缩 / TTS
  -> 前端字幕与译文展示
```

已实现的 Teams Bot 能力（待端到端验证）：

```text
前端 TeamsBotView
  -> POST /bot-api/api/meetings/join（Bot 加入 Teams 会议）
  -> GET  /bot-api/api/meetings/participants（获取参会人员 + 显示名称）
  -> POST /bot-api/api/meetings/summary（发送摘要到参会人员 Teams 私聊）
       -> Graph API 自动安装 Bot App 到用户 personal scope（CatalogAppId）
       -> Bot Framework Connector 发送私聊消息
```

Teams 实时媒体后置原因：
- application-hosted media bot 要求 Windows 服务器 + 有效证书 + 固定公网 UDP 端口；
- NAudio WASAPI 采集方案在当前架构下无法稳定工作；
- 浏览器 `getDisplayMedia()` 已在生产验证，满足当前核心需求；
- 实时媒体作为后续增强能力保留，阿里云 ECS 服务器已就绪。

Teams 实时媒体保留目标（后置）：

```text
Teams 会议
  -> Teams Calling Bot（阿里云 ECS Windows 服务器）
  -> .NET Teams Media Bridge
  -> 实时音频 / 当前说话人
  -> 现有 Linux 服务器上的 Java 后端
  -> ASR / 翻译 / 压缩 / TTS / 字幕 / 历史记录
```

## 3. 当前状态

### 3.1 当前所处阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 阶段 0：平台配置准备 | ✅ 已完成 | Entra、Azure Bot、Teams App（meeting bot）、Graph 权限、ngrok、App Validation 基础配置均已完成 |
| 阶段 1：本地 PoC | ✅ 已完成 | Bot 入会、call 生命周期日志均已验证；阿里云 ECS 部署已完成（待实时媒体恢复时使用） |
| **摘要推送（新增）** | **⏳ 待验证** | 管道已完整实现；CatalogAppId、Azure AD 权限、Teams App 均已配置；等待真实用户端到端测试 |
| 阶段 2：媒体桥接服务 | ⏸ 暂停 | application-hosted media 方案暂停；ECS 服务器保留待用 |
| 阶段 3：接入现有同传链路 | ⏸ 暂停 | 依赖阶段 2，随之暂停 |
| 阶段 4：正式部署与产品化 | ⏸ 暂停 | 依赖阶段 2/3，随之暂停 |
| **当前主线替代方案** | 🟢 运行中 | 浏览器 `getDisplayMedia()` → WebSocket → Java 后端 ASR 链路，已在生产验证 |

### 3.2 已完成

截至 `2026-05-21`：

1. 已创建 Microsoft Entra 应用。
2. 已创建 Azure Bot：
   - `synclingo-teams-bot`
3. 已将 Azure Bot 绑定到现有 App Registration。
4. 已为 Bot 添加 `Microsoft Teams Commercial` channel。
5. 已在 App Registration 中配置并完成管理员同意：
   - `Calls.AccessMedia.All`
   - `Calls.JoinGroupCall.All`
6. 已在 Teams Developer Portal 中创建 Teams App：
   - `syncLingo Teams Bot`
7. 已将 Bot 绑定到 Teams App。
8. 已声明 Bot 支持：
   - `Supports audio calls`
   - `Supports video calls`
9. 已决定前后端继续部署在现有 Linux 服务器，Teams 实时媒体桥接单独部署。
10. 已决定开发阶段先使用本机 Windows 电脑，不立即租 Azure Windows VM。
11. 已确认现有 Linux 服务器不能作为 Teams application-hosted media bot 的正式承载环境。
12. 已确认 Teams App 后续不仅用于 Bot 入会，也将作为用户在 Teams 中查询会议记录的正式聊天入口。
13. 已安装 `.NET 6 SDK 6.0.428`。
14. 已安装并配置 `ngrok 3.39.2`。
15. 已下载并成功编译微软官方 calling / meeting bot 示例代码。
16. 已启动本地官方样例服务，并通过 `ngrok` 暴露公网地址。
17. 已在 Azure Bot 中配置：
   - `Messaging endpoint`
   - `Calling webhook`
18. 已完成 Teams App manifest 的关键补齐：
   - `validDomains`
   - `supportsChannelFeatures`
   - `webApplicationInfo.id`
   - `webApplicationInfo.resource`
19. 已完成 Teams Bot SSO 基础配置：
   - `Application ID URI`
   - `access_as_user`
   - Teams desktop/mobile 与 Teams web 授权客户端
20. 已补齐官方样例的最小聊天能力：
   - personal scope 欢迎消息；
   - team scope 安装欢迎消息；
   - `Hi` / `hello` / `help` 基础回复。
21. 已将 bot 身份改为单租户配置，并修复错误的 client secret 导致的回复失败。
22. 已重新完成 App Validation，结果为：
   - `14 Success`
   - `1 Warning`
   - `0 Error`
   - `3 Skipped`
23. 已在微软官方 calling / meeting bot 示例中新增“个人聊天发送 Teams 会议链接后自动入会”的 PoC 路径：
   - 个人聊天消息中自动识别 `https://teams.microsoft.com/l/meetup-join/...` 完整链接；
   - 个人聊天消息中也会识别 `https://teams.microsoft.com/meet/...` 短链接，并尝试自动展开成完整 join link；
   - 从会议链接解析 `ChatInfo` 和 `MeetingInfo`；
   - 直接调用现有 `callService.Create(chatInfo, meetingInfo)` 加入已有会议；
   - 保留原有会议 chat 内 `Join scheduled meeting` 路径。
24. 已通过独立输出目录执行编译验证：
   - `dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj -o .\.tmp-build\callingbot-link-test`
   - 结果：成功，存在 2 个既有 `NU1701` 包兼容 warning。
25. 已将 Bot 精简为"入会 + 获取参会人员 + 发送消息"三项功能，移除所有实时媒体依赖，ARM64 Windows 本机可直接运行（`AnyCPU` + `UseAppHost=false`）。
26. 已实现通过 Graph API 获取参会人员（`displayName` + `email`）并在前端 TeamsBotView 展示。
27. 已在 InterpretationView 侧边栏实现"推送摘要到 Teams"按钮，会话结束后一键将 LLM 摘要发送给默认 Teams 用户。
28. 已修复 Bot 推送静默失败问题：无活跃会议时返回 HTTP 409 + 中文错误，前端展示明确提示。
29. 已将发消息从 Graph API 改为 Bot Framework Connector（`Azure.Identity.ClientSecretCredential` + `ServiceClientCredentials`），解决单租户 token 认证问题和 Graph `Teamwork.Migrate.All` 权限限制。
30. 摘要发送已从会议聊天改为 Teams 用户私聊；`CatalogAppId` 现在用于安装 Bot App 到用户 personal scope。
31. Azure AD 权限已授予管理员同意：`User.Read.All`、`TeamsAppInstallation.ReadWriteSelfForUser.All`。
32. Teams 管理中心 App（原 "syncLingo Teams Bot"）已更新为 **"meeting bot"**，manifest `id` 已修正为 `8d24f60f-f031-43ae-a228-c7089d688b57`，`botId` 保持 `ed32d429...`，版本 `1.0.4`。
33. `appsettings.json` → `Bot:CatalogAppId` 已填入 `99867208-21ac-40ec-b946-3fe2f9659006`，Bot 已重启加载。
34. 将 `ChatService.SendMessageViaChatAsync`（Graph API，需 `Teamwork.Migrate.All`）替换为 `CreateProactiveConversationIdAsync`（`28:` Bot ID + `29:` 用户 ID）+ `SendMessageToConversationAsync`（Bot Framework Connector，租户专属 ServiceURL）。
35. 移除前端"退出会议"按钮和后端 `POST /api/meetings/leave` 接口；Bot 在会议结束或仅剩 Bot 时自动挂断。

### 3.3 尚未完成

#### 待验证（已实现代码，尚未端到端确认）

1. **摘要发送到 Teams 用户私聊**：管道已实现（CatalogAppId、Graph 自动安装、Bot Framework Connector 发消息），尚未用真实参会人员验证收到消息。
2. **参会人员显示名称解析**：`User.Read.All` 已授权，尚未确认前端不再显示裸 AAD GUID。
3. **Teams 个人聊天会议记录查询**：C# `MessageBot` + Java `TeamsBotQueryController` 代码已实现，尚未用真实 Teams 用户做端到端测试（`最近`、`搜索 xxx`、`摘要 <id>` 指令）。

#### 待完成（有明确计划，无阻塞）

4. **call 生命周期日志完善**：当前只能通过 `/callback` 请求间接推断，需在代码中明确打印 `call created / connected / ended`。
5. **client secret 生产前必须轮换**：当前联调用 secret 已在沟通中暴露，上线前必须在 Azure AD 创建新 secret 并更新 `appsettings.json`。

#### 暂停（后置，依赖实时媒体，无明确时间表）

6. **`.NET Teams Media Bridge` 实时媒体桥接开发**：持续接收会议混音 PCM 音频帧、active speaker 识别、视频/屏幕共享流。
7. **Teams 媒体流接入 Java 同传链路**：依赖第 6 项。
8. **Teams 内正式查询能力完善**：会议总结定向发送给全部/部分参会者、按用户权限过滤、`teams_user_binding` 绑定表。
9. **正式生产 Azure Windows VM 部署**：当前阿里云 ECS 已就绪，正式部署待实时媒体恢复时推进。

## 4. 可行方案比较

### 方案 A：直接实时 Bot 主线

路线：

```text
先做 Teams Calling Bot + 实时媒体桥接
再接入现有同传链路
最后补 transcript / 会后能力
```

优点：

- 直接对齐最终目标；
- 最快验证 Bot 入会、实时音频、说话人识别；
- 不会先在非核心目标上投入太多；
- 后续可自然扩展自动录屏、屏幕共享、实时字幕。

缺点：

- 技术难度最高；
- 需要 `.NET` 服务；
- 需要 Windows 环境；
- 本地调试复杂度高于普通 Bot。

适用场景：

- 最终目标明确就是实时同传；
- 愿意先承担较高技术复杂度；
- 不希望先做 transcript 再返工。

当前是否采用：

```text
暂停 — Teams 实时媒体桥接部分暂停，Teams Bot 入会 PoC 已完成
```

边界说明：

- 方案 A 中的”实时音频”指 application-hosted media / real-time media bot 持续接收的会议音频帧。
- 不使用 `Graph recordResponse` / `RecordOperation` 作为实时字幕音频来源；该能力只能保留为入会、提示音、录音操作是否触发的辅助验证。
- 2026-05-20 起，实时音频来源已切换为浏览器 `getDisplayMedia()`，Teams 媒体桥接开发后置。

### 方案 B：Transcript 优先

路线：

```text
先接 Teams transcript
先交付会后纪要 / 待办 / 发言人摘要
后续再做实时 Bot
```

优点：

- 风险低；
- 交付快；
- 对现有系统侵入小；
- 容易先产生业务价值。

缺点：

- 不能解决实时同传；
- 不满足“进入会议直接拿实时音频”的核心目标；
- 后续仍然要重新投入实时 Bot。

适用场景：

- 当前只想做会后总结；
- 对实时能力没有迫切需求；
- 想先验证 Graph 权限和会议数据流。

当前是否采用：

```text
否，保留为后续增强能力
```

### 方案 C：短期沿用浏览器采集，Teams 后置

路线：

```text
继续使用现有浏览器 / VoiceMeeter 链路
暂不做 Bot
后续再接 Teams
```

优点：

- 对当前系统最安全；
- 几乎没有新增平台复杂度；
- 成本最低。

缺点：

- 不能自动入会；
- 不能天然拿到 Teams 参与者和当前说话人；
- 不符合 Notta 式体验目标。

适用场景：

- 只想继续打磨现有同传体验；
- Teams 需求尚未确认。

当前是否采用：

```text
是 — 2026-05-20 起正式切换为此方案（浏览器 getDisplayMedia）
```

## 5. 推荐实施阶段

### 阶段 0：平台配置准备

目标：

- 准备所有 Teams / Azure 侧基础资源。

内容：

1. Entra 应用；
2. Azure Bot；
3. Teams channel；
4. Graph permissions；
5. Teams App manifest；
6. `supportsCalling` / `supportsVideo`；
7. webhook 地址预留。

当前状态：

```text
已完成
```

### 阶段 1：本地 PoC

目标：

- 在本机 Windows 上先跑通最小实时 Bot 能力。

内容：

1. 安装 `.NET 6 SDK`；
2. 安装并配置 tunnel 工具；
3. 运行微软官方 calling / meeting bot 示例；
4. 配置当前 Bot 的：
   - `clientId`
   - `tenantId`
   - `clientSecret`
5. 暴露本地 HTTPS webhook；
6. 回 Azure Bot 配置：
   - `Enable calling`
   - `Webhook (for calling)`
7. 验证：
   - Bot 是否能进入会议；
   - 是否能收到 call 生命周期事件。

当前进度：

- 本机 Windows、`.NET 6 SDK`、`ngrok`、官方样例、Azure Bot endpoint、Teams App manifest、SSO 与 App Validation 已跑通；
- 已通过聊天 bot 基础验证；
- 当前剩余核心验证是 Bot 真正入会，以及 call 生命周期是否可用。

完成标准：

- Bot 可以加入测试会议；
- Teams 侧可以看到 Bot；
- 日志可看到 call connected / call ended；
- 失败时可定位到权限、webhook 或 tenant 策略问题。

### 阶段 2：实时媒体桥接

目标：

- 拿到 Teams 的实时音频、发言人和后续视频 / 屏幕共享数据。

内容：

1. 开发独立 `.NET Teams Media Bridge`；
2. 使用官方 media SDK；
3. 读取：
   - 音频帧；
   - participant；
   - active speaker；
   - dominant speaker；
   - 视频流；
   - 屏幕共享流；
4. 设计 bridge 到 Java 后端的协议；
5. 预留录屏能力。

完成标准：

- 能稳定收到音频帧；
- 能识别当前说话人；
- 能拿到视频 / 屏幕共享流事件；
- 媒体服务与主业务系统边界清晰。

### 阶段 3：接入现有同传链路

目标：

- 复用现有后端能力，不重写已完成链路。

内容：

1. Java 后端新增 Teams 音频接入点；
2. 把 Teams 音频送入现有 ASR；
3. 复用：
   - 翻译；
   - 压缩；
   - TTS；
   - 字幕；
   - 历史记录；
4. 增加：
   - `participantId -> displayName`
   - `speakerId -> participant`
   - Teams session 管理；
   - 前端说话人显示。

完成标准：

- Teams 会议发言可在前端实时显示字幕；
- 当前说话人可见；
- 翻译和 TTS 复用现有能力；
- 断线、退会、重连有清理日志。

### 阶段 4：正式部署与产品化

目标：

- 从本机 PoC 迁到正式生产架构。

内容：

1. 租用 Azure Windows VM；
2. 部署 `.NET Teams Media Bridge`；
3. 配置正式域名、证书、固定公网地址；
4. 把现有 Linux 服务器继续作为：
   - 前端；
   - Java 后端；
   - 数据库；
5. 增加：
   - 自动录屏；
   - 会后 transcript；
   - 自动纪要；
   - 监控告警；
   - 多会议并发；
   - 合规提示和录制状态。

完成标准：

- 正式会议可稳定运行；
- 关键链路有监控；
- 自动录屏、纪要和历史记录形成闭环；
- 生产部署符合官方支持形态。

## 6. 当前架构选择

### 6.1 当前实际架构（已落地）

```text
阿里云 ECS Windows Server（印度尼西亚雅加达）
  公网 IP：8.215.69.56
  规格：ecs.c7a.xlarge（4 vCPU / 8 GiB）
  - CallingBotSample（.NET Teams Media Bot）
  - ngrok（信令隧道，固定域名 auction-uncombed-imply.ngrok-free.dev）
  - 媒体端点：UDP 8445 直接公网可达

Linux 服务器 47.237.214.209
  - 现有前端
  - 现有 Java 后端
```

### 6.2 为什么必须用服务器而不能用本机

Teams application-hosted media 的工作方式：微软云服务器主动连入 Bot 的 UDP 端口发送音频帧。本机在共享 WiFi 路由器后面，无法被外部主动访问，Teams 无法连到本机的 UDP 8445。

| 条件 | 本机（共享 WiFi）| 阿里云 ECS |
|---|---|---|
| 公网 IP 控制权 | 无（路由器属于网络管理员）| 有（直接绑定） |
| Teams 能连到 UDP 8445 | 不能 | 能 |
| 可用性 | 依赖本机开机 | 随时可用 |

如果将来使用独立宽带且能控制路由器，可以通过端口转发在本机运行，但服务器方案更稳定。

### 6.3 不采用的架构

```text
Linux 宿主机中再运行 Windows 虚拟机作为正式媒体 Bot
```

### 6.3 不采用的架构

```text
Linux 宿主机中再运行 Windows 虚拟机作为正式媒体 Bot
```

原因：

- 不属于官方推荐生产形态；
- 网络、证书、排障和性能复杂度更高；
- 不利于后续获取官方支持。

## 7. 关键技术决策

| 决策 | 当前选择 | 原因 |
|---|---|---|
| **实时音频来源（当前）** | **浏览器 `getDisplayMedia()`** | 无需额外部署，跨平台，已在生产验证；Teams 媒体 Bot 暂停 |
| **摘要投递通道** | **Teams 用户私聊（Bot Framework Connector）** | Graph API 发消息须 `Teamwork.Migrate.All`（迁移专用），普通 Bot 不可用；Connector 方案无此限制 |
| **单租户 Bot token** | **`Azure.Identity.ClientSecretCredential`** | `MicrosoftAppCredentials` 默认向 botframework.com 取 token，单租户 App 在本租户目录，需显式指定租户端点 |
| **Bot App 自动安装** | **CatalogAppId + Graph `TeamsAppInstallation` API** | Teams 要求 Bot 被用户安装后才能主动发消息；通过 Graph 自动安装省去用户手动操作 |
| **Teams App 标识** | manifest `id: 8d24f60f...`，`botId: ed32d429...` | `id` 是 Teams 包标识（对应 External app ID），`botId` 是 AAD Bot 应用 ID，两者独立，不要求一致 |
| Teams 接入（后置） | 直接实时 Bot | 最终目标是实时入会 + 实时音频 + 说话人识别，后续恢复时继续此方向 |
| Teams Bridge 技术栈 | `.NET / C#` | 贴合官方 SDK 和媒体能力 |
| Teams Bot 部署环境（实时媒体） | 阿里云 ECS（雅加达） | 本机 WiFi 无法暴露 UDP 8445，ECS 有固定公网 IP |
| 前后端部署 | 继续留在现有 Linux | 降低迁移成本，隔离实时媒体负载 |
| 会议总结 | LLM 自动生成 | 结束同传后异步生成，前端历史记录中展示；可通过 Teams Bot 私聊推送 |

## 8. 当前下一步

**2026-05-22 更新：主动消息管道重写完成，等待重启后端到端验证。详细测试步骤见第 12 节。**

当前待完成：

1. **端到端验证（按第 12 节测试步骤执行）**：
   - 运行 `stop-all.bat` 然后 `start-all.bat`（新版会显式 build Bot，确保运行新代码）；
   - 让 Bot 加入会议，等待 `[MessageBot] Stored meeting chat ConversationReference` 日志出现；
   - 测试"发送到会议聊天"，确认会议聊天中所有参会人员可见；
   - 测试"发送给参会人员"，确认 Teams 个人聊天收到摘要消息。
2. **确认 Azure AD 权限**：需要 `TeamsAppInstallation.ReadWriteSelfForChat.All`（会议聊天安装）已授予管理员同意（之前误写为 `ReadWriteSelfForGroupChat.All`，需确认已改正）。
3. 继续打磨历史记录与会议总结（非阻塞）。

Teams 集成恢复条件（后置，无明确时间表）：

- 需要明确 Teams 实时入会、当前说话人可见的业务需求时；
- 阿里云 ECS 服务器已部署，Teams Bot 入会 PoC 已跑通，恢复时可直接继续进入媒体桥接 PoC；
- 恢复前需先轮换已暴露的联调用 client secret。

## 9. 风险与注意事项

1. 任何 secret 都不能写入文档或聊天记录；已暴露 secret 需要轮换。
2. 本地 PoC 可以用本机 Windows，但正式环境仍应迁到 Azure Windows VM。
3. Teams Bot 要访问实时媒体，关键难点不在 Java，而在官方 `.NET` 媒体 SDK 和调用链路。
4. 自动录屏涉及视频 / 屏幕共享流、存储和合规，必须单独设计。
5. 保存媒体或其衍生数据前，需要把录制提示、状态和授权流程设计清楚。
6. 现有 Linux 服务器继续承载主业务，不要和实时媒体服务混部。

## 10. 进度日志

### 2026-05-18

- 创建 Microsoft Entra 应用。
- 创建 Azure Bot `synclingo-teams-bot`。
- 添加 `Microsoft Teams Commercial` channel。
- 为应用授予并完成 admin consent：
  - `Calls.AccessMedia.All`
  - `Calls.JoinGroupCall.All`
- 创建 Teams App `syncLingo Teams Bot`。
- 绑定 Bot，并启用：
  - `Supports audio calls`
  - `Supports video calls`
- 确认不将实时媒体服务部署在现有 Linux 服务器。
- 确认正式部署采用独立 Azure Windows VM，现阶段先用本机 Windows 做 PoC。
- 已安装 `.NET 6 SDK 6.0.428`。
- 已安装并配置 `ngrok 3.39.2`。
- 已下载微软官方 calling / meeting bot 示例代码，并在本机成功编译通过。
- 已启动本地 `ngrok` 隧道并获得公网地址。
- 已在 Azure Bot 中配置：
  - `Messaging endpoint`
  - `Calling webhook`
- 已确认 PoC 阶段的正确路线：
  - 官方样例代码仅作为验证载体；
  - 继续使用现有 `SI-Bot`、`synclingo-teams-bot`、`syncLingo Teams Bot`；
  - 不把样例自带 AppManifest 误当成最终正式应用。
- 已核对并补齐 Teams App manifest：
  - `validDomains` 已加入当前 `ngrok` 域名；
  - 因 manifest version 为 `1.25` 且包含 `team` scope，已补充 `supportsChannelFeatures`；
  - Developer Portal 的 App Validation 已不再提示缺少 `supportsChannelFeatures`。
- 已完成首轮 App Validation，结果为：
  - `4 Success`
  - `3 Warning`
  - `4 Error`
  - `9 Skipped`
- 已定位 App Validation 中的 4 个红色错误：
  - personal scope 未收到欢迎消息；
  - team scope 未触发欢迎消息；
  - Bot 对 `Hi` 无响应；
  - Bot 对 `Hi` 无响应。
- 已确认首轮验证时本地 `dotnet` 服务和 `ngrok` 隧道均未运行，因此 `Hi` 无响应不能单独归因为代码问题。
- 已在官方样例代码中补充最小聊天骨架：
  - 安装成员加入后的欢迎消息；
  - `Hi` / `hello` / `help` 的基础回复；
  - 后续可在此处继续扩展会议记录查询能力。
- 已开始补齐 Teams Bot SSO 基础配置：
  - Entra `Application ID URI` 已设为 `api://botid-{bot-app-id}` 格式；
  - 已创建 `access_as_user` delegated scope；
  - 已授权 Teams desktop/mobile 与 Teams web client 使用该 scope。
  - Developer Portal `Single sign-on` 已填写相同的 `Application ID URI`；
  - manifest `webApplicationInfo` 已补齐 `id` 与 `resource`。
- 已定位当前仍需补齐的本地配置：
  - 新 `client secret`
  - `UserIdWithAssignedOnlineMeetingPolicy`
  - 现有 Teams App 的 catalog / manifest 配置核对
- 新发现：
  - 已有 `client secret` 在沟通中暴露，需要立即轮换。
  - Developer Portal 的 `Publish to org` 失败并不是权限不足导致；当前已确认第一个明确阻塞点是 manifest 缺少 `supportsChannelFeatures`。
  - App Validation 页面目前仍显示占位文本 `Developer Name`，需要再次确认 Basic information 中开发者信息是否已保存成功。
  - 首轮 App Validation 的 `Hi` 响应失败发生时，本地服务并未运行；重新验证前必须同时保持 Bot 服务和 `ngrok` 隧道在线。
  - 后续会议记录查询会使用 Teams App 作为正式聊天入口，因此 SSO 不再只是“可删的 warning”，而是后续产品能力的基础配置。
- 已补充单租户 Bot 运行配置：
  - `MicrosoftAppType = SingleTenant`
  - `MicrosoftAppTenantId`
- 已确认第二轮验证请求实际到达本地服务，首个真实运行阻塞为旧 `client secret` 无效，导致 Bot 无法回消息。
- 已创建新的联调用 `client secret`，替换到本地配置后重新验证通过。
- 已完成第二轮 App Validation，结果为：
  - `14 Success`
  - `1 Warning`
  - `0 Error`
  - `3 Skipped`
- 当前明确剩余：
  - 真实会议入会与 call 生命周期验证；
  - `Publish to your org` / 组织分发；
  - 查询并回填 `CatalogAppId`；
  - 后续实时媒体桥接开发。
- 新决策：
  - 为了尽快验证核心目标，先做真实会议入会 PoC；
  - `Publish to your org` 与 `CatalogAppId` 不作为当前入会验证的前置阻塞；
  - 当前样例中的 `CatalogAppId` 只影响“创建 incident 后自动把 app 安装到 chat”的支线，不影响从现有会议 chat 触发 `Join scheduled meeting` 的主线验证。
- 当前进入：
  - `阶段 1：本地 PoC`

### 2026-05-19

- 新完成：
  - 已确认个人聊天 `Hi` 能收到 Bot 回复，说明 Bot 服务、`ngrok`、client secret 与 `Messaging endpoint` 基础链路可用。
  - 已确认 Teams admin center 中当前 App 范围显示为 `Personal, Team, Group chat`，且 `Admin can install in meetings = Yes`。
  - 已在官方 calling / meeting bot 示例中新增个人聊天会议链接入会路径：
    - `JoinInfo.TryExtractJoinUrl()` 从消息文本中提取 Teams 会议链接；
    - 短链接 `/meet/...` 会先尝试通过 HTTP redirect 展开成 `/l/meetup-join/...`；
    - `JoinInfo.ParseChatInfo()` 从链接解析 `ThreadId` 与 `MessageId`；
    - `MessageBot.JoinMeetingFromUrl()` 调用现有 Graph call 创建逻辑加入会议。
  - 已使用独立输出目录完成 .NET 编译验证，结果成功。
- 新发现：
  - 个人聊天点击 `Create Call` 返回 `7504 Insufficient enterprise tenant permissions`，但该路径是主动创建通话，不是当前“加入已有会议”的主线验证。
  - 当前更适合先验证“个人聊天发送会议链接自动入会”，以绕开 meeting chat 搜索不到 App 的操作阻塞。
- 新决策：
  - 当前入会 PoC 主测路径调整为：用户在 Bot 个人聊天中粘贴完整 Teams 会议链接，Bot 自动解析并加入已有会议。
  - meeting chat 中的 `Join scheduled meeting` 仍保留为备用路径。
- 阻塞：
  - 尚未用真实会议链接验证 Bot 是否成功入会与 call 生命周期日志。
- 下一步：
  - 重启本地 `CallingBotSample` 服务加载新代码；
  - 保持 `ngrok` 与 Azure Bot endpoint 指向当前地址；
  - 在 Bot 个人聊天发送完整 Teams 会议加入链接；
  - 观察 Teams 会议成员列表和本地 `call created / connected / ended` 日志。

### 2026-05-19 入会验证补充

- 新完成：
  - 用户在 Bot 个人聊天发送短 Teams 会议链接后，Bot 提示无法展开短链接，需要完整 `/l/meetup-join/` 链接。
  - 用户随后发送完整 `/l/meetup-join/` 链接，Bot 回复 `Joining the Teams meeting now...`，并返回 `Control this meeting` 卡片。
  - Teams 会议成员列表中已能看到 `syncLingo Teams Bot`。
  - 本地 `CallingBotSample` 日志显示多次收到 `/callback` 请求，且 Teams 请求了 `/audio/please-record-your-message.wav`，说明会议内播放提示音操作已触发。
  - 本地日志显示 `TeamsRecordingService` 从 Teams media service 下载 recorded stream，说明录音操作也已触发。
- 新发现：
  - 样例当前没有把 `call created / connected / ended` 以清晰文本打印出来，但 `/callback`、提示音频 GET、recorded stream 下载已经足以证明 Bot 已进入会议并执行了会中媒体操作。
  - `Failure converting speech to text. Cognitive services is not enabled.` 是样例录音转文字功能缺少 Cognitive Services 配置，不影响 Bot 入会结论。
- 新决策：
  - 阶段 1 的核心入会 PoC 视为已跑通。
  - 下一步优先补充更明确的 call lifecycle 日志，再进入实时媒体桥接验证。
- 阻塞：
  - 尚未进入 `.NET Teams Media Bridge` 实时音频帧接收开发。
- 下一步：
  - 在 `CallingBot` callback 处理处补充 call state 日志；
  - 继续观察 `Established` / `Terminated` 等状态；
  - 准备进入实时媒体桥接 PoC。

### 2026-05-19 call lifecycle 日志补充

- 新完成：
  - 已在 `CallingBot.NotificationProcessor_OnNotificationReceivedAsync` 中补充清晰日志：
    - `Teams call notification received`
    - `Teams call incoming; answering call`
    - `Teams call established`
    - `Teams call first established event; starting record prompt`
    - `Teams record operation notification received`
    - `Teams recording downloaded`
    - `Teams play prompt operation notification received`
    - `Teams participants notification received`
    - `Only bot remains in Teams call; hanging up`
  - 已通过独立输出目录执行编译验证，结果成功。
- 新发现：
  - 当前仍有官方样例既有 nullable warning 和 `NU1701` warning，不影响本次日志补充编译。
- 新决策：
  - 后续入会验证统一优先看新增的 `Teams call ...` 日志，不再只依赖 ASP.NET 请求日志判断。
- 阻塞：
  - 需要重启本地 `CallingBotSample` 后新日志才会生效。
- 下一步：
  - 重启 Bot；
  - 重新发送完整 Teams 会议链接；
  - 确认控制台出现 `Teams call established` 和 participants 日志；
  - 然后进入实时媒体桥接 PoC。

### 2026-05-19 启停脚本收敛

- 新完成：
  - 已将测试启动入口统一为仓库根目录 `start-all.bat`。
  - `start-all.bat` 现在负责启动：
    - Java 后端 Docker 容器；
    - React/Vite 前端；
    - `.NET` `CallingBotSample`；
    - `ngrok` 到固定地址 `https://auction-uncombed-imply.ngrok-free.dev`。
  - 已将测试停止入口统一为仓库根目录 `stop-all.bat`。
  - `stop-all.bat` 现在负责关闭：
    - 前端 `5173` 端口进程；
    - Teams Bot `3978` 端口进程；
    - `ngrok` 进程；
    - `si-backend` Docker 容器。
  - 已删除旧入口：
    - `start-backend.bat`
    - `start-frontend.bat`
    - `stop-backend.bat`
- 新决策：
  - 后续 Teams 集成测试统一使用 `start-all.bat` / `stop-all.bat`，不再维护单独前端、后端启动脚本。
- 下一步：
  - 使用 `start-all.bat` 做一次完整启动回归；
  - 使用 `stop-all.bat` 验证可完整关闭测试栈。

### 2026-05-19 实时音频方案校正

- 新发现：
  - 用户在 Teams 会议中共享带声音的视频，其他账号进入会议可以听到声音，但前端没有出现识别文字。
  - 这说明“Bot 已入会”和“前端出现字幕”之间还缺少真正的会议实时音频来源。
  - 当前样例里的 `recordResponse` / `RecordOperation` 只能下载一次录音操作产生的片段，不能代表连续会议混音流，也不能保证覆盖共享电脑声音。
  - 前端采集音频的旧方案已经删除，前端不再作为音频来源。
- 新决策：
  - `Graph recordResponse` 仅保留为入会和会议内媒体操作验证，不作为正式实时识别方案。
  - 正式方案必须改为 Teams application-hosted media / real-time media bot，持续获取会议实时音频帧。
  - 前端职责限定为实时字幕、翻译结果、TTS 播放和状态展示，不再采集本机麦克风、浏览器标签页或系统声音。
- 正确链路：

```text
Teams 会议混音 / 共享电脑声音
  -> Teams application-hosted media / real-time media bot
  -> .NET Teams Media Bridge
  -> Java 后端音频 chunk 接口或 WebSocket
  -> ASR / 翻译 / 压缩 / TTS
  -> 前端实时字幕与译文展示
```

- 阻塞：
  - 还未接入 application-hosted media bot 的实时音频 socket / PCM frame 处理。
  - 还未证明 Bot 能持续收到会议混音，尤其是“共享电脑声音”。
- 下一步：
  - 删除或停用 `recordResponse` 上传后端的临时识别链路，避免测试误判。
  - 以官方 application-hosted media / real-time media sample 为基础，先验证 Bot 侧能打印实时音频 frame count、采样率、声道数和 RMS。
  - 共享视频测试标准调整为：会议共享时勾选“包括计算机声音”，Bot 控制台实时 RMS 有波动，Java 后端出现 ASR partial / recognized 日志，前端收到字幕事件。

### 2026-05-19 实时媒体入口代码落地

- 新完成：
  - 已在 `CallingBotSample` 中新增 Teams application-hosted media 验证入口：
    - `ITeamsRealTimeMediaService`
    - `TeamsRealTimeMediaService`
    - `TeamsMediaStream`
    - `TeamsMediaCallHandler`
    - `TeamsRealTimeMediaController`
    - `BotMediaLogger`
  - 个人聊天发送完整 Teams 会议链接后，Bot 不再走 `ServiceHostedMediaConfig` 的 `callService.Create(...)` 入会路径，而是调用新的实时媒体服务入会。
  - 新增实时音频帧日志：`Teams real-time audio frame received`，包含 frame count、字节数、timestamp 和 RMS。
  - 已禁用自动 `recordResponse` 链路：
    - 入会后不再自动 `Record(...)`；
    - 参会者加入后不再自动播放录音提示；
    - 收到历史 `RecordOperation` 通知时只记录 warning，不再下载录音、不再上传 Java 后端。
  - 已通过独立输出目录执行 .NET 编译验证：
    - `dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj -o .\.tmp-build\callingbot-realtime-media-test`
    - 结果：成功，存在既有 nullable warning、`System.Net.Http.Formatting.Extension` 兼容 warning，以及 `Microsoft.Graph.Communications.Calls.Media 1.2.0.5304` 的 net472 兼容 warning。
- 新发现：
  - 当前旧 Teams sample 仍使用 `Microsoft.Graph 4.45.0`，如果直接升级到 `Microsoft.Graph.Communications.Calls.Media 1.2.0.10563` 会强制升级到 Graph v5，改动面过大。
  - 因此当前先采用与旧样例同代的 `Microsoft.Graph.Communications.Calls.Media 1.2.0.5304`，保持 Graph v4 调用面稳定。
  - 本地实时媒体仍需要有效证书 thumbprint 和 media DNS/端口配置；只靠普通 HTTP ngrok 不能完成最终媒体流验证。
- 阻塞：
  - `Bot:MediaCertificateThumbprint` 仍为空，下一次发送会议链接会提示实时媒体配置不完整。
  - 还未验证 Teams media platform 是否能连到本机 media port。
- 下一步：
  - 准备本机或 Azure Windows VM 的 application-hosted media 证书；
  - 将证书 thumbprint 写入 `Bot:MediaCertificateThumbprint`；
  - 确认 `Bot:MediaDnsName` 和 `Bot:MediaExternalPort` 能被 Teams media platform 访问；
  - 重启 `start-all.bat` 后，在 Bot 个人聊天发送完整会议链接；
  - 观察控制台是否出现 `Teams real-time media client initialized`、`Teams real-time media call added` 和 `Teams real-time audio frame received`。

### 2026-05-19 阿里云 ECS 服务器部署

- 新完成：
  - 确认本机网络在共享 WiFi 后面，无法做路由器端口转发，本机方案不可行。
  - 决定直接使用阿里云 ECS Windows 服务器作为 Teams 媒体 Bot 的正式部署环境（跳过本机 PoC，直接进正式形态）。
  - 已创建阿里云 ECS 实例：
    - 地域：印度尼西亚（雅加达）
    - 规格：`ecs.c7a.large`（2 vCPU / 4 GiB，AMD 计算型）
    - 操作系统：Windows Server 2019 数据中心版 64 位英文版
    - 系统盘：ESSD 50 GiB
    - 公网带宽：按使用流量
    - 公网 IP：`8.215.69.56`
  - 已配置阿里云安全组入方向规则：
    - TCP 443（HTTPS）
    - TCP 3789（RDP）
    - UDP 8445（Teams 实时媒体）
    - TCP 22（SSH）
  - 已在 ECS 上完成环境准备：
    - 安装 `.NET 6 SDK 6.0.428`（通过 `dotnet-install.ps1`）
    - 生成自签名证书，DnsName = `8.215.69.56`，Thumbprint = `85B1B6B9CDE686D120B6FADA6E0A6C7A9530E538`，存储在 `Cert:\LocalMachine\My`
    - 开放 Windows 防火墙 UDP 8445 入站规则
    - 创建部署目录 `C:\synclingo-bot`
    - 安装 `ngrok`（路径：`C:\synclingo-bot\ngrok.exe`）
    - 启用 OpenSSH Server
  - 已从本机通过 SCP 将发布产物传输到 ECS：
    - 发布目录：`d:\data\syncLingo-plus\.tmp-build\callingbot-publish`
    - 目标目录：`C:\synclingo-bot\app`
  - 已更新 `appsettings.json`：
    - `Bot:MediaDnsName` = `8.215.69.56`
    - `Bot:MediaCertificateThumbprint` = `85B1B6B9CDE686D120B6FADA6E0A6C7A9530E538`
    - `Bot:BotBaseUrl` 继续使用 ngrok 固定域名 `https://auction-uncombed-imply.ngrok-free.dev`
  - 已在 ECS 上配置 ngrok 并启动隧道：
    - 固定域名：`auction-uncombed-imply.ngrok-free.dev` → `localhost:3978`
    - Azure Bot messaging endpoint 和 calling webhook 无需修改
  - 已在 ECS 上成功启动 `CallingBotSample`：
    - 启动命令：`$env:ASPNETCORE_URLS = "http://localhost:3978"; dotnet CallingBotSample.dll`
    - 监听端口：3978
  - 已验证 Teams 个人聊天发送 `Hi` 收到 Bot 回复，消息链路正常。
- 新发现：
  - ECS 上 Windows Defender 会拦截并删除 ngrok zip，需先关闭实时防护再下载解压。
  - dotnet-install.ps1 安装的 .NET SDK 路径（`C:\Users\Administrator\AppData\Local\Microsoft\dotnet`）不在系统 PATH 中，每次新 PowerShell 会话需手动添加：
    `$env:PATH += ";C:\Users\Administrator\AppData\Local\Microsoft\dotnet"`
  - ASP.NET 发布产物在 Production 环境默认监听 5000 端口，需通过 `$env:ASPNETCORE_URLS = "http://localhost:3978"` 强制对齐 ngrok 转发端口。
- 新决策：
  - 放弃本机 PoC 路线，直接在阿里云 ECS 上验证实时媒体链路。
  - ECS 服务器同时作为测试和生产环境使用。
  - `BotBaseUrl` 继续复用 ngrok 固定域名（TCP 信令），媒体端点（UDP 8445）直接走 ECS 公网 IP。

### 2026-05-19 服务器迁移后换机备忘

如需换新 ECS 服务器，需重做以下步骤：

1. 在新服务器上安装 `.NET 6 SDK`（通过 `dotnet-install.ps1`）。
2. 在新服务器上生成新证书：
   ```powershell
   $cert = New-SelfSignedCertificate -DnsName "[新服务器公网IP]" -CertStoreLocation "Cert:\LocalMachine\My" -KeyUsage DigitalSignature,KeyEncipherment -KeyAlgorithm RSA -KeyLength 2048 -NotAfter (Get-Date).AddYears(1)
   Write-Host $cert.Thumbprint
   ```
3. 更新 `appsettings.json`：
   - `Bot:MediaDnsName` = 新服务器公网 IP
   - `Bot:MediaCertificateThumbprint` = 新 Thumbprint
4. 重新发布并通过 SCP 传输到新服务器 `C:\synclingo-bot\app`。
5. 在新服务器安装 ngrok，配置同一 authtoken，启动同一固定域名隧道。
6. 阿里云安全组确认开放：TCP 22、TCP 443、TCP 3789、UDP 8445。
7. Windows 防火墙开放 UDP 8445 入站。
8. 设置 `$env:ASPNETCORE_URLS = "http://localhost:3978"` 后启动 Bot。
- 下一步：
  - 在 Teams 个人聊天发送完整会议链接，观察控制台是否出现：
    - `Teams real-time media client initialized`
    - `Teams real-time media call added`
    - `Teams real-time audio frame received ... rms=...`

### 2026-05-20 Teams 媒体方案暂停，切换至浏览器音频采集

- 新决策：
  - Teams application-hosted media / real-time media bot 方案**正式暂停**。
  - 原因：NAudio WASAPI 系统音频采集在当前架构下无法稳定工作；Teams 媒体 Bot 还需有效证书、固定 UDP 公网端口和 Windows 服务器，整体复杂度远超当前阶段收益。
  - 实时音频来源改为浏览器原生 `navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })`，用户在弹窗中选择标签页/窗口/桌面并勾选「共享音频」即可捕获系统音频，无需任何额外部署。
  - Teams Bot 和阿里云 ECS 服务器保留，待后续有明确需求时恢复。
- 新完成（浏览器音频链路）：
  - 新增 `si-frontend/src/lib/audioCapture.ts`：
    - `AudioCapture` 类，使用 `getDisplayMedia` 捕获系统/标签页音频；
    - `AudioContext({ sampleRate: 16000 })` + `createScriptProcessor(4096, 1, 1)`；
    - RMS 静音检测（阈值 0.001），跳过静音帧减少无效 WebSocket 传输；
    - `floatToPcm16()` Float32 → Int16 转换；
    - `pcmToBase64()` 导出函数。
  - 更新 `si-frontend/src/lib/websocket.ts`：新增 `sendAudio(sessionId, audioBase64)` 方法。
  - 更新 `si-frontend/src/views/InterpretationView.tsx`：
    - WebSocket 连接成功后创建 `AudioCapture`，`onData` 回调中将 PCM16 转 Base64 并通过 WebSocket 发送；
    - 停止时先停 `AudioCapture` 再停 WebSocket；
    - UI 提示文本更新为「选择要翻译的标签页或窗口并勾选「共享音频」」。
- 新完成（会议总结功能）：
  - `MeetingSummaryController`：GET `/{sessionId}` 返回缓存或生成摘要；POST `/{sessionId}` 强制重新生成。
  - `MeetingSummaryFacade`：`getSummary()` 查缓存后按需调用 LLM；`regenerateSummary()` 覆盖缓存。
  - `InterpretationFacade.stopInterpretation()`：结束后异步触发 `meetingSummaryService.generateAndSaveAsync()`。
  - 前端新增 `getMeetingSummary` / `regenerateMeetingSummary` API 函数。
- 新完成（历史记录三标签页重构）：
  - 历史记录详情面板拆分为：文本记录 / 会议总结 / 成本分析 三个 tab。
  - 会议总结 tab：切换时自动拉取，支持导出为 Word（`application/msword` Blob）和 PDF（`window.open` + `window.print`）；可手动重新生成。
  - 成本分析 tab：展示 ASR / 翻译 / TTS / LLM 四张成本卡片（含彩色左侧色条）、用量、单价、估算费用；含合计行和免责声明；LLM 卡片包含「含会议总结生成用量」备注。
  - 移除旧有「查看 / 命名 / 删除 / 导出」操作，界面更简洁。
- 阻塞：
  - 无（当前浏览器音频链路已可用）。
- 下一步：
  - 打磨现有浏览器音频同传体验；
  - 验证会议总结 LLM 生成质量和导出格式；
  - Teams 媒体桥接后置，阿里云 ECS 服务器保留待用。

### 2026-05-20 会议资料驱动总结模块落地

- 新完成：
  - 实时音频链路继续以浏览器系统音频采集为准：前端通过 `navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })` 获取会议或共享视频声音，再经 WebSocket 送入后端 ASR；Teams bot 音频链路不参与当前同传测试。
  - 后端新增会议资料表 `meeting_material`，按 `sessionId` 绑定会议安排、会议报告、高管/重点发言人名单和生成后的总结。
  - 后端新增会议资料总结链路：`MeetingMaterialController`、`MeetingMaterialFacade`、`MeetingMaterialService`、`MeetingMaterialMapper`、`MeetingMaterial`、`SaveMeetingMaterialRequest`、`MeetingMaterialVo`。
  - 新增接口：
    - `GET /api/meeting-materials/sessions/{sessionId}`：读取当前会议绑定的安排、报告和总结。
    - `PUT /api/meeting-materials/sessions/{sessionId}`：保存会议安排、报告和重点发言人名单。
    - `POST /api/meeting-materials/sessions/{sessionId}/summary`：结合会议资料和实时文本记录生成总结。
  - `LlmIntegration` 新增 `summarizeMeetingWithMaterials(...)`，总结输入包括会议安排、会议报告、高管/重点发言人名单和本场会议实时文本记录，输出结构包含会议基本信息、议程完成情况、报告要点、高管发言摘要、决议结论、待办事项和风险跟进。
  - 前端新增 `MeetingMaterialsView`，入口位于同传页悬浮侧边栏的“会议资料总结”。
  - 前端支持选择当前会议或历史会议，录入会议安排、报告、高管/重点发言人，保存后生成绑定到该会议的总结。
  - 前端支持将资料驱动总结导出为 Word 和 PDF。
- 新决策：
  - 第一版先采用“粘贴文本”的方式录入会议通知、议程和报告，不做 PDF/Word 文件解析。
  - 总结结果必须绑定 `sessionId`，便于后续从历史记录、Teams 或 WhatsApp 分享时定位同一场会议。
  - Teams / WhatsApp 发送总结暂未接入，后续建议新增独立 `SummaryDeliveryService`，再分别挂接 Teams Graph / Incoming Webhook 和 WhatsApp Business Cloud API。
- 已验证：
  - `mvn -q -DskipTests compile` 通过。
  - `npm run build` 通过。
- 当前测试方式：
  1. 执行 `.\start-all.bat` 启动本地前后端；如果脚本仍启动 dotnet bot / ngrok，不影响当前浏览器系统音频测试。
  2. 打开 `http://localhost:5173`，点击开始同传。
  3. 在浏览器共享弹窗中选择要翻译的标签页、窗口或桌面，并勾选共享音频。
  4. 播放会议视频或在会议中发言，确认前端实时文本开始出现。
  5. 打开侧边栏“会议资料总结”，选择当前会议。
  6. 粘贴会议安排、会议报告，填写高管/重点发言人名单。
  7. 点击“保存资料”，再点击“生成总结”。
  8. 检查总结内容是否引用议程、报告和实时文本记录，随后验证“导出 Word”和“导出 PDF”。
  9. 测试结束后执行 `.\stop-all.bat`。
- 注意事项：
  - 如果会议没有产生实时文本记录，生成总结会失败或内容为空，需要先确认 ASR 文本已入库。
  - 如果 LLM API Key 未配置或不可用，保存资料可以成功，但生成总结会失败，需要检查后端 LLM 配置和日志。
  - 如果共享视频有声音但前端无文字，优先确认浏览器共享弹窗是否勾选“共享音频”，以及系统音频是否确实进入被共享的标签页或窗口。
- 下一步：
  - 用真实会议安排和报告做一次完整端到端测试，检查总结质量。
  - 决定是否新增 PDF/Word 上传解析。
  - 决定 Teams / WhatsApp 总结发送的正式通道和权限方案。

## 11. Bot 代码更新部署流程

每次修改 Bot 代码后，按以下步骤更新服务器，无需重新配置证书、ngrok 或防火墙。

### 第一步：本机重新发布

```powershell
dotnet publish "d:\data\syncLingo-plus\external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj" `
    -c Release -r win-x64 --self-contained false `
    -o "d:\data\syncLingo-plus\.tmp-build\callingbot-publish"
```

### 第二步：本机传文件到服务器

```powershell
scp -r "d:\data\syncLingo-plus\.tmp-build\callingbot-publish\*" Administrator@8.215.69.56:/synclingo-bot/app/
```

### 第三步：服务器重启 Bot

在服务器 PowerShell 中：

```powershell
# 停止当前 Bot（如果在运行）
Stop-Process -Name dotnet -Force -ErrorAction SilentlyContinue

# 启动新版本
$env:PATH += ";C:\Users\Administrator\AppData\Local\Microsoft\dotnet"
$env:ASPNETCORE_URLS = "http://localhost:3978"
cd C:\synclingo-bot\app
dotnet CallingBotSample.dll
```

### 注意事项

- `appsettings.json` 在传文件时会被覆盖，如果服务器上有本地配置修改，需要在发布前同步到本机，或传完后手动还原。
- ngrok 不需要重启（独立进程，与 Bot 进程无关）。
- 如果换了新服务器（公网 IP 变化），需要：
  1. 在新服务器重新生成证书（DnsName 填新 IP）
  2. 更新 `appsettings.json` 中 `Bot:MediaDnsName` 和 `Bot:MediaCertificateThumbprint`
  3. 重走第一、二步传文件

### 2026-05-20 声纹注册与音色自动绑定链路重构

- 新完成：
  - **彻底移除 Azure Speaker Recognition 方案**：删除 `AzureSpeakerRecognitionIntegration.java`、`AzureSpeakerRecognitionProperties.java`，移除 `application.yml` 中 `azure.speaker-recognition.*` 配置块，从 `.env.example` 中删除 5 个 Azure SR 变量；`SpeakerIdentityService` 重写为纯 Pyannote 路径，无任何 Azure 降级。
  - **声纹注册全自动化**：
    - `SpeakerAudioBuffer` 采样上限从 10s 扩展至 30s（`SPEAKER_VOICE_MAX_SAMPLE_SECONDS = 30`）；
    - 达到 20s 音频且已关联真实人名时自动触发 `triggerEnroll()`（`SPEAKER_VOICE_ENROLL_SAMPLE_SECONDS = 20`）；
    - 关联真实人名时如 speaker profile 尚未注册，也立即异步触发 `enrollSpeakerProfile()`（`mapSessionSpeaker()` 中）；
    - 引入 `enrollingSpeakers` 并发集合，防止同一 session 内重复注册；
    - 删除 `POST /speaker-identities/{id}/enroll` 接口和前端手动注册声纹按钮。
  - **voiceId 自动绑定竞态修复**：
    - `triggerClone()` 在触发时刻捕获 `personName` 快照；
    - Cartesia 克隆完成后优先调用 `bindCartesiaVoiceDirect(personName, voiceId, lang)`，直接按人名写库，不再依赖 `sessionIdentityMap`（防止 session 已清理时绑定失败）；
    - 新增 `bindCartesiaVoiceDirect()` 和 `findIdentityByName()` 方法；
    - `mapSessionSpeaker()` 关联人名时同步检查已就绪 voice，立即绑定已完成的 voiceId。
  - **Cartesia clone 超时修复**：`CartesiaTtsIntegration` 新增独立 `cloneHttpClient`，`readTimeout = 120s`（原全局 10s 不够，Cartesia 服务端约需 30～60s）。
  - **Docker 网络修复**：后端容器内无法访问 `localhost:7000`；`start-all.bat` 的 `docker run` 补充 `-e SPEAKER_SERVICE_URL=http://host.docker.internal:7000` 和 `--add-host=host.docker.internal:host-gateway`，同时注入 `-e SPEAKER_SERVICE_ENABLED=true`。
  - **前端声纹克隆页简化**：删除"克隆默认音色"模块，删除手动注册声纹按钮，声纹编码显示"已注册"代替原始 `enrolled` 字符串，仅保留声纹对照表和本次会议说话人关联。
  - **speaker-service Python 兼容性**：
    - `requirements.txt` 将所有版本约束从固定版本（`torch==2.3.1` 等）改为 `>=` 约束，适配 Python 3.14（`torch>=2.9.0`、`torchaudio>=2.9.0`、`numpy>=2.0.0`）。
    - `main.py` 修复 SpeechBrain 1.0 以上版本的导入路径：优先 `from speechbrain.inference.speaker import SpeakerRecognition`，失败时回退旧路径。
    - `@app.on_event("startup")` 替换为 FastAPI 0.115+ 标准的 `lifespan` 异步上下文管理器。
- 新发现：
  - Python 3.14 pip 源只有 `torch>=2.9.0`，旧固定版本无法安装。
  - SpeechBrain 1.0 将 `speechbrain.pretrained` 模块迁移至 `speechbrain.inference.speaker`，直接 import 旧路径会 `ImportError`。
  - FastAPI 0.115 将 `@app.on_event` 标记为废弃，startup 逻辑需改用 `lifespan`。
- 阻塞（已解决）：
  - ~~speaker-service Python 依赖尚未安装完成~~（已安装）
  - ~~torch DLL 被 Windows 应用控制策略拦截~~（已用 `Unblock-File` 解除）
  - ~~SpeechBrain 在 Windows 上默认用 symlink 缓存模型，无权限创建~~（已 monkey-patch 为 COPY 策略）
- 下一步：
  - 验证 `python main.py` 启动后 `/health` 返回 `model_loaded: true`；
  - 重新用 `start-all.bat` 启动后端，做端到端声纹注册 + voiceId 绑定验证。

### 2026-05-20 speaker-service Windows 兼容性修复

- 新完成：
  - **torch DLL 被应用控制策略拦截**：pip 从网络下载的 native DLL 带有"来自网络"的 Zone 标记，Windows 策略拦截加载。通过 `powershell -Command "Get-ChildItem -Recurse '...torch' -Filter '*.dll' | Unblock-File"` 批量解除标记，`import torch` 恢复正常。
  - **SpeechBrain symlink 权限错误**（`[WinError 1314] 客户端没有所需的特权`）：SpeechBrain 默认用 `LocalStrategy.SYMLINK` 将 HuggingFace 缓存链接到 `pretrained_models/` 目录，Windows 非管理员账号无此权限。在 `main.py` 中 import SpeechBrain 后立即对 `fetch()` 函数做 monkey-patch，将默认 `local_strategy` 改为 `LocalStrategy.COPY`；同时修补已导入该函数引用的各模块（`speechbrain.inference.interfaces` 等），使 `from_hparams` 调用时直接使用复制策略。
  - **`FetchConfig(local_strategy=...)` API 不存在**：`FetchConfig` 中无 `local_strategy` 字段（该字段属于 `fetch()` 函数参数），已移除错误用法，lifespan 改回直接调用 `from_hparams`。
  - `main.py` 加入 `if __name__ == "__main__"` 入口，`python main.py` 现在可直接启动 uvicorn。
  - `start-all.bat` 步骤 [4/6] 改为直接 `python main.py`，不再走 venv 脚本。
- 新发现：
  - Windows pip 下载的 native DLL 会被打上 Zone.Identifier 标记，策略严格时阻止加载，需要 `Unblock-File` 处理整个包目录。
  - SpeechBrain 1.x 的 `FetchConfig` 与 `fetch()` 的 `local_strategy` 参数是独立的，`FetchConfig` 只控制缓存/网络行为，不控制链接策略。
  - 由于 `fetch` 在 `interfaces.py` 顶部被 `from ... import fetch` 导入，只 patch `_sb_fetch_mod.fetch` 不够，必须同时修补各使用模块中的引用。
- 阻塞：
  - 无（待验证 `python main.py` 成功启动并返回 `model_loaded: true`）。
- 下一步：
  - 验证 `http://localhost:7000/health` 返回 `{"status":"ok","model_loaded":true}`；
  - 重新执行 `start-all.bat`，完成端到端声纹注册 + voiceId 自动绑定测试。

### 2026-05-21 Bot 精简 + 参会人员获取 + 推送摘要到 Teams

- 新完成：
  - **Bot 代码精简（ARM64 兼容）**：本机为 ARM64 Windows，原 csproj 引用的 `Microsoft.Graph.Communications.Calls.Media` 和 `Microsoft.Skype.Bots.Media` 包含 x64-only native DLL，在 ARM64 进程中触发 `BadImageFormatException`。已将上述包全部移除，Bot 精简为仅保留：Bot 入会（通过前端 `POST /api/meetings/join`）、获取参会人员（Graph API）、向 Teams 聊天发送消息三项功能，无实时媒体依赖。
  - **ARM64 启动问题修复**：
    - `.exe` app host 通过注册表 `HKLM:\SOFTWARE\dotnet\Setup\InstalledVersions\x64` 查找运行时，ARM64 机器上该键不存在；改为 `<UseAppHost>false</UseAppHost>`，让 `dotnet run` 直接使用 `dotnet exec <dll>` 路径，绕过注册表查找。
    - 将 `<PlatformTarget>x64</PlatformTarget>` 改为 `<PlatformTarget>AnyCPU</PlatformTarget>`，允许 ARM64 dotnet 加载托管程序集。
    - `start-all.bat` 中 Teams Bot 启动行改为显式调用 `"C:\Program Files\dotnet\dotnet.exe" run`。
  - **Bot 获取参会人员**：`MeetingSummaryController` 新增 `GET /api/meetings/participants`，从 `ICallCache` 取出当前活跃 call 的 AAD ID 列表，通过 Graph API 批量解析为 `{ aadId, displayName, email }`，前端 `TeamsBotView` 新增"会议参会人员"区域，可一键将参会人加入摘要发送对象。
  - **推送摘要到 Teams 按钮**：`InterpretationView` 侧边栏新增"推送摘要到 Teams"按钮（会话结束后显示），点击后：
    1. 调用 Java 后端 `GET /api/summary/{sessionId}` 获取 LLM 生成的会议摘要；
    2. 将摘要文本 `POST /bot-api/api/meetings/summary` 发送到 C# Bot；
    3. Bot 通过 Graph API 将摘要消息发送到 Teams 会议聊天（`sendToMeetingChat: true`）。
  - **推送静默失败修复**：原 `MeetingSummaryController.SendSummary` 无论 Bot 是否在会议中都返回 `{"sent":true}`，摘要在 `MeetingSummaryService` 内被静默丢弃。修复为：
    - 无 `activeCallId`（Bot 未加入任何会议）时返回 HTTP 409 + 中文错误消息；
    - 有 callId 但无 `threadId` 时同样返回 409；
    - Graph API 调用失败时抛出异常并捕获为 502。
    - `MeetingSummaryService.SendSummaryAsync` 改为抛出异常而非静默吞掉，保证错误能被上层感知。
    - 前端解析 JSON 错误体，将 `error` 字段内容直接展示给用户，而非原始 HTTP 状态码。
  - **参会人员追踪 Bug 修复**：原 `CallingBot` 只在 `!atLeastOneUserJoined` 为 `true` 时才记录参会人 AAD ID，导致后续加入的参会者被忽略；已将 `AddParticipantAadId` 循环移至条件块外，对所有 participants 通知均记录。
  - **build 验证**：`dotnet build` 结果 0 错误、14 警告（全为原样例既有 nullable warning）。
- 新发现：
  - ARM64 Windows 上 `dotnet` 位于 `C:\Program Files\dotnet`（ARM64 运行时），编译目标若为 x64，ARM64 进程无法加载，需改为 `AnyCPU`。
  - `<UseAppHost>false</UseAppHost>` 会跳过生成 `.exe` 启动器，防止 app host 走注册表查找运行时路径，适合 ARM64 开发机本地调试。
  - C# Bot 的 `activeCallId` 和 `meetingThreadId` 存储在内存缓存中（4 小时过期），Bot 进程重启后缓存清空，需重新在前端 TeamsBotView 点击"加入会议"才能恢复推送能力。
- 新决策：
  - 当前 Bot 不再包含任何实时媒体代码，完全依赖 Graph API 进行参会人员解析和消息发送，ARM64 本机可直接运行。
  - 推送摘要到 Teams 必须满足前置条件：Bot 已加入会议（`activeCallId` 有效），否则前端会收到明确的中文错误提示。
- 阻塞：
  - 无（Bot 本地可正常运行；推送功能前置条件是 Bot 已入会）。
- 下一步：
  - 用 `start-all.bat` 启动完整栈，在 Teams Bot 页面输入会议链接加入会议；
  - 开始同传 → 结束 → 点击"推送摘要到 Teams"，验证会议聊天中收到摘要消息。

### 2026-05-21 发送消息认证修复 + 会议聊天自动安装 Bot

- 新完成：
  - **Graph API 发消息权限问题定位**：原 `ChatService.SendMessageToChatAsync` 通过 Graph API `POST /chats/{id}/messages` 发送消息，该接口对应用级权限（daemon app）只允许 `Teamwork.Migrate.All`（仅限数据迁移场景），普通 Bot 无法使用。已将发消息改为 **Bot Framework Connector** 方案，不再依赖 Graph API。
  - **单租户 Bot token 认证修复（三次迭代）**：
    - 第一次：用 `ConnectorClientFactory` 创建 `MicrosoftAppCredentials`，直接赋值 `OAuthEndpoint`，编译失败（`CS0200`，属性只读）。
    - 第二次：尝试在派生类构造函数中设置 `OAuthEndpoint`（利用 `protected set`），仍编译失败，该版本 SDK（4.17.2）中 `AppCredentials.OAuthEndpoint` 无任何 setter。
    - 第三次（最终方案）：放弃 `MicrosoftAppCredentials`，改为自定义 `BotFrameworkTokenCredentials`（继承 `Microsoft.Rest.ServiceClientCredentials`），重写 `ProcessHttpRequestAsync`，通过 `Azure.Identity.ClientSecretCredential` 从租户专属端点 `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token` 取得 scope 为 `https://api.botframework.com/.default` 的 token，注入到每次 HTTP 请求的 `Authorization: Bearer` header。`Azure.Identity` 自动处理 token 缓存与续期。编译结果：0 错误，14 既有 warning。
  - **Bot 入会后自动安装 Teams App 到会议聊天**：在 `MeetingSummaryController.JoinMeeting` 中，入会成功后 fire-and-forget 调用 `TryInstallAppInMeetingChatAsync`，通过 Graph API `POST /chats/{threadId}/installedApps` 将 Bot 对应的 Teams App 安装到会议聊天，409（已安装）静默跳过，其他错误记 warning 日志不阻断入会响应。
  - **`CatalogAppId` 配置项新增**：`BotOptions` 新增 `CatalogAppId` 字段，对应 `appsettings.json` 中已有的 `Bot:CatalogAppId` 占位符，留空或含 `<<` 时自动跳过安装逻辑。
  - **Docker 构建缓存损坏修复**：`docker builder prune -f` 清除约 21 GB 损坏缓存，恢复 `start-all.bat` 的镜像构建能力。
- 新发现：
  - Graph API `POST /chats/{id}/messages` 对应用级权限（无用户上下文）只开放 `Teamwork.Migrate.All`，普通 Teams Bot 无法通过此接口向会议聊天发消息，必须走 Bot Framework Connector 通道。
  - Bot Framework SDK 4.17.2 中 `AppCredentials.OAuthEndpoint` 无任何可写入口（非 `virtual`，无 `protected set`），无法通过继承或外部赋值覆盖，只能绕开整个 `MicrosoftAppCredentials` 体系，自定义 `ServiceClientCredentials`。
  - `MicrosoftAppCredentials` 默认向 `botframework.com` 租户取 token，单租户 App 不在该目录，会报 `AADSTS700016`。单租户 Bot 必须显式指定本租户端点取 token。
  - `Azure.Identity.ClientSecretCredential` 封装了 token 缓存逻辑，无需手动管理过期，适合用于长期运行的 daemon 服务凭据。
- 新决策：
  - 消息发送永久改用 Bot Framework Connector + `Azure.Identity`，不再使用 Graph API 发消息，避免权限依赖。
  - `CatalogAppId` 需要配置后才能触发自动安装；未配置时 Bot 正常入会，只是不会自动出现在会议聊天中。
- 需要配置：
  - `appsettings.json` → `Bot:CatalogAppId`：填入 Teams App 在组织目录中的 catalog ID（通过 Graph `GET /appCatalogs/teamsApps?$filter=externalId eq '{manifestId}'` 查询）。
  - Azure AD → API 权限 → 添加 `TeamsAppInstallation.ReadWriteSelfForChat.All`（应用权限）并授予管理员同意，Bot 才能自动将自己安装到会议聊天。
- 阻塞：
  - 无（编译通过；Bot Framework Connector + `Azure.Identity` 认证方案理论上可工作）。
- 下一步：
  - 重启 Bot，再次点击"推送摘要到 Teams"，确认不再出现 `AADSTS700016` 错误；
  - 验证会议聊天收到摘要消息；
  - 补全 `CatalogAppId` 并在 Azure AD 加 `TeamsAppInstallation.ReadWriteSelfForChat.All` 权限，验证自动安装流程。

### 2026-05-21 摘要发送改为 Teams 用户私聊

- 新完成：
  - 废弃摘要发送中的会议聊天方案：`POST /api/meetings/summary` 不再接受 `sendToMeetingChat` / `sendToParticipants`，也不再依赖 `activeCallId`、`meetingThreadId` 或会议聊天安装状态。
  - C# Bot `MeetingSummaryController.SendSummary` 请求体改为 `{ content, recipients }`，其中 `recipients` 为 Teams 用户邮箱、UPN 或 AAD ID。
  - `MeetingSummaryService` 改为逐个 Teams 用户发送，返回成功/失败明细；所有用户发送失败时返回 502，部分失败时返回成功响应并带 `failedCount`。
  - `ChatService` 负责解析 Teams 用户、确保 Bot App 安装到用户 personal scope、获取个人 chat 后通过 Bot Framework Connector 发送私聊消息。
  - 删除旧的会议聊天自动安装入口：`TryInstallAppInMeetingChatAsync`、`IChatService.InstallApp`、会议聊天 `SendMessageToChatAsync` 不再作为摘要发送路径。
  - 前端 `TeamsBotView` 改为“Teams 用户摘要”页面，只维护 Teams 用户收件人和默认开关；移除会议链接入会、参会人员拉取、群组发送对象等旧 UI。
  - `InterpretationView` 的“推送摘要到 Teams”按钮改为读取 `si_teams_recipients` 中的默认 Teams 用户，然后调用 `sendTeamsSummaryToUsers(summary, recipients)`。
- 新发现：
  - 旧页面虽然维护了发送对象，但实际请求只传 `sendToMeetingChat: true`，没有把用户对象传给 Bot，因此和“只发给 Teams 用户”的目标不一致。
  - 给 Teams 用户做 proactive 私聊仍需要 `Bot:CatalogAppId`，因为后端需要通过 Graph 将 Bot App 安装到用户 personal scope 后才能拿到个人 chat。
- 新决策：
  - 摘要投递正式以 Teams 用户私聊为主，不再把会议聊天作为默认投递目标。
  - 会议入会接口可以保留给 Bot 入会 PoC 和后续能力，但不参与摘要发送。
- 需要配置：
  - `appsettings.json` -> `Bot:CatalogAppId`：填入 Teams App 在组织目录中的 catalog ID。
  - Azure AD API 权限：面向用户 personal scope 安装需 `TeamsAppInstallation.ReadWriteSelfForUser.All`（应用权限）并授予管理员同意。
- 已验证：
  - 旧会议聊天发送路径残留搜索通过。
  - C# Bot `dotnet build` 通过，仍有官方样例既有 nullable warning。
  - 前端 `npm.cmd run build` 通过。
- 下一步：
  - 重新构建并重启 C# Bot 与前端。
  - 在 Teams 用户摘要页面添加一个真实 Teams 用户并设为默认。
  - 结束一次同传后点击“推送摘要到 Teams”，验证该用户收到 Bot 私聊摘要。

### 2026-05-21 Bot 个人聊天查询历史会议

- 新完成：
  - 新增 Teams Bot 个人聊天查询方案：用户可以直接给 Bot 发送 `最近`、`搜索 关键词`、`摘要 会议ID/关键词` 等指令查询 syncLingo 历史会议。
  - C# `MessageBot` 不再只返回固定运行状态，而是清理 Teams @mention / HTML 文本、读取 Teams 成员资料，并转发 `{ aadId, mail, userPrincipalName, displayName, message }` 到 Java 后端。
  - C# 新增 `SyncLingoBotQueryService`，通过 `Bot:BackendBaseUrl` 调用 Java `POST /api/teams-bot/query`。
  - Java 后端新增 `TeamsBotQueryController -> TeamsBotQueryFacade -> TeamsBotQueryService` 查询链路，保持 controller / facade / service 分层。
  - Java 查询服务按 Teams `mail` / `userPrincipalName` / UPN 前缀匹配 `si_user.email` 或 `si_user.username`，只返回匹配用户自己的历史会议。
  - 支持配置 `teams.bot.default-user-id` / `TEAMS_BOT_DEFAULT_USER_ID` 作为开发兜底；默认值为 `0`，不会自动泄露任意用户历史记录。
- 新发现：
  - Bot 个人聊天里的 Teams 用户身份可以由 `TeamsInfo.GetMemberAsync` 获取，适合用作后端用户映射入口。
  - 当前系统已有 `si_user.email` 字段，可以先作为 Teams 用户映射字段，不必为了 MVP 立即新增绑定表。
- 新决策：
  - 第一版采用确定性命令解析，不接 LLM；先保证会议记录查询、摘要读取和权限边界稳定。
  - 后续如需自然语言问答，可在 Java 后端基于历史记录和摘要追加 RAG / LLM 层，而不是让 C# Bot 直接访问数据库或生成答案。
- 需要配置：
  - C# Bot `appsettings.json` -> `Bot:BackendBaseUrl` 指向 Java 后端，例如 `http://localhost:8080`。
  - 生产环境建议将 syncLingo 用户的 `email` 填为 Teams 邮箱/UPN，或建立正式绑定表后切换到绑定表。
  - 生产环境建议同时设置 Java `TEAMS_BOT_API_SECRET` 与 C# `Bot:BackendApiSecret`，让 `/api/teams-bot/query` 只接受 Bot 后端调用。
- 已验证：
  - C# Bot `dotnet build` 通过。
  - Java 后端 `mvn.cmd -q -DskipTests package` 通过。
- 下一步：
  - 在真实 Teams 个人聊天中发送 `最近`、`搜索 xxx`、`摘要 <sessionId>` 做端到端验证。
  - 根据真实用户组织方式，决定是否新增 `teams_user_binding` 管理表和后台绑定界面。

### 2026-05-21 确定采用方案 B：组织目录自动安装 Bot

- 新完成：
  - 明确放弃参会人员手动安装作为正式路径，直接采用方案 B：Teams App 发布到组织目录后，由 Bot 使用 Graph 自动安装到用户 personal scope。
  - 删除 `ChatService` 中 `CatalogAppId` 未配置时走 `CreateConversationAsync` 的 fallback，避免再次触发未安装用户无法主动发消息的问题。
  - `ChatService.SendMessageToUserAsync` 现在必须先校验 `Bot:CatalogAppId`，然后执行 `EnsureBotInstalledForUserAsync()`，再获取 personal chat 并通过 Bot Framework Connector 发消息。
  - `MeetingSummaryController.SendSummary` 在全部收件人失败时返回第一条真实失败原因，便于定位 CatalogAppId / Graph 权限 / 用户解析问题。
  - 新增 `docs/teams-bot-plan-b-auto-install-guide.md`，记录方案 B 的 Azure AD 权限、Teams 管理中心发布、CatalogAppId 查询、配置和验证步骤。
- 新发现：
  - 本地 `syncLingo-teams-app.zip` 与 `AppManifest/manifest.json` 均包含 `personal` scope，可用于组织目录发布。
  - manifest `id` / `botId` 为 Azure AD Bot 应用 ID；方案 B 运行时还必须使用组织目录分配的 `CatalogAppId`。
- 新决策：
  - 发送摘要到 Teams 用户时不再允许 `CreateConversationAsync` fallback。
  - `TeamsAppInstallation.ReadWriteSelfForUser.All` 与 `CatalogAppId` 成为自动安装路径的硬性前置条件。
- 需要配置：
  - Azure AD 应用权限：`User.Read.All`、`TeamsAppInstallation.ReadWriteSelfForUser.All`，并授予管理员同意。（已完成）
  - Teams 管理中心上传并允许 Teams App。（已完成）
  - Teams 管理中心 App ID `99867208-21ac-40ec-b946-3fe2f9659006` 已写入 C# `appsettings.json` 的 `Bot:CatalogAppId`。
- 下一步：
  - C# Bot 已重启并监听 `http://0.0.0.0:3978`。
  - 用真实 Teams 用户执行摘要私聊发送验证，确认日志出现 personal scope 安装和发送成功记录。

### 2026-05-21 Teams App 改名、manifest 修正与 CatalogAppId 完整配置

- 新完成：
  - **问题根因梳理**：通过阅读完整进度文档，系统梳理了"No Teams users received the summary"的所有历史问题根因（见下方"新发现"）。
  - **Azure AD 权限授予**：在 Azure AD 应用 `ed32d429...` 下，添加并授予管理员同意：`User.Read.All`（解析参会人员显示名称）、`TeamsAppInstallation.ReadWriteSelfForUser.All`（自动为用户安装 Bot App）。
  - **定位 Teams Admin Center 中的正确 App**：原先以为 `99867208-21ac-40ec-b946-3fe2f9659006` 不在 Teams 管理中心，实际是用该 ID 搜索找不到，需要按名称（syncLingo Teams Bot）搜索。
  - **澄清 External app ID 与 botId 的区别**：Teams 管理中心的 External app ID（`8d24f60f-f031-43ae-a228-c7089d688b57`）是 manifest `id` 字段（Teams App 包的唯一标识），与 bot 的 AAD ClientId（`ed32d429...`，对应 manifest `botId`）是两个不同字段，不一致是正常的，不影响消息发送。
  - **manifest.json 修正**：将顶层 `id` 从 `ed32d429...` 改为 `8d24f60f-f031-43ae-a228-c7089d688b57`（与 Teams 管理中心已有 App 的 External app ID 一致），`botId` 和 `webApplicationInfo.id` 保持 `ed32d429...` 不变，版本号升至 `1.0.4`，应用名称改为 `meeting bot`。
  - **重新打包 syncLingo-teams-app.zip**：按修正后的 manifest 重新生成 App 包。
  - **Teams 管理中心上传新版本**：在 "syncLingo Teams Bot" 详情页通过 "Upload file" 上传新包，应用名称已更新为 **meeting bot**，CatalogAppId `99867208-21ac-40ec-b946-3fe2f9659006` 不变。
  - **`appsettings.json` 中 `Bot:CatalogAppId` 已配置**：值为 `99867208-21ac-40ec-b946-3fe2f9659006`，Bot 已重启加载新配置。
- 新发现：
  - **历史问题汇总**（按时间顺序）：
    1. Graph API `POST /chats/{id}/messages` 对应用权限只开放给 `Teamwork.Migrate.All`，普通 Bot 无法用此接口发消息；已改为 Bot Framework Connector。
    2. `MicrosoftAppCredentials` 默认向 `botframework.com` 租户取 token，单租户 App 不在该目录，报 `AADSTS700016`；已改为自定义 `BotFrameworkTokenCredentials` + `Azure.Identity`。
    3. 前端 axios `VITE_API_BASE_URL=http://localhost:8080` 使 `/bot-api/**` 绕过 Vite 代理直接打到 Java 后端，Java 返回 404；已在 Java 后端加 `BotApiProxyController` 转发。
    4. Bot 只监听 `127.0.0.1:3978`，Docker 容器无法访问；已改为 `0.0.0.0:3978`。
    5. `CatalogAppId` 为占位符导致所有发送失败；已填入真实值 `99867208...`。
    6. `External app ID`（manifest `id`）与 bot AAD ClientId 是不同字段，两者不一致属正常现象；之前的分析误将其视为阻塞问题。
  - Teams 管理中心搜索 App 时须按**名称**搜索，用 CatalogAppId 搜索无效。
  - Teams 管理中心存在两个自定义 App：**meeting bot**（原 syncLingo Teams Bot，有用）和 **syncLingo Bot**（多余，无删除权限，可忽略或 Block）。
- 新决策：
  - manifest 顶层 `id` 必须与 Teams 管理中心已有 App 的 External app ID 保持一致，否则上传时报"已存在相同 app ID"冲突。
  - 多余的 "syncLingo Bot" App 无法从管理中心删除（无删除选项），暂时忽略，不影响功能。
- 阻塞：
  - 无明确阻塞；所有前置配置已完成，待端到端验证。
- 下一步：
  - 用 `start-all.bat` 启动完整栈；
  - 在 Teams Bot 页面输入真实会议链接，点击"加入会议"；
  - 确认参会人员列表正常显示（含显示名称，不再是裸 AAD GUID）；
  - 点击"发送给参会人员"，观察 Bot 控制台日志出现 `[ChatService] EnsureBotInstalledForUser`、`[ChatService] SendMessageToUser end` 记录；
  - 确认参会人员在 Teams 个人聊天中收到摘要消息。

### 2026-05-21 Bot Framework 主动私聊修复（28: 前缀 + 租户专属 ServiceURL）

- 新完成：
  - **根因定位**：之前 `ChatService.SendMessageViaChatAsync` 走 Graph API `POST /chats/{id}/messages`，该接口对应用级权限仅开放 `Teamwork.Migrate.All`（数据迁移专用），Bot 持有的 `Chat.ReadWrite.All` 不够，报 `Forbidden: Missing role permissions, requires Teamwork.Migrate.All`。
  - **改为 Bot Framework Connector 创建对话**：删除 `SendMessageViaChatAsync`，新增：
    - `CreateProactiveConversationIdAsync(userAadId)`：使用 `28:{clientId}` 作为 Bot ID、`29:{userAadId}` 作为用户 ID，调用 `connector.Conversations.CreateConversationAsync` 创建 1:1 对话，返回 conversationId。
    - `SendMessageToConversationAsync(conversationId, htmlContent)`：用 `connector.Conversations.SendToConversationAsync` 发送消息，`From.Id = 28:{clientId}`，`ServiceUrl = https://smba.trafficmanager.net/id/{tenantId}/`（租户专属端点，通用 `/teams/` 端点会拒绝主动创建）。
  - **`ConnectorClientFactory` 已使用租户专属 ServiceURL**（之前已修复）：`BotFrameworkTokenCredentials` 通过 `Azure.Identity.ClientSecretCredential` 从租户专属端点取 Bot Framework token，`ConnectorClient` 使用 `https://smba.trafficmanager.net/id/{tenantId}/` 作为 baseUrl。
  - **编译验证**：`dotnet build` 结果 0 错误，14 既有 warning。
- 新发现：
  - Teams Bot ID 前缀约定：Bot 应用身份使用 `28:` 前缀，AAD 用户使用 `29:` 前缀；`CreateConversationAsync` 中 `Bot.Id` 必须带 `28:` 否则 Teams 无法识别 Bot 身份。
  - 通用 Bot Framework ServiceURL（`https://smba.trafficmanager.net/teams/`）在单租户场景下会拒绝主动创建对话，必须使用租户专属 URL（`/id/{tenantId}/`）。
  - Graph API `POST /chats/{id}/messages` 的应用级权限限制与 `Bot Framework Connector SendToConversation` 完全独立；后者只需 Bot Framework token（scope `https://api.botframework.com/.default`），无 Graph 权限依赖。
- 新决策：
  - 发送私聊消息路径固定为：`EnsureBotInstalledForUserAsync` → `CreateProactiveConversationIdAsync` → `SendMessageToConversationAsync`，不再走 Graph `chats` API 发消息。
- 阻塞：
  - 无（编译通过，Bot 已重启，等待端到端验证）。
- 下一步：
  - 在前端 Teams Bot 页面加入会议，刷新参会人员列表，点击"发送给参会人员"；
  - 观察 Bot 日志出现 `[ChatService] Proactive conversation created` 和 `[ChatService] Bot Framework message sent`；
  - 确认参会人员 Teams 私聊收到摘要消息。

### 2026-05-21 移除"退出会议"功能

- 新完成：
  - 前端 `TeamsBotView.tsx`：删除 `leaveMeeting` 导入、`handleLeaveMeeting` 函数、"退出会议"按钮。
  - 前端 `TeamsBotView.css`：删除 `.tb-btn--danger` 和 `.tb-btn--danger:hover:not(:disabled)` 样式。
  - 前端 `api/index.ts`：删除 `leaveMeeting` 导出函数。
  - 后端 `MeetingSummaryController.cs`：删除 `POST /api/meetings/leave` 接口和 `LeaveMeetingRequest` 类。
- 新决策：
  - 当前 Bot 主要用于加入会议、获取参会人员、发送摘要，退出逻辑不在核心路径内，暂不提供 UI 控制。Bot 会在会议自然结束或其余人全部退出后自动挂断（既有 `OnlyBotRemains` 逻辑）。

### 2026-05-22 主动消息方案大重构：ContinueConversationAsync + 会议聊天支持

#### 背景

2026-05-21 记录的 `Bot Framework Connector CreateConversationAsync` 方案在实测中持续返回 BadRequest，始终无法发出消息。经过多轮排查（见"问题历程"），最终定位为两个独立根因并彻底重写消息发送管道。

#### 问题历程（按时间顺序）

1. **会议聊天发消息 403 `Teamwork.Migrate.All`**
   - 原路径：Graph `POST /chats/{threadId}/messages`
   - 原因：该接口对应用级权限（daemon app）只开放给 `Teamwork.Migrate.All`（数据迁移专用），普通 Bot 无法使用。
   - 修复：改为 Bot Framework `ContinueConversationAsync`，完全绕开 Graph 消息接口。

2. **会议聊天发消息 403（Bot 不在会议聊天名单）**
   - 原因：Bot 通过 Calling API 入会（音视频层），与 Bot 是否在会议聊天**消息名单**完全独立。Graph Calling API 入会不会自动把 Bot 加入会议聊天。
   - 修复：入会成功后 fire-and-forget 调用 `EnsureBotInMeetingChatAsync`，通过 `POST /chats/{threadId}/installedApps` 将 Bot 安装到会议聊天；Teams 随后发出 `conversationUpdate`，`MessageBot.OnMembersAddedAsync` 捕获并缓存 `ConversationReference`。

3. **Azure AD 权限名称错误**
   - 误写为 `TeamsAppInstallation.ReadWriteSelfForGroupChat.All`，Azure Portal 搜索无结果。
   - 正确权限：`TeamsAppInstallation.ReadWriteSelfForChat.All`（应用权限）。

4. **个人私聊 BadRequest（Graph filter 缺 ConsistencyLevel 头）**
   - `EnsureBotInstalledForUserAsync` 使用 `$filter=teamsApp/id eq '...'` 查询用户已安装 App；
   - Graph 对**导航属性**的 filter 查询必须带 `ConsistencyLevel: eventual` 请求头，否则返回 400；
   - 原代码未加该头，所有"是否已安装"预检查都失败 → BadRequest。

5. **消息发送静默成功（实际未送达）**
   - `AdapterWithErrorHandler.OnTurnError` 捕获了 `SendActivityAsync` 抛出的异常，不再向上抛出；
   - `ContinueConversationAsync` 返回时 Bot 服务端认为"成功"，上层代码没有感知到失败。
   - 修复：在 callback 内用闭包变量 `deliveryError` 捕获异常，`ContinueConversationAsync` 结束后检查并重新抛出。

6. **单租户 Bot 主动消息 BadRequest（缺 `tid` claim）**
   - `ContinueConversationAsync(string botId, convRef, callback)` 重载不附带租户信息，`ConfigurationBotFrameworkAuthentication` 无法解析单租户 token 端点，返回 400。
   - 修复：改用 `ClaimsIdentity` 重载，手动注入 `aud`、`appid`、`tid` 三个 claim，让认证层找到正确的租户专属端点。

7. **Bot 重启后旧代码继续运行（DLL 文件锁）**
   - `stop-all.bat` 只按端口（3978）杀进程，杀的是 dotnet 子进程（app）；
   - 父进程 `dotnet run`（用于 MSBuild 编译协调）继续存活，持有 `bin/Debug/net6.0/CallingBotSample.dll` 的文件锁；
   - 此时 `dotnet build` 编译虽成功，但无法将新 DLL 从 `obj/` 复制到 `bin/`；
   - 下次 `start-all` 运行的仍是旧 DLL。
   - 修复：`stop-all.bat` 改用 `taskkill /F /T /FI "WINDOWTITLE eq syncLingo Teams Bot"` 杀整个进程树（`/T` = 包含所有子进程）；`start-all.bat` 在启动前加显式 `dotnet build` 步骤，失败立即报错退出，启动改为 `dotnet run --no-build`。

#### 新完成

- **`ChatService` 全面重写**（消息发送管道）：
  - 删除旧的 `CreateProactiveConversationIdAsync` / `SendMessageToConversationAsync` / `BotFrameworkTokenCredentials` / `ConnectorClientFactory` 方案。
  - 新增 `ExecuteContinueConversation`：使用 `ClaimsIdentity`（含 `aud`、`appid`、`tid`）调用 `((CloudAdapter)adapter).ContinueConversationAsync`，捕获 callback 内的发送异常并重新抛出，日志记录完整的 ConversationReference 字段。
  - `SendMessageToUserAsync` 逻辑：
    1. 优先使用缓存的 `ConversationReference`（用户上次给 Bot 发消息时存储）；
    2. 无缓存时，执行"安装 + 触发 conversationUpdate + 轮询"流程（官方 graph-proactive-installation 示例模式）：
       - `InstallBotForUserAsync`：直接 POST 安装，409 则忽略，安装后带 `ConsistencyLevel: eventual` 头重查 installId；
       - `TriggerConversationUpdateAsync`：GET `/users/{id}/teamwork/installedApps/{installId}/chat`，促使 Teams 向 Bot 发 `conversationUpdate`；
       - `PollConversationReferenceAsync`：每 500ms 检查缓存，最多等待 8 秒。
  - 删除旧 `EnsureBotInstalledForUserAsync`（有 filter 无 ConsistencyLevel 头）和 `GetPersonalChatIdAsync`（不再需要 chatId 路径）。
  - 修复 `EnsureBotInMeetingChatAsync`：去掉预检查 filter 查询（同样有 ConsistencyLevel 问题），直接 POST 安装，409 则忽略。

- **`MessageBot.OnMembersAddedAsync` 新增**：
  - 群聊/会议聊天（`IsGroup=true`）：按 `conversationId` 缓存 `ConversationReference` 供 `SendToMeetingChatAsync` 使用；
  - 个人聊天：按用户 AAD ID 缓存 `ConversationReference` 供 `SendMessageToUserAsync` 快速路径使用。

- **`CallCache` 扩展**：新增 `GetMeetingChatConversationReference` / `SetMeetingChatConversationReference`，按 `threadId` 缓存会议聊天 `ConversationReference`，8 小时过期。

- **`IChatService` + `MeetingSummaryService` 扩展**：新增 `SendToMeetingChatAsync` 和 `EnsureBotInMeetingChatAsync`，前端可通过 `POST /api/meetings/summary/chat` 将摘要发给所有会议参与者。

- **前端 `TeamsBotView.tsx`**：新增"发送到会议聊天"按钮，独立于"发送给参会人员"；会议聊天发送调用 `POST /bot-api/api/meetings/summary/chat`，无需填写收件人。

- **`start-all.bat` / `stop-all.bat` 修复**：
  - `stop-all.bat`：用 `taskkill /F /T /FI "WINDOWTITLE eq ..."` 杀整个进程树，确保 `dotnet.exe` 父进程也被终止，DLL 锁释放。
  - `start-all.bat`：清理阶段也改用 `taskkill /T`；新增显式 `dotnet build` 步骤，编译失败立即报错，避免用旧 DLL 启动；Bot 启动改为 `dotnet run --no-build`。

#### 新决策

- **主动消息永久改为 `ContinueConversationAsync` + `ClaimsIdentity`**，不再使用自定义 `ServiceClientCredentials` + `ConnectorClient`。
- `OnMembersAddedAsync` 缓存 `ConversationReference` 作为快速路径；install+poll 作为兜底，而非使用手动构造的 ConversationReference（手动构造在单租户场景下 serviceUrl 和 tenantId 容易出错）。
- Graph filter 查询导航属性时**必须**带 `ConsistencyLevel: eventual` 头；不确定时优先选择"直接操作 + 处理 409"模式，避免预查询。

#### 当前状态

| 功能 | 状态 | 说明 |
|---|---|---|
| Bot 入会 | ✅ 已验证 | `POST /api/meetings/join` 正常工作 |
| 参会人员列表 | ✅ 已验证 | 显示名称通过 `User.Read.All` 解析 |
| 个人私聊发消息 | ⏳ 待验证 | 新 `ContinueConversationAsync` 方案已实现，需重启后测试 |
| 会议聊天发消息 | ⏳ 待验证 | 需 Bot 重新加入会议后测试 |
| start/stop-all 修复 | ✅ 已完成 | DLL 文件锁问题已修复 |

#### 阻塞

- 无代码级阻塞；需要重启 Bot 并重新加入会议后端到端验证。

---

## 12. 当前测试步骤（2026-05-22）

### 前置条件

- Bot 进程**必须完全停止后**再通过 `start-all.bat` 重新启动，否则旧代码仍在运行。
- `start-all.bat` 现在会在启动 Bot 前执行 `dotnet build`，编译失败会立即报错并停止。

### 第一步：启动完整栈

```bat
stop-all.bat   :: 确保完全停止旧进程（新版会杀整个进程树）
start-all.bat  :: 重新编译 Bot 并启动所有服务
```

观察 `start-all.bat` 输出：
- `[5/6] Building and starting frontend and Teams bot...` 后应出现 `Teams bot build OK.`
- 不应出现任何 `[ERROR]` 行

### 第二步：让 Bot 加入会议

1. 打开 `http://localhost:5173`，进入 **Teams Bot** 页面。
2. 填入完整 Teams 会议链接（`https://teams.microsoft.com/l/meetup-join/...`），点击"加入会议"。
3. 在 Bot 控制台日志中确认：
   ```
   Bot joined meeting via REST. callId=..., threadId=19:meeting_...
   [ChatService] EnsureBotInMeetingChat start, threadId=...
   [MessageBot] Stored meeting chat ConversationReference, conversationId=...
   ```
   - 前两行表示入会成功；
   - 第三行表示 Teams 向 Bot 发出了 `conversationUpdate`，Bot 已缓存会议聊天 ConversationReference（这是发消息到会议聊天的前提）。
   - **如果第三行没有出现**：等待约 10 秒后再测试，或确认 Teams App 的 `CatalogAppId` 配置正确（`appsettings.json` 中 `Bot:CatalogAppId` = `99867208-21ac-40ec-b946-3fe2f9659006`）。

### 第三步：测试"发送到会议聊天"

1. 在前端 Teams Bot 页面，填入任意摘要内容。
2. 点击"**发送到会议聊天**"按钮。
3. 成功标志：
   - 前端显示"摘要已发送到会议聊天，所有参会人员可见"。
   - Bot 控制台出现：
     ```
     [ChatService] SendToMeetingChat start, threadId=...
     [ChatService] Using cached meeting chat ConversationReference, threadId=...
     [ChatService] ContinueConversation start — convId=..., isGroup=True, ...
     [ChatService] Message sent via adapter. conversationId=...
     ```
   - Teams 会议聊天中出现摘要消息，所有参会人员可见。
4. 失败排查：
   - 错误 `Bot is not installed in meeting chat`：第二步的第三行日志未出现，说明 `conversationUpdate` 未收到。重新执行第二步（重新加入会议）。
   - 错误 `BadRequest` / `Unauthorized`：检查 Bot 控制台中 `[ChatService] ContinueConversation start` 之后的日志，找 `SendActivityAsync failed` 行，查看 HTTP 状态码和响应体。

### 第四步：测试"发送给参会人员"（个人私聊）

1. 在前端 Teams Bot 页面，点击"获取参会人员"（或直接在收件人栏填入 Teams 用户邮箱）。
2. 填入摘要内容，点击"**发送给参会人员**"。
3. 成功标志（快速路径，用户曾给 Bot 发过消息）：
   ```
   [ChatService] SendMessageToUser start, identifier=...
   [ChatService] Using cached ConversationReference for aadId=...
   [ChatService] Message sent via adapter. conversationId=...
   ```
4. 成功标志（install+poll 路径，用户从未与 Bot 聊过）：
   ```
   [ChatService] No cached ConversationReference, running install+poll flow for aadId=...
   [ChatService] Installing bot for user ...
   [ChatService] Triggering conversationUpdate via GET /chat for user ...
   [MessageBot] Stored personal ConversationReference for aadId=...
   [ChatService] ConversationReference available after Xms for aadId=...
   [ChatService] Message sent via adapter. conversationId=...
   ```
5. 失败排查：
   - 错误 `Bot was installed for user ... but no conversationUpdate arrived within 8s`：Teams 没有在 8 秒内发 `conversationUpdate`。这种情况罕见但可能发生（Teams 网络延迟）。解决办法：让用户先在 Teams 中找到 `meeting bot` 并发一条消息给 Bot，之后发送会走快速路径。
   - 错误 `BadRequest`：检查 Bot 控制台 `SendActivityAsync failed` 日志，查看 HTTP 响应体，确认 `ClaimsIdentity` 中的 `appid` 和 `tid` 与 `appsettings.json` 中的 `Bot:AppId` 和 `AzureAd:TenantId` 一致。

### 第五步：验证个人聊天查询（Bot 聊天功能）

1. 在 Teams 中找到 `meeting bot`，发送 `最近`。
2. 预期 Bot 回复最近几条会议记录（按 Teams 账号关联的 syncLingo 用户历史）。
3. 发送 `搜索 关键词` 或 `摘要 <sessionId>`，测试查询功能。
4. 失败排查：Bot 回复"暂无相关会议记录"时，确认 Java 后端的 `si_user.email` 与 Teams 账号 UPN/mail 匹配。

### 注意事项

- **Bot 重启后缓存清空**：每次重启 Bot 后，必须重新执行第二步让 Bot 加入会议；如果会议 App 已安装但本进程没有收到 `conversationUpdate`，新版会使用 meeting threadId 构造 ConversationReference 兜底发送。
- **个人私聊快速路径依赖历史**：用户给 Bot 发过消息（第五步）后，之后的私聊发送会走快速路径，无需等 8 秒轮询。建议先完成第五步再测第四步。
- **不需要重新配置 Azure AD 权限**：当前已授权的权限（`User.Read.All`、`TeamsAppInstallation.ReadWriteSelfForUser.All`、`TeamsAppInstallation.ReadWriteSelfForChat.All`）已满足所有发送路径需求。

---

### 2026-05-22 主动消息二次修复：TextFormat + ActivityId + meeting thread fallback

- 新发现：
  - 本次通过 `start-all.bat` 启动后，`%TEMP%\bot.log` 显示会议聊天发送失败点不是 Graph 安装失败：Graph 直接查询 `GET /chats/{threadId}/installedApps` 返回 `meeting bot` 已安装，手动 `POST /installedApps` 返回 409 `AppEntitlementAlreadyExists`。
  - 会议聊天失败的真实原因是：当前 Bot 进程没有缓存 meeting chat 的 `ConversationReference`；当 App 已安装时，Teams 不一定会再次向新进程发送 `conversationUpdate`。
  - 个人私聊路径中，Graph personal scope 安装成功，`GET /installedApps/{installId}/chat` 也触发了 `conversationUpdate`，日志出现 `[MessageBot] Stored personal ConversationReference`；失败发生在 `SendActivityAsync`，Bot Framework 返回 400。
  - Teams Bot 消息 `TextFormat` 官方只支持 `plain` / `markdown` / `xml`，旧代码设置 `TextFormat = "html"`，这是 400 的高概率原因。
  - `ConversationReference` 从 `conversationUpdate` 缓存时会带 `ActivityId`，主动消息如果沿用该值，Bot Framework 可能把消息当成 reply 发到 `/activities/{activityId}`；新版发送前清空 `ActivityId`，让消息作为新的 proactive activity 发送。
- 新完成：
  - `ChatService.ExecuteContinueConversation()` 改为把简单 HTML 归一化为 Teams Markdown，并设置 `TextFormatTypes.Markdown`。
  - 发送前统一构造 proactive 专用 `ConversationReference`，清空 `ActivityId`，保留 Teams 原始 `serviceUrl`、conversationId、tenantId。
  - `SendToMeetingChatAsync()` 在没有缓存 meeting chat reference 时，会先调用 `EnsureBotInMeetingChatAsync()`，再使用 meeting `threadId` + tenant service URL 构造兜底 ConversationReference 发送。
  - `EnsureBotInMeetingChatAsync()` 对 409 已安装改为 info 日志，并记录耗时；补充非 Graph 异常日志，避免 fire-and-forget 异常静默。
  - `JoinMeeting()` 改为等待 `EnsureBotInMeetingChatAsync()` 完成后再返回，确保安装结果能进入日志。
  - `IChatService` 注释从 HTML 消息改为 Teams text / Markdown 消息，避免继续误用 unsupported `html` TextFormat。
- 新决策：
  - 会议聊天发送不再把 `conversationUpdate` 缓存作为唯一前置；缓存优先，threadId fallback 作为重启后兜底。
  - Teams Bot 主动消息文本统一按 Markdown 发送；需要复杂展示时应改用 Adaptive Card，而不是把任意 HTML 放进 `TextFormat`。
- 验证：
  - C# Bot `dotnet build` 通过（0 error，既有 nullable warning 不影响）。
  - `start-all.bat` 本次在 Docker build export 阶段遇到 Docker Desktop snapshot/cache 错误：`parent snapshot ... does not exist`；该问题与 Teams Bot 代码无关。已使用已生成的 `si-backend:latest` 镜像手动拉起 backend、frontend、Bot、ngrok。
  - 当前 Bot 已重新启动并监听 `http://0.0.0.0:3978`。
- 下一步：
  - 重新让 Bot 加入会议后，分别测试"发送到会议聊天"和"发送给参会人员"。
  - 若仍返回 400，优先看 `%TEMP%\bot.log` 中 `[ChatService] SendActivityAsync failed` 后的 HTTP body。

---

## 13. 后续更新模板

```markdown
### YYYY-MM-DD

- 新完成：
- 新发现：
- 新决策：
- 阻塞：
- 下一步：
```
