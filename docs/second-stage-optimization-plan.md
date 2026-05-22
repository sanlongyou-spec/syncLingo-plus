# 第二阶段优化计划

> 本文作为第一轮优化完成后的第二阶段更新计划。第二阶段先补齐用户级语言资产能力，再逐步接入类似 Notta 的 Teams 会议 Bot 能力。目标是在不破坏现有实时同传链路的前提下，先让 ASR 热词和术语表按用户隔离、可独立维护，再让系统可以进入 Teams 会议、获取会议音频、识别说话人，并复用当前 ASR、翻译、压缩、TTS、字幕、会议纪要能力。

## 1. 目标

1. 为每个用户提供独立的术语表和 ASR 热词表，禁止不同用户之间默认共享。
2. 为每个用户提供默认语言配置，默认优先服务中文 / 印尼语会议，英语只在特殊会议中启用。
3. 使用 ASR 热词提升人名、公司名、项目名、品牌名、缩写和近期热点词识别率。
4. 为中文到印尼语、中文到英语分别设计压缩策略，在保证语义完整的前提下降低字幕和播报长度。
5. 在功能逐渐复杂后，升级前端信息架构和视觉体验，保证配置能力增强的同时不削弱实时同传主流程。
6. 支持通过 Teams Bot 加入 Microsoft Teams 会议。
7. 获取会议音频，并接入现有实时同传链路。
8. 获取会议参与者、当前发言人、说话人标识，用于字幕、翻译结果、会议记录和音色策略。
9. 支持会后拉取 Teams transcript，生成会议纪要、待办事项、关键词和历史记录。
10. 保持现有浏览器采集、VoiceMeeter 路由、WebSocket 同传能力可独立使用。

## 2. 用户级语言资产

### 2.1 术语表用户隔离

目标：

- 每个用户只看到、维护和使用自己的术语表。
- 系统预置公共词典可以作为模板导入，但导入后进入用户自己的空间。
- 默认不允许 A 用户的术语影响 B 用户的翻译结果。

实施要求：

1. `terminology` 表增加 `user_id` 字段。
2. 术语查询、新增、编辑、删除、启停全部按 `user_id` 过滤。
3. 当前已导入的公共词典可通过“复制到我的术语表”或初始化脚本分发给用户，而不是作为所有用户实时共享的唯一数据源。
4. Google Glossary 生成逻辑按用户维度输出，或至少按 `user_id + language_direction` 生成独立 glossary resource。
5. 前端术语页只展示当前登录用户的数据。

建议字段：

```sql
ALTER TABLE terminology
ADD COLUMN user_id BIGINT NOT NULL AFTER id,
ADD INDEX idx_user_enabled (user_id, enabled);
```

### 2.2 ASR 热词

目标：

- 在 ASR 阶段提升用户关心的近期热点词、专有名词和缩写识别率。
- ASR 热词与术语表分开管理，因为它们解决的问题不同：
  - 术语表：约束翻译结果。
  - ASR 热词：提升语音识别阶段命中率。

推荐实现：

1. 使用 Azure Speech SDK `PhraseListGrammar`。
2. 在 `ConversationTranscriber` 启动时，为当前用户加载启用的 ASR 热词。
3. 热词优先支持：
   - 人名
   - 公司名
   - 项目名
   - 品牌名
   - 地名
   - 缩写
   - 当前会议临时词
4. 不把整本术语词典全部塞入 ASR；应按用户、场景、会议选择构造会话级热词集。
5. 支持权重、分类、启停、有效期和最近使用时间。

建议数据表：

```sql
CREATE TABLE asr_hotword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    phrase VARCHAR(255) NOT NULL,
    language VARCHAR(16),
    category VARCHAR(64),
    weight DECIMAL(4, 2) DEFAULT 1.00,
    enabled TINYINT DEFAULT 1,
    expires_at DATETIME DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_enabled (user_id, enabled),
    INDEX idx_expires_at (expires_at)
);
```

前端要求：

- 在“术语表”附近增加“ASR 热词”独立 Tab，不能混成一个概念。
- 支持新增、编辑、删除、启停、搜索、按语言筛选、按分类筛选。
- 支持把某个术语一键复制为 ASR 热词，但不自动双向绑定。
- 启动同传前允许选择“本次会话要启用的热词集合”。

### 2.3 用户隔离通用规则

1. `terminology` 和 `asr_hotword` 都必须带 `user_id`。
2. 所有 Controller / Service / Mapper 查询都必须显式带用户条件。
3. 所有批量导入都必须写入目标用户，不允许缺省导入到全局空间。
4. 会议开始时只加载当前用户可见的数据。
5. 后续如需公共模板，单独设计 `template` 或 `system_dictionary` 概念，不和用户私有数据混存。

### 2.4 用户默认语言配置

目标：

- 大多数会议默认只启用中文和印尼语，减少无效翻译、无效 TTS 和前端干扰。
- 英语保留为可选语种，只在特殊会议中临时启用。
- 每个用户可维护自己的默认语言偏好，不同用户之间相互隔离。

实施要求：

1. 新增用户级默认语言配置，至少包含：
   - `default_source_lang`
   - `enabled_languages`
2. 默认值：
   - `default_source_lang = auto`
   - `enabled_languages = zh-CN,id-ID`
3. 启动同传时允许前端临时覆盖本次会议启用语种。
4. 会议开始后，将本次实际启用语种固化到 session，后续 ASR 自动识别范围、翻译目标语和 TTS 都以 session 配置为准。
5. 自动同传模式下至少保留两个启用语种，避免出现“只有源语言，没有目标语言”的无效配置。
6. 前端提供“保存为默认”能力，同时允许用户在单次会议中临时打开英语。

验收标准：

- 新用户默认得到 `zh-CN + id-ID`。
- 用户保存默认语言后，下次进入同传页自动带出。
- 只启用中印双语时，中文输入只翻译到印尼语，不再额外生成英语字幕和英语 TTS。
- 临时启用英语后，本次会议可恢复三语能力，但不改变用户长期默认，除非显式点击“保存为默认”。

## 3. Teams 官方能力判断

### 3.1 实时会议 Bot

Microsoft Teams 支持通过 Microsoft Graph Cloud Communications API 创建可以参与通话和会议的 Bot。实时音视频能力需要 Real-time Media Platform。

可实现能力：

- Bot 作为会议参与者加入 Teams 会议。
- 获取实时音频帧、视频帧、屏幕共享帧。
- 获取参与者列表、参与者状态、静音状态、active speaker 等会中信号。
- Bot 可播放音频回会议，也可以在会议聊天中发送消息。

主要限制：

- Application-hosted media bot 主要依赖 Microsoft Graph Communications SDK / Media SDK，官方生态以 .NET 为主。
- 实时媒体 Bot 通常需要运行在 Windows Server 或 Azure Windows 环境。
- 获取和持久化会议媒体内容涉及合规要求，必须确认租户策略、会议参与者告知和数据保存策略。
- Microsoft 官方文档更推荐普通 AI 会议智能场景优先使用会议 transcript API，而不是直接处理实时媒体。

### 3.2 会后 transcript API

Microsoft Graph 支持在会议开启转录后，会议结束后拉取 transcript 和 recording 相关资源。

可实现能力：

- 获取会议转录文本。
- 获取说话人、时间戳、语句内容。
- 生成会后纪要、待办事项、发言人摘要。
- 与现有历史记录、会议纪要、术语和统计能力打通。

主要限制：

- 依赖 Teams 会议开启转录，且需要租户管理员授权 Graph 权限。
- 不是实时能力，适合作为低风险 MVP。

## 4. 推荐实施路线

### 阶段 0：用户级语言资产

目标：先完成术语表和 ASR 热词的用户隔离，再继续做更复杂的 Teams 接入。

当前状态：已完成。

任务：

1. 为术语表增加 `user_id`，完成后端和前端隔离。
2. 新增 `asr_hotword` 表及其 CRUD API。
3. 前端新增 ASR 热词管理页或术语页内独立 Tab。
4. 在 ASR session 启动时注入当前用户启用的热词。
5. 为术语表和 ASR 热词分别增加导入、启停、搜索和日志验证能力。
6. 增加用户级 glossary 配置，按 `user_id + language_direction` 选择 glossary。
7. 增加会议级热词选择，允许启动同传前挑选本次启用的热词。
8. 为热词增加语种筛选、分类筛选、批量导入和最近使用时间。
9. 增加用户默认语言配置，默认启用中文和印尼语，英语按需临时打开。

验收标准：

- A 用户新增术语或热词后，B 用户不可见、不可用。
- 当前用户启用的热词在 ASR 启动日志中可见数量，但不打印完整词表。
- 包含热词的口述测试中，识别准确率明显优于未启用热词时。
- 术语表仍只影响翻译，不直接等同于 ASR 热词。
- 用户默认语言配置可持久化，会议级语言配置可覆盖默认值但不会污染其他用户。

### 阶段 0.5：实时链路精细化与前端体验升级

目标：在继续扩展外部集成之前，先把当前同传产品打磨到更适合长期使用的状态。

当前状态：已完成（不包含 Teams 相关阶段）。

#### 0.5.1 中文到英语压缩

目标：

- 中文翻译成英语时也启用压缩。
- 英语压缩力度低于印尼语，减少过度删减对信息完整性的影响。

策略：

1. 保留现有 `zh -> id` 压缩策略。
2. 新增独立的英语压缩 prompt，不允许复用印尼语 prompt。
3. `zh -> en` 也进入压缩链路，但目标压缩比例应比 `zh -> id` 低约 10%。
4. 建议通过独立配置项控制：
   - `compression.zh-to-id.target-ratio`
   - `compression.zh-to-en.target-ratio`
   - `compression.zh-to-en.enabled`
5. 压缩失败时继续回退到原始英语译文，不影响字幕和 TTS。
6. 日志必须分别区分 `zh->id` 与 `zh->en` 压缩方向、模型、耗时、原始长度和压缩后长度。

建议初始值：

- `zh -> id`：沿用现有目标比例。
- `zh -> en`：目标压缩比例比印尼语低 10%，例如若印尼语目标为 70%，英语可先以 80% 起测。

验收标准：

- 长文本 `zh -> en` 出现独立压缩日志。
- `zh -> en` 与 `zh -> id` 使用不同 prompt。
- 英语压缩后仍保持英语输出，不得出现印尼语串出。
- 英语压缩后的信息保留度高于印尼语压缩策略，人工抽检不应出现明显信息缺失。

#### 0.5.2 前端体验升级

目标：

- 功能增加后，前端仍应首先服务“开始同传、查看字幕、听到语音”这一主任务。
- 术语、热词、语言配置、历史、纪要等能力要更好找，但不能挤压实时同传主界面。

设计原则：

1. 首屏仍然以实时同传为中心，不改成后台系统式首页。
2. 采用清晰的主次分层：
   - 主区：实时字幕、当前会话状态、开始 / 停止同传。
   - 次区：本次会议设置，如语言、热词、音色。
   - 管理区：术语、glossary、历史、纪要、后续 Teams 能力。
3. 功能入口要更精致、更易扫读：
   - 顶部导航精简；
   - 会前配置收纳为明确的设置面板；
   - 高频操作靠近开始按钮；
   - 低频管理能力移出主流程。
4. 页面应统一视觉系统：
   - 统一颜色、间距、层级、按钮、表单、标签和空状态；
   - 保持专业、克制、适合会议场景；
   - 不使用会分散注意力的装饰性布局。
5. 桌面端和移动端都要保证字幕区优先级最高，不能被配置面板挤压到难以阅读。
6. 后续如接入 Teams，预留“会前配置 / 会中状态 / 会后记录”扩展位，不再把所有功能继续堆进同一页。

建议页面结构：

1. `InterpretationView`
   - 主字幕区
   - 会前快速设置
   - 运行中状态栏
2. `TerminologyView`
   - 术语
   - ASR 热词
   - Glossary
3. `HistoryView`
   - 历史会话
   - 纪要
   - 导出
4. 后续可新增：
   - `SettingsView`
   - `TeamsView`

验收标准：

- 用户进入首页后无需跳转即可开始同传。
- 启动前可以快速确认本次语言、热词和音色。
- 功能增加后，主字幕区仍然是首屏视觉中心。
- 常用操作不超过两步可达。
- 桌面和窄屏下无明显重叠、遮挡和布局断裂。

### 阶段 1：Teams Transcript 导入 MVP

目标：先不做实时入会音频，优先打通低风险的会后会议记录能力。

任务：

1. 在 Azure / Microsoft Entra ID 注册应用。
2. 申请 Graph transcript 相关权限，并完成管理员同意。
3. 后端新增 Teams 集成配置。
4. 新增 Teams meeting transcript 拉取服务。
5. 将 Teams transcript 转换为现有 `interpretation_record` 或独立 `meeting_transcript_record`。
6. 复用现有会议纪要生成接口，生成摘要、重点事项、待办事项。
7. 前端历史记录页增加 Teams 导入入口。

验收标准：

- 能按 meetingId 或 organizer + meeting 时间拉取 transcript。
- 记录中保留 speaker、startTime、endTime、text。
- 能生成会议纪要。
- 日志只记录 meetingId、speakerId、textLen、costMs，不打印完整敏感原文。

### 阶段 2：Teams Bot 入会 PoC

目标：验证 Bot 能否加入会议，并获取参与者和会中事件。

任务：

1. 创建 Teams App manifest，声明 Bot 支持 calling。
2. 配置 Bot webhook callback。
3. 使用 Microsoft Graph Cloud Communications API 加入指定会议。
4. 记录 callId、meetingId、participantId、active speaker 事件。
5. 验证会议组织者是否能看到并允许 Bot 入会。
6. 明确 Bot 入会提示和合规文案。

### 阶段 3：实时音频桥接服务

目标：用独立 `.NET Teams Media Bridge` 获取实时音频，再转发给现有 Java 后端。

建议架构：

```text
Teams Meeting
  -> Teams Media Bot / .NET Bridge
  -> PCM audio frames + speaker/participant events
  -> Java Backend WebSocket/gRPC ingest
  -> Azure ASR / Translate / OpenAI Compression / Cartesia TTS
  -> Frontend subtitles / shared page / optional Teams chat
```

### 阶段 4：实时会议助手能力

可选功能：

1. 将实时翻译字幕发送到我们的共享页。
2. 将关键翻译或纪要片段发送到 Teams meeting chat。
3. 会中实时生成双语/三语发言记录。
4. 会后自动生成会议纪要、待办事项、发言人摘要。
5. 按 Teams 发言人绑定音色策略。
6. 支持会议开始前配置目标语言、术语表、ASR 热词、是否启用压缩、是否启用 TTS。

## 5. 与现有项目的集成点

后端新增模块建议：

- `AsrHotwordService`
- `AsrHotwordController`
- `AsrHotwordMapper`
- `TeamsGraphIntegration`
- `TeamsTranscriptService`
- `TeamsMeetingBotService`
- `TeamsAudioIngestController` 或 `TeamsAudioWebSocketHandler`
- `TeamsSpeakerMappingService`

前端新增能力建议：

- 术语表用户隔离。
- ASR 热词管理入口。
- 用户默认语言配置。
- 同传主界面信息架构升级。
- 会前快速配置面板。
- Teams 会议导入入口。
- Teams 会议 Bot 状态面板。
- Teams speaker 显示。
- Teams 会议历史筛选。

## 6. 配置与权限

需要准备：

1. Microsoft 365 租户。
2. Microsoft Entra ID 应用注册。
3. Teams App manifest。
4. Bot Framework / Graph Communications Bot 配置。
5. 管理员同意 Graph 权限。
6. 对外可访问的 HTTPS callback 地址。

可能需要的权限方向：

- `OnlineMeetings.Read`
- `OnlineMeetings.ReadWrite.All`
- transcript / recording 相关权限
- `Calls.JoinGroupCall.All`
- `Calls.JoinGroupCallAsGuest.All`
- `Calls.AccessMedia.All`

## 7. Teams 接入后的具体实施事项

如果决定正式接入 Teams，建议不要一上来就做实时媒体 Bot，而是按“先低风险数据接入，再实时入会”的顺序推进。

### 7.1 先完成组织侧准备

1. 确认 Microsoft 365 租户、Teams 管理员和 Entra ID 管理员。
2. 确认目标租户是否允许第三方应用、Bot 入会、应用访问策略和 transcript 拉取。
3. 准备一个可长期使用的公网 HTTPS 域名，用于 OAuth 回调、Bot webhook 和后续媒体桥接。
4. 明确合规边界：
   - 是否允许录音、转写、摘要和保存；
   - 会议成员是否需要显式提示；
   - transcript、音频、摘要各自保留多久；
   - 日志中哪些字段可以留、哪些原文绝不能落盘。
5. 确认第一批目标场景：
   - 仅会后摘要；
   - 会后摘要 + 历史归档；
   - 实时字幕；
   - 实时字幕 + 语音播报；
   - 是否真的需要 Bot 自动入会。

### 7.2 第一阶段先做 Transcript MVP

优先级最高，因为它最容易证明 Teams 接入价值，也最不容易破坏现有实时同传链路。

需要完成：

1. 在 Entra ID 注册应用，完成 client credential flow。
2. 申请 transcript 相关 Graph 权限，并由管理员同意。
3. 新增 Teams 配置模块：
   - tenantId
   - clientId
   - clientSecret / certificate
   - authority
   - scope
4. 新增 `TeamsGraphIntegration`：
   - 获取 access token；
   - 查询 meeting；
   - 拉取 transcript；
   - 做错误码、重试和限流处理。
5. 新增 `TeamsTranscriptService`：
   - 将 transcript 转成内部统一结构；
   - 保留 speaker、startTime、endTime、textLen；
   - 映射到 `meeting_transcript_record` 或复用现有会议记录表。
6. 前端历史页增加“导入 Teams 会议记录”入口。
7. 将 transcript 接到现有会议纪要生成链路，输出：
   - 摘要；
   - 待办；
   - 关键词；
   - 发言人摘要。

完成标志：

- 可以通过 meetingId 拉到 transcript；
- 可以生成会议纪要；
- 可以在历史记录里查看；
- 日志不打印完整会议原文。

### 7.3 第二阶段做 Teams Bot 入会 PoC

Transcript MVP 跑通后，再验证实时 Bot 是否可行。

需要完成：

1. 创建 Teams App manifest，并声明 Bot 支持 calling。
2. 配置 Bot webhook callback 和公网证书。
3. 新增 `TeamsMeetingBotService`：
   - 发起 join meeting；
   - 保存 callId、meetingId、tenantId；
   - 处理 call state webhook；
   - 处理 participant joined / left；
   - 处理 active speaker 事件。
4. 新增 Bot 状态页：
   - 未加入；
   - 正在加入；
   - 已连接；
   - 已离开；
   - 错误状态。
5. 先只验证：
   - Bot 能否被组织者看见；
   - Bot 能否进入会议；
   - 能否拿到参与者列表；
   - 能否拿到 active speaker 事件。
6. 同步确认会议侧提示语、隐私说明和管理员策略。

完成标志：

- Bot 可以稳定进入测试会议；
- 能拿到 participant 与 active speaker 事件；
- 会议结束后可以正常清理 call；
- 失败时能从日志定位原因。

### 7.4 第三阶段再做实时媒体桥接

这一步是 Teams 接入里最重的部分，建议独立成服务，不直接塞进现有 Java 后端。

需要完成：

1. 新建独立 `.NET Teams Media Bridge` 服务。
2. 在 Windows Server 或 Azure Windows 环境部署媒体桥接服务。
3. 通过 Teams Real-time Media Platform 获取：
   - PCM 音频帧；
   - participant / speaker 事件；
   - call 生命周期事件。
4. 定义桥接协议，把音频和 speaker 事件转发到 Java 后端：
   - WebSocket 或 gRPC；
   - 带 sessionId、participantId、speakerId、timestamp；
   - 统一采样率和声道格式。
5. Java 后端新增 `TeamsAudioIngestController` 或 `TeamsAudioWebSocketHandler`。
6. 把 Teams 音频接入现有链路：
   - Azure ASR；
   - 术语；
   - 压缩；
   - 翻译；
   - TTS；
   - 字幕；
   - 历史记录。
7. 新增 `TeamsSpeakerMappingService`：
   - participantId -> speakerId；
   - speakerId -> 显示名；
   - speakerId -> 音色策略。

完成标志：

- Teams 会议中的真人发言可进入当前 ASR 链路；
- 字幕能显示发言人；
- 翻译、TTS、历史记录能继续复用现有模块；
- 断线、退会、重连时能正确清理。

### 7.5 第四阶段做产品化能力

1. 会前配置：
   - 目标语种；
   - 本次会议启用的术语表；
   - 本次会议启用的 ASR 热词；
   - 是否启用压缩；
   - 是否启用 TTS；
   - 是否启用会后摘要。
2. 会中能力：
   - 实时多语字幕；
   - 发言人标识；
   - 关键句推送；
   - 可选 Teams chat 消息；
   - 共享页同步展示。
3. 会后能力：
   - 自动摘要；
   - 待办；
   - 关键词；
   - 发言人摘要；
   - transcript 与翻译结果归档。
4. 管理能力：
   - Teams 会议历史筛选；
   - Bot 调用记录；
   - transcript 导入记录；
   - 错误重试；
   - 权限和租户配置检查。

### 7.6 推荐实施顺序

1. 先补完阶段 0 剩余项：
   - 用户级 glossary；
   - 会议级热词选择；
   - 热词高级筛选和批量导入。
2. 再做 `Teams Transcript MVP`。
3. 然后做 `Teams Bot 入会 PoC`。
4. 最后再决定是否值得投入 `实时媒体桥接`。

### 7.7 当前项目中建议新增的模块

后端：

- `TeamsProperties`
- `TeamsGraphIntegration`
- `TeamsTranscriptService`
- `TeamsTranscriptController`
- `TeamsMeetingBotService`
- `TeamsAudioIngestController` 或 `TeamsAudioWebSocketHandler`
- `TeamsSpeakerMappingService`
- `MeetingTranscriptRecord`
- `MeetingTranscriptRecordMapper`

前端：

- Teams 连接配置页；
- 历史页中的 Teams 导入入口；
- Bot 状态面板；
- 会议详情页中的 speaker 视图；
- 会前配置页中的术语 / 热词选择。

## 8. 风险与约束

1. 实时 Teams Media Bot 工程复杂度高，建议独立服务实现，不直接塞进 Java 后端。
2. Bot 入会可能需要会议组织者显式允许。
3. 不同租户的安全策略可能阻止第三方 Bot 入会或获取媒体。
4. Teams transcript 依赖会议开启转录。
5. 原始音频、转录文本和会议纪要都可能包含敏感信息，日志不得打印完整原文。
6. 任何录制、转写、保存、分析会议内容的能力，都需要明确用户告知和授权。
7. 术语表和 ASR 热词若不做用户隔离，会造成不同客户之间的数据串用，属于功能和隐私双重风险。
8. ASR 热词过多会降低维护性，也可能稀释热点词增强效果，应优先使用会话级精选热词。
9. 英语压缩若复用印尼语 prompt，会造成输出语言错误，必须使用独立 prompt。
10. 前端功能持续叠加若不重做信息架构，会逐渐损害实时同传主流程的可用性。

## 9. 测试计划

### 9.1 用户隔离测试

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "Terminology|AsrHotword|userId|loaded hotwords|ERROR"
```

通过标准：

- 用户 A 新增术语和 ASR 热词后，用户 B 查询不到。
- 用户 A 开始同传时，只加载用户 A 自己的启用热词。
- 用户 B 的翻译结果不受用户 A 术语表影响。

### 9.2 ASR 热词测试

场景：

1. 先关闭某个热点词。
2. 朗读包含该词的句子 5 次，记录识别结果。
3. 启用该热点词后重复同样测试。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "PhraseList|loaded hotwords|ASR recognized|sessionId|ERROR"
```

通过标准：

- ASR 启动时出现热词加载日志。
- 启用热词后，该词的识别命中率高于未启用时。
- 日志只记录热词数量、语言和用户，不打印完整热词内容。

### 9.3 用户默认语言配置测试

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/language-preferences?userId=1
```

通过标准：

- 新用户默认返回 `zh-CN,id-ID`。
- 保存默认值后再次查询可复现。
- 仅启用中印双语时，会话日志中的目标语不再包含英语。

### 9.4 中文到英语压缩测试

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "compress start|compress end|zh->en|zh->id|OpenAI response done|ERROR"
```

通过标准：

- 长文本 `zh -> en` 会触发压缩。
- `zh -> en` 压缩比例低于 `zh -> id` 压缩力度约 10%。
- 英语结果仍为英语，不出现印尼语输出。

### 9.5 前端体验升级验证

日志无法完全证明，优先通过浏览器实际操作验证：

- 首页可直接开始同传。
- 开始前可快速看到语言、热词、音色配置。
- 主字幕区在首屏中最突出。
- 管理功能可达，但不遮挡主流程。
- 桌面和窄屏下无明显重叠。

### 9.6 Teams Transcript MVP 测试

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "TeamsTranscript|fetch transcript|parse transcript|speakerCount|recordCount|generateSummary|ERROR"
```

### 9.7 Teams Bot 入会 PoC 测试

```powershell
docker logs --since 15m si-backend |
  Select-String -Pattern "TeamsBot|join meeting|call connected|participant|active speaker|call ended|cleanup|ERROR"
```

### 9.8 实时音频桥接测试

```powershell
docker logs --since 15m si-backend |
  Select-String -Pattern "TeamsAudioIngest|audio frame|speakerId|processFinalRecognition|translate end|sent tts_audio|ERROR"
```

## 10. 下一步建议

先完成阶段 0.5，再继续 Teams 相关阶段。原因是当前系统已经开始从单一实时链路走向可配置产品，必须先把默认语言、英语压缩和前端信息架构收稳，否则后续 Teams transcript、Teams bot、会议模板一旦接入，会把已有复杂度继续放大。完成阶段 0.5 后，再执行 Teams transcript 导入；实时 Teams Bot 继续作为后续独立 PoC，避免一开始就把高复杂度实时媒体能力耦合进当前稳定同传链路。
