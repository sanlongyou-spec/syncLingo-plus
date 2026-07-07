# 聚龙同传系统后续优化实施方案

> 本方案用于后续开发的执行基准。所有优化必须按阶段推进，每完成一个阶段先通过对应测试与验收，再进入下一阶段。

## 1. 实施原则

1. 优先级顺序：稳定性 > 延迟 > 英语扩展 > 准确率 > 字幕与会议记录 > 声音体验 > 基础管理能力。
2. 不做大规模架构重构，沿用现有分层：controller -> facade -> service -> integration -> mapper。
3. 每次变更必须保持可回滚：数据库变更单独提交，代码变更单独提交，配置变更单独记录。
4. 实时链路优先保证不中断、不挂死，再优化体验。
5. 后续每个功能完成后必须执行本文中的测试门禁。
6. 所有新增和修改代码必须遵守 `docs/coding-standards.md` 中定义的代码规范；若实施方案与代码规范冲突，以代码规范为准。

## 2. 当前系统基线

后端：

- Java 21 + Spring Boot 3.2.5
- WebSocket 路径：`/ws/asr`
- 实时编排核心：`RealtimeInterpretationFacade`
- ASR：`AzureAsrIntegration`
- 翻译：`GoogleTranslateIntegration` + `TranslationService`
- LLM 压缩：`LlmIntegration`
- TTS：`TtsService` + Cartesia WebSocket
- 持久化：MyBatis 注解 Mapper + MySQL

前端：

- React 18 + TypeScript + Vite
- 主界面：`InterpretationView`
- 音频采集：`AudioCapture`
- WebSocket 客户端：`AsrWebSocket`
- TTS 播放与 VoiceMeeter 路由：浏览器 `setSinkId()`

基线验证命令：

```powershell
cd si-frontend
npm.cmd exec tsc -- --noEmit

cd ..\si-backend
mvn -q -DskipTests compile
```

## 3. 阶段总览

| 阶段 | 目标 | 主要交付 | 是否允许进入下一阶段 |
|---|---|---|---|
| 第 0 阶段 | 建立基线与测试脚本 | 编译通过、现状确认、测试清单 | 必须 |
| 第 1 阶段 | 稳定性与延迟基础优化 | TTS 超时、健康检查、链路耗时、ASR/LLM 参数优化 | 必须 |
| 第 2 阶段 | 英语三语链路 | en-US ASR、三语翻译、英语 TTS、前端语言适配 | 必须 |
| 第 3 阶段 | 术语库与对话记录 | 术语表、记录表、翻译前后 hook、历史查询 | 必须 |
| 第 4 阶段 | 字幕、队列、会议纪要 | 三语字幕、队列积压控制、会议纪要基础版 | 必须 |
| 第 5 阶段 | 音色与基础管理统计 | 音色授权、使用记录、成本统计 | 可按实际进度拆分 |

## 4. 第 0 阶段：基线确认

### 4.1 目标

确认当前项目可编译、核心链路结构清楚、测试入口明确。

### 4.2 执行任务

1. 确认工作区状态，避免覆盖用户改动。
2. 执行前端 TypeScript 编译检查。
3. 执行后端 Maven 编译检查。
4. 确认当前启动脚本：
   - `start-all.bat`
   - `stop-all.bat`
   - 后续测试统一通过这两个脚本启动 / 停止前端、后端、Teams Bot 和 ngrok。
5. 记录当前已知差异：
   - 前端存在 `LoginView`，但路由未挂载 `/login`。
   - 前端 API 有 `/api/auth/login`，后端当前未见对应 controller。
   - WebSocket JWT 拦截器目前允许无 token 连接。
   - 当前音频采集实际使用 `getDisplayMedia`，不是麦克风 `getUserMedia`。

### 4.3 验收标准

- `npm.cmd exec tsc -- --noEmit` 通过。
- `mvn -q -DskipTests compile` 通过。
- 不修改业务代码。

## 5. 第 1 阶段：稳定性与延迟优化

### 5.1 TTS Future 30 秒超时保护

改动范围：

- `RealtimeInterpretationFacade`
- 必要时增加常量到 `Constants`

实施步骤：

1. 为每个 TTS 播放任务增加最大等待时间。
2. 当 TTS 合成、音频队列消费或播放链卡住超过 30 秒时，主动完成 `playFuture`。
3. 超时时发送错误日志，不阻塞后续 session 任务。
4. 确保 `audioQueue` 收到结束信号，避免消费者永久 `take()`。

验收标准：

- Cartesia 回调不返回时，TTS 链不会永久阻塞。
- 新的识别结果仍可继续排队播放。
- 停止 session 后资源能清理。

测试：

- 后端编译。
- 模拟 TTS 错误回调。
- 模拟 TTS 无完成回调。
- 连续触发 5 段识别，确认不会卡死。

### 5.2 健康检查

改动范围：

- `pom.xml`
- `application.yml`
- `HealthController` 或 Spring Boot Actuator

实施步骤：

1. 引入 `spring-boot-starter-actuator`。
2. 开放 `/actuator/health`。
3. 保留现有 `/api/health`，避免启动脚本或测试脚本失效。

验收标准：

- `/api/health` 返回 UP。
- `/actuator/health` 返回 UP。

测试：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/health
Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health
```

### 5.3 链路耗时日志

改动范围：

- `RealtimeInterpretationFacade`
- `AsrWebSocketHandler`
- `TranslationService`
- `TtsService`
- `LlmIntegration`

实施步骤：

1. 统一记录 `sessionId`、文本长度、语言方向。
2. 记录阶段耗时：
   - ASR final 到达时间
   - 翻译耗时
   - LLM 压缩耗时
   - TTS 首 chunk 耗时
   - TTS 完成耗时
   - WebSocket 推送 chunk 大小
3. 避免打印完整敏感文本，只打印长度和少量必要上下文。

验收标准：

- 单次完整同传在日志中能串起全链路。
- 发生异常时能定位到 ASR、翻译、LLM、TTS 或 WS 推送阶段。

### 5.4 WebSocket 断连清理

改动范围：

- `AsrWebSocketHandler`
- `RealtimeInterpretationFacade`
- 前端 `InterpretationView`

实施步骤：

1. 后端断连时使用业务 `sessionId` 清理，而不是只用 WebSocket session id。
2. 清理 ASR session、TTS callbacks、TTS chain、语言版本、pending 状态。
3. 前端关闭 WebSocket 时停止采集、停止 pending audio source、关闭 AudioContext。

验收标准：

- 浏览器直接关闭/刷新后，后端不会残留活跃 ASR session。
- 重新开始同传不会被旧 TTS 队列阻塞。

### 5.5 ASR 静音检测优化

改动范围：

- `application.yml`
- `application-dev.yml`
- `application-prod.yml`
- `.env.example`

实施步骤：

1. 将默认 `end-silence-timeout-ms` 调整到 1200-1500ms。
2. dev 环境可先使用 1500ms，测试切句质量。
3. 保留环境变量覆盖能力。

验收标准：

- 短句 final 明显更快。
- 没有大量异常截断句子。

### 5.6 LLM 压缩策略优化

改动范围：

- `TranslationService`
- `GoogleTranslateProperties`
- `application.yml`

实施步骤：

1. 默认实时链路模型使用 OpenAI 官方 SDK 调用，压缩模型建议为 `gpt-5-nano` 或由配置项控制。
2. 提高 LLM 压缩触发阈值，建议从 `text.length() > 40` 调整为 `> 80` 起测。
3. 短文本直接 Google Translate -> TTS。
4. 增加配置项：
   - `compression.enabled`
   - `compression.min-text-length`
   - `compression.model`

验收标准：

- 短句不调用 LLM。
- 长句压缩失败时继续使用原始译文。
- 日志能看出是否触发压缩。

> **重要约束（已实施）**：LLM 压缩仅适用于 `zh→id` 方向。`zh→en` 翻译结果**不经压缩**直接下发 TTS。
>
> 原因：`LlmIntegration.COMPRESSION_SYSTEM_PROMPT_ID` 是印尼语专用 prompt（明确写明"输入文本为印尼语"）。若将英语翻译结果送入该 prompt，LLM 会忽略实际输入语言，输出印尼语文本，导致英语字幕栏显示印尼语内容。
>
> 如需对英语添加压缩，必须在 `LlmIntegration` 中新增独立的英语 prompt，并在 `TranslationService.isCompressionDirection()` 里单独启用，不得复用印尼语 prompt。

### 5.7 跳读防护与 TTS 顺序追踪

当前状态：已实施，仍需按 `optimization-validation-test-plan.md` 做运行时验证。

改动范围：

- `AzureAsrIntegration`
- `AzureSpeechProperties`
- `application.yml`
- `application-dev.yml`
- `Constants`
- `WsMessage`
- `RealtimeInterpretationFacade`
- `AsrWebSocketHandler`
- `TtsService`
- 前端 `types/index.ts`
- 前端 `InterpretationView`

实施内容：

1. ASR 增加 Azure 官方分段静音参数：
   - `Speech_SegmentationSilenceTimeoutMs`
   - 配置项：`AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS`
   - 默认值：`2000`
2. 语义分段配置（预留，默认关闭）：
   - `Speech_SegmentationStrategy`
   - 配置项：`AZURE_ASR_SEGMENTATION_STRATEGY`
   - 默认值：空（不启用）
   - 注意：`Semantic` 值与 `ConversationTranscriber`（说话人分离）不兼容，会导致 Azure 返回错误 1007 "Could not validate speech context"。如需启用，必须先切换到普通 `SpeechRecognizer`（放弃说话人分离），或等待 Azure SDK 支持该组合。
3. TTS 语言切换时不再重置 `sessionTtsChain`，避免已生成或已排队的音频被主动跳过。
4. TTS 队列超过预警阈值时只记录 `TTS queue backlog high`，不再清空旧队列。
5. 每个 TTS 任务生成可追踪标识：
   - `ttsTaskId`
   - `ttsSequence`
   - `chunkIndex`
6. `/ws/asr` 下发 `tts_audio` 时携带上述 TTS 任务字段。
7. Cartesia WebSocket 请求增加：
   - `continue=false`
   - `max_buffer_delay_ms=0`
   用于完整句子自定义缓冲，减少完整句已到达后继续等待。
8. 前端不再按当前识别语言丢弃旧 TTS chunk。
9. 前端按 `ttsTaskId + chunkIndex` 记录重复块和疑似缺块：
   - 重复或旧 chunk：忽略并打印 warning。
   - chunkIndex 不连续：打印 `possible missing chunk` warning。
10. 启动同传前先检查 VoiceMeeter sink 是否 ready；未 ready 时阻止启动，避免运行中持续丢 PCM。

验收标准：

- 启动 ASR 时日志出现 `ASR silence config`，且 `segmentationSilenceMs=2000` 或当前配置值。
- 语言切换时只出现 `keep queued TTS to avoid skip`，不再出现 `TTS skipped`、`TTS playback skipped`、`TTS chain reset`。
- 队列积压时只出现 `TTS queue backlog high`，不再出现 `TTS queue backlog trimmed`。
- 后端每个 `tts_audio` 日志包含 `taskId`、`sequence`、`chunkIndex`。
- 前端控制台正常情况下不出现 `possible missing chunk` 或 `duplicate or old chunk ignored`。
- VoiceMeeter 未 ready 时不能开始同传，不允许 fallback 到默认扬声器。

### 5.8 ASR 分段长度控制

当前状态：已实施。

改动范围：

- `AzureSpeechProperties`
- `AzureAsrIntegration`
- `application.yml`
- `application-dev.yml`

背景：Azure Speech SDK 官方**没有**基于字符数限制单段长度的参数。唯一的 SDK 层时长参数为 `Speech_SegmentationMaximumTimeMs`（配合 `Time` 策略），字符数限制必须在应用层实现。

实施内容：

1. 语义分段：见 5.7 第 2 条，当前默认关闭（与 ConversationTranscriber 不兼容）。
2. SDK 层时长兜底（可选）：
   - `Speech_SegmentationMaximumTimeMs`
   - 配置项：`AZURE_ASR_SEGMENTATION_MAXIMUM_TIME_MS`
   - 默认值：`0`（不启用）
   - 启用条件：仅当 `segmentationStrategy` 为 `Time` 时有效；当前默认关闭以避免与 ConversationTranscriber 冲突
3. 新增应用层字符数强切（当前唯一生效的长度限制机制）：
   - 配置项：`AZURE_ASR_MAX_SEGMENT_CHARS`
   - 默认值：`150`
   - 实现位置：`AzureAsrIntegration.AsrSession.startConversationTranscriber()`
   - 原理：`transcribing`（中间结果）事件中文本累计超过阈值时，立即以 `isFinal=true` 回调下发；等待真正的 `transcribed` 事件时只补充余下部分（`text.substring(alreadySent)`）
   - 并发安全：`forcedFinalPending`（`AtomicBoolean`）保证同一段只强切一次；强切期间后续中间结果被抑制

切段机制（当前生效）：

| 层级 | 机制 | 默认状态 | 触发条件 |
|------|------|---------|---------|
| 1 | Azure Semantic 策略 | **关闭**（与 ConversationTranscriber 不兼容） | — |
| 2 | `Speech_SegmentationMaximumTimeMs` | **关闭**（默认 0） | 仅配合 Time 策略使用 |
| 3 | 应用层 `maxSegmentChars=150` | **开启** | 中间结果字符数超限时强切 |
| — | `Speech_SegmentationSilenceTimeoutMs=2000` | 始终生效 | 静音 2 秒后自然切段 |

验收标准：

- 启动日志出现 `ASR silence config`，包含 `segmentationStrategy=`（空）、`segmentationMaxTimeMs=0`、`maxSegmentChars=150`。
- 连续演讲不停顿时，日志中 `processFinalRecognition` 的文本长度不超过 150。
- 触发字符数强切时，日志出现 `force-segment at N chars`。
- 余下部分有内容时，日志出现 `force-segment remainder`。
- 正常短句依赖静音切段自然分段，不触发 `force-segment`。

## 6. 第 2 阶段：英语三语链路

### 6.1 后端语言常量扩展

改动范围：

- `Constants`
- DTO/VO 如有需要同步

实施步骤：

1. 增加：
   - `LANG_EN_US = "en-US"`
   - `LANG_EN_SHORT = "en"`
2. 更新语言支持判断，支持 `zh`、`id`、`en`。

验收标准：

- `TranslationService` 接受英语源语言和目标语言。

### 6.2 Azure ASR 英语支持

改动范围：

- `application.yml`
- `AzureAsrIntegration`

实施步骤：

1. 默认 `AZURE_ASR_LANGUAGES` 增加 `en-US`。
2. 自动语言检测列表变为 `zh-CN,id-ID,en-US`。
3. `normalizeAsrLang()` 支持 `en`。

验收标准：

- 英文音频能识别为 `en-US` 或 `en`。

### 6.3 三语翻译方向

改动范围：

- `RealtimeInterpretationFacade`
- `TranslationService`
- `GoogleTranslateIntegration`

实施步骤：

1. 明确目标语言策略：
   - 如果用户选择固定目标语言，则按用户选择。
   - 如果自动双向模式，中文 -> 印尼语，印尼语 -> 中文，英语根据会议配置选择目标语。
2. 建议增加 session 级配置：`targetLang` 仍作为主目标语言。
3. 支持中、印、英六个方向：
   - zh -> id
   - id -> zh
   - zh -> en
   - en -> zh
   - id -> en
   - en -> id

验收标准：

- 六个方向 HTTP 翻译测试通过。
- WebSocket 识别英文后能正确翻译到目标语言。

### 6.4 英语 TTS

改动范围：

- `CartesiaProperties`
- `application.yml`
- `RealtimeInterpretationFacade`
- `Constants`

实施步骤：

1. 增加默认英语 voice id 配置。
2. `resolveVoiceId()` 支持英语。
3. 增加英语语速配置，初始建议 `1.05` 或 `1.0`。

验收标准：

- 英语目标语言能产生 TTS。
- 无英语音色配置时降级到默认音色，并记录 warning。

### 6.5 前端三语适配

改动范围：

- `src/constants.ts`
- `src/types/index.ts`
- `InterpretationView`
- 样式文件

实施步骤：

1. 增加英语语言标签和 badge 样式。
2. UI 支持显示中文、印尼语、英语。
3. TTS stale chunk 丢弃逻辑支持英语。
4. 如保持自动模式，展示当前识别语言与目标语言。

验收标准：

- 英语识别、译文、字幕 badge 显示正常。
- 前端 TypeScript 编译通过。

## 7. 第 3 阶段：术语库与对话记录

### 7.1 新增对话记录表

数据库表：

```sql
CREATE TABLE interpretation_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL,
    seq          INT NOT NULL,
    source_lang  VARCHAR(16),
    target_lang  VARCHAR(16),
    source_text  TEXT,
    target_text  TEXT,
    spoken_at    DATETIME(3),
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_seq (session_id, seq),
    INDEX idx_session_id (session_id)
);
```

改动范围：

- `schema.sql`
- 新增 `InterpretationRecord`
- 新增 `InterpretationRecordMapper`
- 新增 `InterpretationRecordService`
- `RealtimeInterpretationFacade`
- `InterpretationController`

实施步骤：

1. final recognition 到达时暂存原文。
2. translated 完成后保存 source/target 文本。
3. 使用 session 内递增 seq 保证顺序。
4. 提供按 session 查询接口。

验收标准：

- 同传结束后能查到完整原文和译文。
- 顺序与前端展示一致。

### 7.2 术语库初版

建议表：

```sql
CREATE TABLE terminology (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    term_zh     VARCHAR(255),
    term_id     VARCHAR(255),
    term_en     VARCHAR(255),
    category    VARCHAR(64),
    enabled     TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled),
    INDEX idx_category (category)
);
```

改动范围：

- 新增 entity/mapper/service/controller
- `TranslationService`

实施步骤：

1. 增加术语查询能力。
2. 翻译前 hook：保护源文本中的术语。
3. 翻译后 hook：修正译文中的术语。
4. 先实现后端 API，前端管理页可放到第 5 阶段。

验收标准：

- 指定术语在目标语言中稳定输出。
- 术语库 disabled 后不参与修正。

## 8. 第 4 阶段：字幕、队列控制、会议纪要

### 8.1 三语字幕

改动范围：

- `InterpretationView`
- `WsMessage`
- `AsrWebSocketHandler`

实施步骤：

1. 复用 `recognized` 和 `translated` 消息。
2. 前端增加稳定字幕区域和历史记录区域。
3. 保证三语 badge、换行、滚动体验。

验收标准：

- 同传过程中实时字幕不断流。
- 切换语言后旧音频不继续占用新语言声道。

### 8.2 播放队列积压控制

改动范围：

- `RealtimeInterpretationFacade`
- 前端 `InterpretationView`

实施步骤：

1. 后端记录每个 session TTS 队列长度。
2. 队列超过阈值时先进入可观测预警模式：
   - 记录 `TTS queue backlog high`
   - 保留已排队任务，避免静默跳读
   - 后续如需降延迟，必须显式设计“保真模式/低延迟摘要模式”开关
3. 前端按 `ttsTaskId + chunkIndex` 追踪音频块连续性，不再仅凭当前识别语言丢弃旧 chunk。

验收标准：

- 连续快速发言 2 分钟不会出现静默丢句；如有积压，日志必须可定位。
- 停止 session 立即停止播放。

### 8.3 会议纪要基础版

改动范围：

- `InterpretationRecordService`
- `LlmIntegration`
- 新增 summary service/controller

实施步骤：

1. 按 session 拉取 `interpretation_record`。
2. 拼接会议文本。
3. 调 LLM 生成：
   - 会议摘要
   - 重点事项
   - 待办事项
4. 先同步生成，后续再考虑异步任务。

验收标准：

- 给定 sessionId 能生成基础纪要。
- 空记录返回明确错误。

## 9. 第 5 阶段：声音体验与基础管理统计

### 9.1 音色使用记录

建议表：

```sql
CREATE TABLE voice_usage_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64),
    user_id     BIGINT,
    voice_id    VARCHAR(128),
    target_lang VARCHAR(16),
    text_len    INT,
    used_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_voice_id (voice_id)
);
```

验收标准：

- 每次 TTS 调用能记录 voiceId、语言、文本长度。

### 9.2 音色授权

实施步骤：

1. 扩展 `user_voice`：
   - `authorized`
   - `scope`
   - `disabled`
2. TTS 调用前校验音色是否可用。

验收标准：

- 禁用音色不会被会议调用。
- 未授权音色返回明确错误或降级默认音色。

### 9.3 成本统计

实施步骤：

1. 扩展 `interpretation_session` 统计字段：
   - `asr_audio_ms`
   - `translate_chars`
   - `tts_chars`
   - `llm_input_tokens`
   - `llm_output_tokens`
2. 在对应 service 中累加。

验收标准：

- 每场 session 结束后可查看基础资源消耗。

### 9.4 会中说话人自动音色克隆

改动范围：

- `AzureAsrIntegration`
- `AsrService`
- `RealtimeInterpretationFacade`
- 新增 `SessionSpeakerVoice`
- 新增 `SessionSpeakerVoiceMapper`
- 新增 `SessionSpeakerVoiceService`
- `InterpretationController`

实施步骤：

1. 将实时 ASR 会话切换为 Azure `ConversationTranscriber`，获取 `Guest-n` 说话人标签。
2. 后端按 `sessionId + speakerId` 维护会话内说话人音色映射。
3. 每个 session 保存最近 PCM 音频缓冲。
4. 每次 final recognition 到达时，将最近音频归集到对应 `Guest-n`。
5. 某个 `Guest-n` 累计到目标采样时长后，后台调用 Cartesia `/voices/clone`。
6. 克隆成功后保存 `cartesiaVoiceId`，后续该 `Guest-n` 的 TTS 优先使用该音色。
7. 克隆未完成或失败时继续使用手动音色或默认音色，不阻塞同传链路。

验收标准：

- WebSocket start 后日志显示使用 `ConversationTranscriber`。
- 真实多人发言时日志出现 `speakerId=Guest-*`。
- 某个 `Guest-n` 累计音频后出现 `speaker voice clone ready` 或明确失败日志。
- `/api/interpretation/speaker-voices/{sessionId}` 可查询每个 `Guest-n` 的克隆状态。
- 克隆成功后后续 TTS 日志出现 `speaker voice resolved`。

## 10. 每阶段通用测试门禁

每完成一个阶段，必须执行：

```powershell
cd si-frontend
npm.cmd exec tsc -- --noEmit

cd ..\si-backend
mvn -q -DskipTests compile
```

如涉及后端启动，还需执行：

```powershell
.\start-all.bat
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/health
Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health
docker logs si-backend --tail 120
```

如涉及前端交互，还需执行：

```powershell
cd si-frontend
npm.cmd run dev
```

浏览器人工验证：

1. 打开 `http://localhost:5173`。
2. 启动同传。
3. 允许系统/标签页音频捕获。
4. 验证字幕、译文、TTS 播放。
5. 停止同传。
6. 刷新页面后再次启动。

## 11. 专项测试清单

稳定性测试：

- WebSocket 正常 start/stop。
- 浏览器刷新后后端清理 session。
- TTS 服务异常时不会阻塞后续任务。
- 连续 10 分钟运行无明显资源泄漏。

延迟测试：

- 短句 final 延迟。
- 翻译耗时。
- LLM 压缩耗时。
- TTS 首 chunk 延迟。
- 前端收到 chunk 到播放耗时。

三语测试：

- 中文 -> 印尼语
- 印尼语 -> 中文
- 中文 -> 英语
- 英语 -> 中文
- 印尼语 -> 英语
- 英语 -> 印尼语

对话记录测试：

- 每条 final recognition 有记录。
- 每条 translated 能回填 target_text。
- seq 顺序稳定。
- 按 session 查询结果完整。

术语库测试：

- 人名不被错误翻译。
- 公司名不被错误翻译。
- 部门名固定输出。
- 禁用术语不生效。

音频测试：

- 中文 TTS 输出到 VoiceMeeter Input。
- 印尼语 TTS 输出到 VoiceMeeter Aux Input。
- 英语 TTS 按配置输出。
- 语言切换后已排队 TTS 不被主动跳过；如发生缺块，前端控制台必须出现 `possible missing chunk`。

## 12. 回滚策略

数据库：

- 每次新增表优先只新增，不破坏旧表。
- 修改表字段必须提供 rollback SQL。
- 删除字段必须延后，不在本轮直接删除。

配置：

- 所有新行为必须支持配置开关。
- LLM 压缩、术语修正、会议纪要都要可关闭。

代码：

- 每阶段独立提交。
- 出现实时链路不稳定时，优先回滚当阶段，不影响前一阶段能力。

## 13. 推荐执行顺序

严格按以下顺序执行：

1. 第 0 阶段：基线确认。
2. 第 1.1：TTS 超时保护。
3. 第 1.2：健康检查。
4. 第 1.3：链路耗时日志。
5. 第 1.4：WebSocket 断连清理。
6. 第 1.5：ASR 静音参数优化。
7. 第 1.6：LLM 压缩策略优化。
8. 第 1.7：跳读防护与 TTS 顺序追踪。
9. 第 2 阶段：英语三语链路。
10. 第 3 阶段：术语库与对话记录。
11. 第 4 阶段：字幕、队列控制、会议纪要。
12. 第 5 阶段：音色与基础管理统计。

## 14. 阶段完成定义

一个阶段只有同时满足以下条件，才算完成：

1. 代码实现完成。
2. 编译检查通过。
3. 涉及接口的功能测试通过。
4. 涉及实时链路的人工验证通过。
5. 日志中没有新的 ERROR 或资源泄漏迹象。
6. 文档记录已更新。
7. 已明确下一阶段是否依赖本阶段产物。
8. 已按 `docs/coding-standards.md` 完成代码规范自检。

## 15. 周度优化记录规则

从后续迭代开始，所有每周新增优化项统一追加在本文档中，不再为每一周单独新建优化计划文档。阶段性专题文档可以作为补充材料存在，但每周最终结论必须回写到本文档。

### 15.1 记录要求

1. 每周使用统一标识，例如 `2026-W20`。
2. 每周至少记录：
   - 本周目标；
   - 优化项及状态；
   - 实施结果；
   - 影响范围；
   - 验收标准；
   - 遗留问题。
3. 历史周记录只追加、不覆盖，便于后续回溯。
4. 如果某项暂缓，必须写明原因和后续条件。
5. 对应测试内容统一写入 `docs/optimization-validation-test-plan.md`，并使用完全一致的周标识。

### 15.2 周度追加模板

```markdown
## 周度优化记录：2026-W20

### 本周目标

- ...

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| ... | 已完成 / 进行中 / 暂缓 | ... |

### 实施结果

- ...

### 影响范围

- 后端：
- 前端：
- 配置 / 数据：

### 验收标准

- ...

### 遗留问题

- ...
```

## 周度优化记录：2026-W21

### 本周目标

- 推进 Microsoft Teams Bot 入会 PoC，降低对 meeting chat 安装路径的依赖。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| Teams 会议链接自动入会 PoC | 已完成 | 官方 calling / meeting bot 示例支持在个人聊天中识别 Teams 会议链接并调用现有 Graph call 逻辑加入会议 |

### 实施结果

- `MessageBot` 收到个人聊天消息后，会优先检测 Teams 会议链接。
- `JoinInfo` 新增会议链接提取和 `ChatInfo` 解析能力。
- 解析成功后复用现有 `callService.Create(chatInfo, meetingInfo)` 发起加入已有会议调用。
- 原有 meeting chat 中 `Join scheduled meeting` 路径保留。

### 影响范围

- 后端：无 Java 主业务改动。
- 前端：无 React 前端改动。
- 配置 / 数据：无新增配置；仍依赖现有 Azure Bot、Graph 权限、calling webhook 与 `ngrok` 配置。
- 外部样例：`external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample`。

### 验收标准

- 个人聊天发送完整 Teams 会议链接后，Bot 返回正在加入会议的提示。
- Teams 会议成员列表能看到 Bot 入会。
- 本地日志出现 `call created / call connected / call ended` 生命周期。
- 编译检查通过。

### 遗留问题

- 已完成编译验证，但尚未用真实会议链接完成端到端入会验证。
- 个人聊天 `Create Call` 仍返回 Graph `7504`，该路径不作为当前加入已有会议主线阻塞。

## 周度优化记录：2026-W21 Teams 用户摘要私聊发送改造

### 本周目标

- 将 Teams 摘要投递从“会议聊天”旧方案切换为“指定 Teams 用户私聊”。
- 删除摘要发送链路中对会议 `activeCallId`、`meetingThreadId`、会议聊天安装状态的依赖。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| Bot 摘要发送接口改造 | 已完成 | `POST /api/meetings/summary` 改为接收 `{ content, recipients }`，`recipients` 为 Teams 用户邮箱、UPN 或 AAD ID |
| 会议聊天发送路径删除 | 已完成 | 删除 `sendToMeetingChat` / `sendToParticipants` 旧请求字段，删除会议聊天自动安装和会议 thread 发送分支 |
| Teams 用户私聊发送 | 已完成 | C# Bot 解析用户、安装 Bot App 到用户 personal scope、获取个人 chat 后用 Bot Framework Connector 发送 |
| 前端收件人管理改造 | 已完成 | `TeamsBotView` 改为 Teams 用户收件人管理页，同传页推送按钮读取默认用户发送 |

### 实施结果

- `MeetingSummaryController.SendSummary` 不再要求 Bot 已加入会议。
- `MeetingSummaryService` 对每个用户独立发送，并返回成功/失败明细。
- `ChatService` 保留 Bot Framework Connector 发送方式，但投递目标改为用户 personal chat。
- `TeamsBotView` 删除会议链接入会、参会人员、群组发送对象等与用户私聊无关的 UI。
- `InterpretationView` 改用 `sendTeamsSummaryToUsers(summary, recipients)`。

### 影响范围

- 后端：C# Teams Bot `MeetingSummaryController`、`MeetingSummaryService`、`ChatService`、`IChatService`。
- 前端：`si-frontend/src/api/index.ts`、`si-frontend/src/types/index.ts`、`TeamsBotView`、`InterpretationView`。
- 配置：仍需要 `Bot:CatalogAppId`；用户 personal scope 安装需要 Azure AD 应用权限 `TeamsAppInstallation.ReadWriteSelfForUser.All`。

### 验收标准

- 发送摘要请求未带 `recipients` 时返回 400。
- 不再出现 `sendToMeetingChat`、`sendToParticipants`、`TryInstallAppInMeetingChatAsync`、会议聊天 `SendMessageToChatAsync` 残留。
- 添加默认 Teams 用户后，同传页“推送摘要到 Teams”会向这些用户发送私聊摘要。
- C# Bot 与前端构建通过。

### 遗留问题

- 真实 Teams 用户端到端收信仍需在 `Bot:CatalogAppId` 和 `TeamsAppInstallation.ReadWriteSelfForUser.All` 配置完成后验证。

## 周度优化记录：2026-W21 Teams Bot 历史会议查询

### 本周目标

- 让用户可以在 Teams 个人聊天中直接和 syncLingo Bot 沟通，查询过往会议记录和会议摘要。
- 复用现有 Java 后端历史会话与摘要数据，不让 C# Bot 直接访问数据库。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| Bot 聊天命令入口 | 已完成 | `MessageBot` 解析 Teams 用户消息，支持 `帮助`、`最近`、`搜索 关键词`、`摘要 <sessionId/关键词>` |
| Java 查询接口 | 已完成 | 新增 `POST /api/teams-bot/query`，返回 Bot 可直接回复的文本 |
| Teams 用户映射 | 已完成 | 优先按 Teams mail / UPN 匹配 `si_user.email` 或 `si_user.username`，默认不使用兜底用户 |
| C# 到 Java 集成 | 已完成 | 新增 `SyncLingoBotQueryService`，通过 `Bot:BackendBaseUrl` 调用 Java 后端，并支持共享密钥请求头 |
| 自然语言问答 | 暂缓 | 第一版采用确定性命令，后续再引入 RAG / LLM，避免权限和答案稳定性混在一起 |

### 实施结果

- C# Bot 收到 Teams 个人聊天消息后，会清理 @mention/HTML，读取 Teams 成员资料并转发到 Java 后端。
- Java 后端按用户身份查找 syncLingo 用户，仅返回该用户自己的历史会议。
- Bot 可以返回最近会议列表、关键词搜索结果、单场会议摘要。
- 未匹配到用户时，Bot 返回明确绑定提示，而不是使用默认用户泄露记录。

### 影响范围

- 后端：`si-backend` 新增 Teams Bot 查询 controller / facade / service / DTO / VO，并扩展 `UserMapper`。
- C# Bot：`MessageBot`、`BotOptions`、`Startup`，新增 `Services/SyncLingo` 集成服务。
- 配置 / 数据：新增 Java `teams.bot.*` 配置；C# 使用 `Bot:BackendBaseUrl` 与可选 `Bot:BackendApiSecret` 配置。

### 验收标准

- Java 后端编译通过。
- C# Bot 编译通过。
- 未绑定 Teams 用户查询时返回绑定提示。
- 已匹配 Teams 用户发送 `最近` 时返回自己的历史会议列表。
- 已匹配 Teams 用户发送 `摘要 <sessionId>` 时只能查看自己的会议摘要。

### 遗留问题

- 真实 Teams 个人聊天端到端查询待部署后验证。
- 当前用户映射基于 `si_user.email` / `username`，正式多人生产环境建议新增独立 `teams_user_binding` 表和管理界面。
- 生产环境需配置 Java `TEAMS_BOT_API_SECRET` 与 C# `Bot:BackendApiSecret`，避免查询接口被非 Bot 调用。

## 周度优化记录：2026-W21 Teams Bot 方案 B 自动安装固化

### 本周目标

- 将 Teams 用户私聊发送正式固化为方案 B：组织目录发布 + Graph 自动安装用户 personal scope。
- 删除 `CreateConversationAsync` fallback，避免未安装用户导致主动消息发送失败。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| 删除 CreateConversation fallback | 已完成 | `ChatService` 不再在 `CatalogAppId` 缺失时尝试 `CreateConversationAsync` |
| 强制 CatalogAppId 前置校验 | 已完成 | 发送 Teams 用户消息前必须配置 `Bot:CatalogAppId` |
| 自动安装 personal scope | 已完成 | 保留并固化 `Users/{id}/teamwork/installedApps` 安装路径 |
| 失败原因透出 | 已完成 | 全部收件人失败时 API 返回第一条真实失败原因 |
| 授权指南 | 已完成 | 新增 `docs/teams-bot-plan-b-auto-install-guide.md` |

### 实施结果

- `ChatService.SendMessageToUserAsync` 只走方案 B：解析用户 -> 校验 `CatalogAppId` -> 安装 Bot App 到用户 personal scope -> 获取 personal chat -> 发送消息。
- 删除 Bot Framework `CreateConversationAsync` fallback 和旧的 chat scope `InstallApp` 辅助方法。
- 错误提示能直接指向缺少 `CatalogAppId`、Graph 权限或用户解析问题。

### 影响范围

- C# Bot：`ChatService`、`MeetingSummaryController`。
- 文档：Teams 方案 B 授权指南、Teams 集成进度、周度优化记录。
- 配置：必须配置 `Bot:CatalogAppId`，并完成 Azure AD `TeamsAppInstallation.ReadWriteSelfForUser.All` 管理员同意。

### 验收标准

- C# Bot 构建通过。
- 旧 fallback 关键字 `CreateConversationAsync`、`CreateConversationWithUserAsync` 不再出现在 `ChatService`。
- 未配置 `CatalogAppId` 时返回明确配置错误。
- 配置正确后，发送摘要前会自动安装 Bot App 到目标 Teams 用户 personal scope。

### 遗留问题

- Azure AD Graph 权限、Teams 管理中心应用发布、`Bot:CatalogAppId` 回填和 C# Bot 重启已完成；仍需真实 Teams 用户端到端验证摘要私聊收信。

## 周度优化记录：2026-W21 Teams Bot 主动消息二次修复

### 本周目标

- 修复 Teams 会议聊天和个人私聊主动消息在真实租户中仍发送失败的问题。
- 明确 Teams 平台是否支持当前目标能力，并让实现贴近 Microsoft 官方 proactive messaging 模式。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| 日志定位 | 已完成 | 读取 `%TEMP%\bot.log`，定位会议聊天失败在缺少当前进程 ConversationReference，个人私聊失败在 `SendActivityAsync` 400 |
| Teams 支持边界确认 | 已完成 | 官方文档确认 Teams 支持 Bot proactive messages；个人/聊天/团队需要先安装 App，并使用 conversationId / ConversationReference |
| TextFormat 修复 | 已完成 | 旧代码使用 unsupported `html`，新版归一化为 Teams Markdown 并设置 `TextFormatTypes.Markdown` |
| ActivityId 清理 | 已完成 | 主动发送前清空 `ConversationReference.ActivityId`，避免把消息当成 reply 发给 `conversationUpdate` 活动 |
| 会议聊天兜底 | 已完成 | meeting chat App 已安装但没有缓存时，使用 meeting `threadId` 构造 ConversationReference 兜底发送 |
| 安装日志强化 | 已完成 | `EnsureBotInMeetingChatAsync` 对 409 已安装记录 info，并补充非 Graph 异常日志 |

### 实施结果

- `ChatService.ExecuteContinueConversation()` 不再发送 `TextFormat = "html"`。
- `ChatService` 新增 proactive reference 构造、meeting thread fallback、HTML 到 Markdown 的轻量转换。
- `MeetingSummaryController.JoinMeeting()` 等待 meeting chat 安装检查完成后返回，避免安装异常只存在于后台任务。
- 通过 Graph 直接验证当前 meeting chat 已安装 `meeting bot`，`POST /installedApps` 返回 409 `AppEntitlementAlreadyExists`。
- `start-all.bat` 本次暴露 Docker Desktop build snapshot/cache 损坏问题，已绕过 Docker build export 失败并手动拉起当前测试栈。

### 影响范围

- C# Bot：`ChatService`、`IChatService`、`MeetingSummaryController`。
- 文档：Teams 集成进度、周度优化记录、周度验证记录。
- 配置 / 数据：无新增配置；继续使用既有 `Bot:CatalogAppId` 与 Graph 权限。

### 验收标准

- C# Bot 构建通过。
- `%TEMP%\bot.log` 能看到 meeting chat 409 已安装被记录为 info，而不是静默。
- 个人私聊发送不再出现 unsupported `TextFormat = html`。
- 发送前 proactive `ConversationReference.ActivityId` 为空。
- 真实 Teams 会议中，"发送到会议聊天"和"发送给参会人员"至少各有一个成功路径。

### 遗留问题

- 真实 Teams 端到端发送需用户重新加入会议后回归。
- Docker Desktop snapshot/cache 损坏会导致完整 `start-all.bat` 在 backend image export 阶段失败；需要后续清理 Docker build cache 或重启 Docker Desktop 后再验证脚本全流程。

## 周度优化记录：2026-W24 分享页 1.35x 加速端到端延迟分组统计

### 本周目标

- 让分享页在音频播放积压时最高使用 `1.35x` 追赶，且不丢弃音频。
- 精确区分普通播放、加速中和 `1.35x` 样本，测量“开始说话到听众开始播放对应 TTS”的端到端延迟。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| 分享页动态播放加速 | 已完成 | 积压不超过 1 秒使用 `1.00x`，1 至 4 秒线性加速，达到 4 秒后使用 `1.35x` |
| 加速状态上报 | 已完成 | 客户端延迟样本新增 `sessionId`、`backlogMs`、`playbackRateMilli` 与 `outputLatencyMs` |
| 延迟日志增强 | 已完成 | 后端 `e2e client latency` 日志记录播放积压与播放倍速 |
| 延迟分析分组 | 已完成 | 分析脚本单独输出 `1.00x`、加速中、`1.35x` 的端到端延迟 |

### 实施结果

- 分享页继续完整播放全部 Opus 音频，只通过 Web Audio `playbackRate` 逐步消化积压。
- `UserShareView` 使用浏览器官方 `AudioContext.getOutputTimestamp()` 将对应 TTS 首音映射到音频输出设备时间，并上报端到端延迟、服务端段、RTT、本地尾延迟、输出设备尾延迟、积压和播放倍速。
- `tests/analyze_latency.py` 可直接判断测试期间是否真正进入 `1.35x`，并输出该组延迟分布。

### 影响范围

- 前端：`ShareView`、`UserShareView`、`src/api/index.ts`。
- 后端：`InterpretationController` 延迟日志。
- 测试：`tests/analyze_latency.py`。

### 验收标准

- 前端构建和后端测试通过。
- 连续说话产生至少一个 `playbackRateMilli=1350` 样本。
- 分析脚本输出 `1.35x` 样本数、端到端均值/中位数/p90 和积压均值/p90。
- 人工确认 `1.35x` 下语音仍可理解。

### 遗留问题

- `playbackRate` 会同时提高音调，听感必须由真人确认。
- `1.35x` 只会缩短已有播放积压，不会缩短第一句的 ASR、翻译或 TTS 首音耗时。

## 周度优化记录：2026-W24 TTS 严格句序播放

### 本周目标

- 杜绝后一句翻译或 TTS 更快时先于前一句播放的跳读现象。
- 保留不同句子的翻译与 TTS 合成并行能力，只约束同一目标语言的实际播放顺序。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| final 识别初始化保序 | 已完成 | 每个会话按 ASR final 到达顺序初始化句子，避免并发任务先后顺序漂移 |
| 播放位置提前预留 | 已完成 | 在翻译开始前为每个目标语言预留 TTS 播放位置，不再由翻译完成速度决定播放顺序 |
| 同语言严格串行播放 | 已完成 | 中文、印尼语、英语各自维护播放链；同语言严格保序，不同语言仍可并行 |
| 异常自动释放 | 已完成 | 翻译失败、空翻译、会话停止、播放超时和播放异常都会释放预留位置，避免后续句子永久阻塞 |
| 顺序日志 | 已完成 | 新增 `TTS order reserved` 与 `TTS order released`，记录语言、任务和 sequence |

### 实施结果

- 后一句可以先完成翻译和 TTS 合成，但发送音频前必须等待同语言前一句完成。
- 播放顺序由 ASR final 句子顺序决定，不再由翻译服务响应速度决定。
- 播放队列长度现在包含已预留、翻译中和等待播放的任务，可更早暴露真实积压。

### 影响范围

- 后端：`RealtimeInterpretationFacade` 的 final 识别调度、翻译任务初始化和 TTS 播放链。
- 测试：新增后句先完成翻译时仍按前句、后句顺序播放的自动化测试。

### 验收标准

- 后端完整测试通过。
- 自动化测试中第二句先完成翻译与合成，实际首音 sequence 仍为 `1, 2`。
- 服务器真实连续语音测试中不再出现后句先读、前句后补。
- 同一语言的 `TTS order released` sequence 不倒退。

### 遗留问题

- 严格保序不会降低前句自身延迟；前句处理较慢时，后句会等待并形成播放积压。
- 分享页 WebSocket 发送队列满时仍可能丢弃音频包，需要通过压力测试单独评估，但不会改变本次句序规则。

## 周度优化记录：2026-W24 发言摘要姓名可编辑与乱码防护

### 本周目标

- 允许用户在历史记录的“发言摘要”中修正识别错误的汇报人姓名，并将姓名与摘要正文持久化。
- 阻止常见 UTF-8 错解乱码摘要继续写入或展示，并自动刷新已有异常摘要。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| 汇报人姓名可编辑 | 已完成 | 发言摘要卡片将姓名展示改为输入框，并新增“保存”按钮 |
| 姓名与正文持久化 | 已完成 | 新增发言摘要更新接口，保存 `speaker_name` 与 `summary` |
| 摘要乱码检测 | 已完成 | 将替换字符和常见 UTF-8 错解特征纳入不可用摘要判断 |
| 异常摘要自动刷新 | 已完成 | 生成、重新生成和读取历史摘要时检测异常，最多尝试三次后返回明确错误 |
| 检索索引同步 | 已完成 | 手动修改、重新生成和自动刷新后重新构建对应发言摘要向量 |
| 分层与返回模型收口 | 已完成 | 发言摘要 Controller 改经 `SpeakerSummaryFacade` 调用，并使用 VO 返回持久化记录 |

### 实施结果

- 用户可直接修改汇报人姓名和摘要正文；点击“保存”后一次性持久化全部修改，刷新页面仍保留修正内容。
- 发送 Teams 时继续使用页面当前编辑后的姓名和正文。
- 新生成的乱码摘要不会入库；历史乱码摘要在读取时会基于原始发言文本自动重新生成。
- LLM 连续返回异常内容时最多尝试三次，避免请求无限等待。

### 影响范围

- 后端：`MeetingController`、`SpeakerSummaryFacade`、`SpeakerSummaryService`、`SpeakerSummaryRecordMapper`、发言摘要 DTO/VO。
- 前端：`HistoryView`、`src/api/index.ts`。
- 测试：`SpeakerSummaryServiceTest`。
- 数据：无表结构变更；更新发言摘要时同步更新对应向量记录。

### 验收标准

- 汇报人姓名和摘要正文可编辑并通过接口持久化。
- 常见乱码摘要被识别为不可用，历史异常摘要可自动重新生成。
- 手动修改或重新生成后，发言摘要向量索引同步刷新。
- 前端生产构建和后端完整测试通过。

### 遗留问题

- 本机 MySQL 连接在后端启动阶段被重置，暂未完成真实历史数据页面的端到端保存点击验证。
- 乱码自动刷新依赖原始发言文本和 LLM 服务可用；缺少原始文本时仅记录告警，不覆盖现有摘要。

## 周度优化记录：2026-W25 用户管理与权限管理方案

### 本周目标

- 在现有登录认证基础上建立统一、可审计的 RBAC 权限体系，并按资源归属限制用户可访问的数据。
- 明确 `admin`、`operator`、`viewer` 三类人员角色，以及 Bot、公开分享页等非人员调用方的独立认证边界。
- 逐步移除前端传入 `userId` 决定数据归属的模式，防止通过修改参数访问或操作其他用户数据。
- 在不影响正式会议同传、分享页和 Teams Bot 的前提下，分阶段启用权限拦截。

### 当前代码基线与主要问题

- 当前盘点范围包括 203 个后端主代码文件、18 个 Controller、98 个 HTTP 接口方法、31 个前端源文件、52 个当前 Teams Bot 源文件和 9 个 speaker-service 文件。
- `si_user.role` 字段已存在，但注册时未赋值；登录响应、令牌、后端请求上下文和前端状态均不使用角色。
- 当前令牌只包含 `userId / username / expiresAt`，后端过滤器只判断令牌是否有效，认证成功后仅写入 `authenticatedUserId`。
- 18 个 Controller 中至少 12 个仍直接接收或使用前端传入的 `userId`；前端多个页面读取 `localStorage`，并在缺失时默认使用用户 `1`。
- 多数会议、会话、资料、摘要、行动项、音频、术语、热词和费用接口没有统一的权限与资源归属校验。
- `MeetingService.requireOwner()` 在无请求上下文时会放行，且文件摘要、内容读取、重新加载和下载等路径未统一校验 `meetingId` 与 `fileId` 归属。
- ASR WebSocket 只在握手时验证令牌，不校验令牌用户是否有权启动、写入或停止消息中的 `sessionId`；分享 WebSocket 按 `sessionId` 公开连接。
- `/api/interpretation/public/user/{userId}/active` 可按用户编号查询活动会话；`/bot-api/**` 被认证过滤器排除，但 Java 代理和 C# 业务接口没有服务间凭证校验。
- 管理接口使用独立 `X-Admin-Secret`，日志下载还支持查询参数 secret；浏览器端不应持有或通过 URL 发送管理密钥。
- 当前后端只有 `spring-security-crypto`，尚未使用完整 Spring Security；权限相关自动化测试为空，前端测试文件为 0。
- 数据库结构变更主要由 Service 启动时执行 DDL；权限表、审计表和账号状态变更应改用可回滚的版本化迁移。
- `schema.sql` 包含固定默认管理员密码，生产配置仍存在固定数据库密码和 JWT secret 默认值，必须在权限上线前清理。

### 目标授权模型

权限判断统一采用三层模型：

1. **身份认证**：当前调用方是谁，区分人员用户、内部服务和公开分享访问者。
2. **功能权限**：该身份能执行什么操作，例如查看会议、启动同传、管理用户。
3. **数据范围**：该身份能对哪些资源执行操作，例如自己的会议、被分配的会议或全部会议。

统一判断表达式：

`允许访问 = 身份有效 AND 拥有功能权限 AND 满足资源数据范围`

仅隐藏前端按钮不构成授权；所有敏感操作必须由后端再次判断。

### 角色定义

| 角色 / 身份 | 定位 | 默认数据范围 | 核心限制 |
|---|---|---|---|
| `ADMIN` | 系统管理员，负责账号、角色、系统配置、审计和支持排障 | `ALL` | 日常会议操作仍需记录管理员越权访问审计 |
| `OPERATOR` | 秘书处或同传操作人员，负责会议准备、同传、摘要和通知 | `OWN + ASSIGNED` | 不得管理账号角色、下载系统日志或执行全局管理任务 |
| `VIEWER` | 只读查看人员，查看被授权会议、记录和摘要 | `ASSIGNED`，必要时包含本人创建资源 | 不得创建、修改、删除、启动同传或发送 Teams 消息 |
| `SERVICE` | Teams Bot、后台任务等非人员服务身份 | 明确到服务与接口 | 不使用普通用户 JWT，不出现在用户角色选择器中 |
| `SHARE_CAPABILITY` | 分享页短期只读能力令牌 | 单个 `sessionId` | 仅允许读取指定公开会话，不代表系统用户 |

第一阶段固定三个人员角色和权限映射，管理员页面只允许分配角色、启停账号和撤销会话，不开放任意自定义角色或权限组合。待固定矩阵稳定后，再评估数据库动态角色。

### 权限码与角色矩阵

| 权限域 | 权限码 | ADMIN | OPERATOR | VIEWER |
|---|---|---:|---:|---:|
| 账号 | `USER_READ`、`USER_MANAGE`、`ROLE_ASSIGN`、`SESSION_REVOKE` | 是 | 否 | 否 |
| 人员目录 | `DIRECTORY_READ` | 是 | 是 | 否 |
| 人员目录 | `DIRECTORY_MANAGE` | 是 | 否 | 否 |
| 会议 | `MEETING_READ` | 全部 | 自有/被分配 | 被分配 |
| 会议 | `MEETING_CREATE`、`MEETING_UPDATE`、`MEETING_DELETE` | 全部 | 自有/被分配 | 否 |
| 同传 | `INTERPRETATION_READ` | 全部 | 自有/被分配 | 被分配 |
| 同传 | `INTERPRETATION_OPERATE` | 全部 | 自有/被分配 | 否 |
| 资料与出勤 | `MATERIAL_READ`、`ATTENDANCE_READ` | 全部 | 自有/被分配 | 被分配 |
| 资料与出勤 | `MATERIAL_MANAGE`、`ATTENDANCE_MANAGE` | 全部 | 自有/被分配 | 否 |
| 摘要与行动项 | `SUMMARY_READ`、`ACTION_ITEM_READ` | 全部 | 自有/被分配 | 被分配 |
| 摘要与行动项 | `SUMMARY_EDIT`、`SUMMARY_REGENERATE`、`ACTION_ITEM_MANAGE` | 全部 | 自有/被分配 | 否 |
| 术语与热词 | `TERMINOLOGY_READ`、`HOTWORD_READ` | 全部 | 自有 | 否 |
| 术语与热词 | `TERMINOLOGY_MANAGE`、`HOTWORD_MANAGE` | 全部 | 自有 | 否 |
| 通知与 Bot | `TEAMS_SEND`、`BOT_OPERATE` | 是 | 自有/被分配会议 | 否 |
| 音频与导出 | `AUDIO_READ`、`EXPORT_READ` | 全部 | 自有/被分配 | 被分配 |
| 音频 | `AUDIO_MANAGE` | 全部 | 自有 | 否 |
| 费用 | `COST_READ_SELF` | 是 | 是 | 否 |
| 费用 | `COST_READ_ALL` | 是 | 否 | 否 |
| 系统管理 | `ADMIN_JOB_EXECUTE`、`LOG_READ`、`AUDIT_READ` | 是 | 否 | 否 |

`ADMIN` 的 `ALL` 数据范围不是绕过授权，而是授权策略中的显式结果；每次访问其他用户资源时记录审计事件。

### 数据范围与资源关系

- `OWN`：资源的 `user_id` 等于当前认证用户。
- `ASSIGNED`：当前用户存在于新增的 `meeting_member` 表中，且访问级别满足操作要求。
- `ALL`：仅限管理员，并记录目标资源、原因、请求编号和结果。
- 会话、资料、摘要、行动项、音频等子资源必须通过所属 `meetingId` 或 `sessionId` 反查父资源授权，不接受客户端声明归属。
- 对没有 `meetingId` 的历史会话，暂以会话 `user_id` 作为所有者；迁移后优先绑定会议。

建议新增：

```text
meeting_member(meeting_id, user_id, access_level, assigned_by, create_time)
audit_log(actor_type, actor_id, role, permission, resource_type, resource_id,
          action, result, request_id, ip, detail_json, create_time)
share_token(id, token_hash, session_id, expires_at, revoked_at, create_time)
```

`meeting_member.access_level` 第一阶段只使用 `VIEW` 和 `OPERATE`，避免过早引入复杂 ACL。

### 认证与后端改造

1. 引入完整 Spring Security，使用 `SecurityFilterChain`、Bearer Token 过滤器和统一 `Authentication` 主体。
2. 将自定义令牌替换为标准 JWT。至少包含 `sub`、`username`、`role`、`tokenVersion`、`jti`、`iat`、`exp`、`iss` 和 `aud`。
3. 权限矩阵由服务端根据角色解析；JWT 包含角色但不长期固化全部权限，角色变化时递增 `token_version`，旧令牌立即失效。
4. `si_user` 新增 `status`、`token_version`、`last_login_time`、`password_changed_at`；`role` 改为非空并只允许固定值。
5. 建立 `AuthenticatedActor`，提供 `userId / username / role / permissions / tokenVersion`；异步任务必须显式传入 actor 或资源所有者，不能依赖线程请求上下文。
6. 建立 `AccessPolicyService`，集中实现 `requirePermission()`、`requireMeetingAccess()`、`requireSessionAccess()`、`requireFileAccess()` 等资源授权，删除分散的 `requireSelf()` 与失效开放式 `requireOwner()`。
7. Controller 保持 `controller -> facade -> service -> mapper` 分层；Controller 只声明权限和传递认证主体，资源授权放在统一策略层并由 Facade 调用。
8. 认证失败返回 401，身份有效但权限不足返回 403，资源不存在或无权感知时可统一返回 404，避免资源枚举。
9. 敏感操作写审计日志；禁止记录 JWT、分享令牌、服务密钥、密码或完整查询字符串。

### 用户管理接口与管理员页面

新增人员账号接口：

```http
GET    /api/auth/me
PUT    /api/me/profile
PUT    /api/me/password
GET    /api/admin/users
POST   /api/admin/users
PUT    /api/admin/users/{id}
PUT    /api/admin/users/{id}/role
PUT    /api/admin/users/{id}/status
POST   /api/admin/users/{id}/reset-password
POST   /api/admin/users/{id}/revoke-sessions
GET    /api/admin/audit-logs
```

- 生产环境关闭公开注册；如必须保留，注册账号只能进入 `PENDING` 或最低权限 `VIEWER`，由管理员启用。
- 管理员不能停用或降级系统中最后一个有效管理员。
- 密码重置使用一次性临时密码或邀请链接，并要求首次登录修改；禁止返回密码哈希。
- 用户列表支持用户名、姓名、邮箱、角色和状态筛选；角色与状态修改必须二次确认并写审计。
- 前端新增 `AuthProvider` 和权限守卫；登录后调用 `/api/auth/me`，不再由各页面直接读取 `localStorage.userId`。
- 全局响应拦截器统一处理 401 退出登录和 403 无权限提示；路由、导航和按钮按权限显示，但不替代后端授权。

### 现有接口迁移规则

1. **本人资源接口**：移除 `userId` 参数，由认证主体推导。包括会议列表/创建、会话列表、术语、热词、语言偏好、音频、本人费用、会前用量和跨会议问答。
2. **资源接口**：按 `meetingId / sessionId / fileId / actionItemId / summaryId` 反查父资源，再校验权限与数据范围。包括会议资料、摘要、发言摘要、行动项、记录、说话人映射、开始/停止同传和下载。
3. **全局管理接口**：系统人员目录写操作、用户管理、全部费用、日志和重建任务只允许管理员；人员目录读取允许操作员。
4. **公开分享接口**：删除按 `userId` 查询活动会话的公开入口；分享 URL 使用不可猜测、可过期、可撤销的能力令牌，HTTP 与分享 WebSocket 均校验同一令牌。
5. **ASR WebSocket**：握手后写入完整认证主体；首个 `start` 消息校验 `INTERPRETATION_OPERATE` 和会话归属，并将该连接绑定到一个授权会话；后续 audio/stop 只能操作已绑定会话。
6. **Teams Bot**：Java `/bot-api/**` 必须先校验人员权限，再由 Java 使用独立服务密钥调用 C# Bot；C# 业务接口校验该服务密钥。Bot Framework `/api/messages` 继续使用平台认证，不与业务代理密钥混用。
7. **管理接口**：浏览器使用管理员 JWT 和权限；`X-Admin-Secret` 仅保留给受控运维自动化，禁止查询参数 secret，并记录调用主体。

### 数据库与配置迁移

- 引入 Flyway 或 Liquibase，权限相关表结构、角色回填和索引全部通过版本化迁移执行，不在 Service `@PostConstruct` 中新增权限 DDL。
- 将现有用户角色回填规则明确化：当前唯一受控管理员账号设为 `ADMIN`；秘书处实际操作账号设为 `OPERATOR`；其他账号默认 `VIEWER` 或 `PENDING`。
- 上线前删除固定默认管理员密码，要求通过部署密钥或一次性初始化流程创建首个管理员。
- 生产环境对 `DB_PASSWORD`、`JWT_SECRET`、`ADMIN_API_SECRET`、`TEAMS_BOT_API_SECRET` 做启动必填校验，禁止使用仓库默认值。
- JWT 密钥轮换采用 `kid` 或双密钥过渡；访问令牌建议 30 分钟，角色/状态变更通过 `token_version` 立即撤销。

### 分阶段实施顺序

| 阶段 | 内容 | 完成门槛 |
|---|---|---|
| 0. 基线与报告模式 | 固化 98 个接口策略清单；确定现有账号角色；新增越权报告日志 | 不拦截生产业务，但能统计 userId 不一致和缺失策略 |
| 1. 身份基础 | Spring Security、标准 JWT、`AuthenticatedActor`、账号状态、tokenVersion、`/api/auth/me` | 登录、过期、禁用、撤销和角色变化测试通过 |
| 2. 权限与用户管理 | 权限枚举、固定矩阵、统一策略服务、用户管理接口和管理员页、审计日志 | admin/operator/viewer 功能边界测试通过 |
| 3. 收口前端 userId | 本人接口从认证主体取 userId；移除前端默认用户 1 和散落 localStorage 读取 | 修改 userId 参数不能访问其他用户数据 |
| 4. 资源授权 | meeting_member、会议及全部子资源授权、ASR WebSocket 会话绑定 | own/assigned/other 三类资源矩阵通过 |
| 5. 公共与服务边界 | 分享能力令牌、Bot 服务密钥、管理员接口分离 | 分享令牌过期/撤销和 Bot 未授权测试通过 |
| 6. 强制执行与清理 | 从 `REPORT_ONLY` 切换 `ENFORCE`，删除兼容参数和旧鉴权路径 | 无受保护接口缺少显式策略，完整回归通过 |

切换期间使用 `AUTHZ_MODE=REPORT_ONLY|ENFORCE`。报告模式只记录本应拒绝的请求；确认正式会议、分享页和 Bot 没有误拦截后，再按接口域逐步强制执行。

### 验收标准

- 所有 98 个 HTTP 接口、3 个 WebSocket 路径和 C# Bot 业务接口都有明确的公开、人员权限、服务身份或分享能力令牌策略。
- 所有本人资源接口不再接受决定归属的 `userId`；前端不再默认使用用户 `1`。
- `ADMIN / OPERATOR / VIEWER` 对自己的、被分配的和其他人的资源均符合权限矩阵。
- 禁用账号、角色变更和撤销会话能使已有令牌失效；无效、过期、篡改令牌返回 401。
- 资源越权返回 403 或防枚举 404；敏感操作和管理员跨用户访问均有审计记录。
- ASR WebSocket 不能操作未授权会话；公开分享令牌只能读取一个指定会话并支持过期、撤销。
- Java Bot 代理和 C# Bot 业务接口均拒绝缺少或错误服务凭证的调用。
- 后端完整测试、前端构建与浏览器权限流程、Bot 构建、数据库迁移和端到端正式会议回归全部通过。

### 遗留问题与实施决策

- 需由业务确认现有账号的初始角色，以及操作员是否允许修改被分配会议还是仅操作同传。本方案默认被分配操作员可操作会议，查看者只读。
- 第一阶段不支持任意自定义角色；若未来确有多部门差异，再增加 `role / permission / role_permission / user_role` 动态模型。
- 权限改造涉及大量历史接口和跨模块契约，必须按阶段提交和部署，不能一次性整体强制拦截。

## Weekly Optimization Record: 2026-W25 AI Q&A Optimization Final Implementation

### Goal

- Implement the final Track B AI Q&A optimization path, not a minimum version.
- Preserve all existing server data and avoid destructive vector rebuilds.
- Deliver evaluation baseline, retrieval optimization, grounded answers, multi-turn context, streaming latency safeguards, and automated verification.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Final plan document | Done | See `docs/ai-qa-optimization-final-plan-2026-W25.md`. |
| Data-safe embedding profile | Done | Added model, dimension, profile, hash, status, and timestamp metadata. |
| Profile-aware rebuild | Done | Rebuild and regeneration paths write only the active profile while preserving legacy/default vectors. |
| Evaluation baseline and runner | Done | Added deterministic scoring service plus admin score/run endpoints, a reusable request template, and a local report runner for generated-answer coverage reports. |
| RAG quality tuning | Done | Enabled fail-open query expansion and rerank by default; added helper timeout, max query cap, per-query recall cap, and recall/rerank timing metrics. |
| Grounded source answer | Done | Added grounded context requirements and structured source anchors before LLM answer generation. |
| Multi-turn streaming Q&A | Done | Added optional Teams Bot history, bounded backend history, and Bot-side recent chat window. |
| Latency safeguards | Done | Added stream status events; C# Bot filters status markers from user-visible output. |

### Data Safety Rules

- Do not run `TRUNCATE interpretation_embedding`.
- Do not clear and rebuild production embeddings as the optimization path.
- Do not overwrite older profile embeddings when introducing a new profile.
- Business cleanup deletes embeddings only for deleted source resources.
- Keep rollback possible by switching retrieval profile/configuration.

### Affected Modules

- Backend RAG and embedding services.
- Embedding mapper and migration scripts.
- Teams Bot Q&A streaming path.
- Backend automated tests.
- Canonical optimization and validation documents.

### Acceptance Criteria

- Existing data remains searchable.
- New and legacy/default embedding profiles can coexist.
- Query rewrite, expansion, decomposition, hybrid retrieval, rerank, source grounding, and multi-turn context have automated coverage.
- Baseline and optimized evaluation reports can be generated.
- `mvn test` passes before manual validation.

### Residual Issues

- Production migration still requires backup and rehearsal on a copied database before enabling profile-aware rebuild on the server.

## Weekly Optimization Record: 2026-W25 Commercial Release Hardening and Linux Deployment Cleanup

### Goal

- Treat the repository as a completed commercial project ready for GitHub publishing and Linux server deployment.
- Remove active ngrok usage from scripts, deployment docs, and Teams production guidance.
- Replace stale Teams Bot / SharePoint / auto-join planning docs with current capability boundaries.
- Add the missing commercial project skeleton: README, proprietary license, security policy, contribution guide, changelog, CI, Dependabot, Linux deployment templates, and production checklist.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Active ngrok removal | Done | `start-all.bat` and `stop-all.bat` no longer require or manage ngrok. |
| Teams Bot source path cleanup | Done | Local startup now points to `bot/CallingBotSample` instead of the old external sample path. |
| Production ingress clarification | Done | Nginx routes `/bot-api/**` through Java authorization, while `/api/messages` goes directly to C# Bot. |
| GitHub project skeleton | Done | Added root README, LICENSE, SECURITY, CONTRIBUTING, CHANGELOG, CI, and Dependabot. |
| Linux deployment templates | Done | Added backend env, Bot appsettings, systemd, and Nginx templates under `deploy/linux/`. |
| Stale docs cleanup | Done | Replaced outdated deployment and Teams planning docs with current, concise instructions. |
| Artifact hygiene | Done | Expanded `.gitignore` and `.dockerignore`; removed tracked temporary Bot audio files. |

### Affected Modules

- Root startup/shutdown scripts.
- Root and backend Dockerfiles.
- Frontend Vite proxy and user-facing Teams notification help text.
- Teams Bot manifest and example production config.
- Deployment and commercial readiness documentation.
- GitHub CI/dependency automation.

### Acceptance Criteria

- No active script starts or requires ngrok.
- Production docs direct Azure Bot to `https://<domain>/api/messages`.
- `/bot-api/**` is documented and configured as a Java-authorized path.
- GitHub publishing does not include secrets, local model downloads, temporary Bot WAV files, or generated app packages.
- Commercial operators have one canonical Linux deployment path and one go-live checklist.

### Residual Issues

- Real production domain, privacy policy URL, terms URL, Teams app IDs, Azure Bot credentials, and server secrets remain manual deployment inputs.
- Historical weekly records still mention old experiments for auditability; they are not current instructions.

## 周度优化记录：2026-W26 印尼语→中文翻译质量增强（ASR 后处理 + LLM 纠错翻译）+ 分享音量统一

### 本周目标

- 解决印尼语→中文同传质量差的问题：专业词/缩写被 ASR 听错、句子被切碎、`dari` 误译为“发件人”、术语未发挥作用。
- 在不破坏现有分层（controller→facade→service→integration→mapper）的前提下，把 id→zh 改为“先纠错再翻译”，并把术语真正喂给模型。
- 统一三种语言（含源语言原声）的分享播放音量。
- 所有改动可回滚、可配置、失败可降级，绝不阻断实时链路。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| 分享音量统一（响度归一化） | 已完成 | `OpusStreamEncoder.feed()` 编码前做目标 RMS 归一化（跨块平滑 + 块内斜坡 + 限幅 + 静音不放大），各语言/音色收敛到同一响度。 |
| 印尼语成句（wtpsplit） | 已完成 | 服务器开启 `SEGMENTATION_SERVICE_ENABLED=true`（speaker-service `sat-3l-sm` 模型，首启自动下载），印尼语先成完整句再翻译。 |
| 印尼语分句最小句长闸门 | 已完成 | 新增 `AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS`（默认 24）：wtpsplit 检测到的过短边界不切，避免 “satu/nine/depan 4” 碎片。 |
| 静音切句/超长兜底调参 | 已完成 | 线上 `AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS=800`、`AZURE_ASR_MAX_SEGMENT_WORDS=35`，减少停顿误切与句中硬切。 |
| 印尼语数字格式归一化（译前） | 已完成 | `TranslationService` 仅对印尼语源文本：千分位 `.` 去除、小数 `,`→`.`（如 60.390,8→60390.8）。 |
| id→zh LLM 纠错翻译 | 已完成 | 新增 `LlmIntegration.correctAndTranslateIndonesianToChinese`：按棕榈施肥/缺素/叶片分析语境先纠 ASR 错词再翻中文；`TranslationService` 路由 id→zh 走 LLM，默认开启，失败/超时回退 Google。 |
| 滑动上下文消歧 | 已完成 | `RealtimeInterpretationFacade` 维护会话级印尼语源文本滑动窗口（去重 + 600 字截断），供 LLM 消歧（如 kang→K/钾）。 |
| 术语喂给 LLM：精确 + 模糊 | 已完成 | 精确命中（原文确有该词）标“必须遵守”；新增 `TerminologyService.fuzzyIdToZhHints`（有界 Levenshtein）把形近术语（kupu≈pupuk、buron≈boron）标“参考”，解决“听错就匹配不上”。 |
| LLM 元话语拦截 + 译文清洗 | 已完成 | 拦截“无法判断/疑似识别错误/说明/我注意到”等解释性输出（命中→回退 Google），并清除（疑似…）括号注释，绝不让元话语进入 TTS/记录。 |
| 专名/称谓锁定 | 已完成 | 提示词固定：`pacar/pacarmen/pacar men/pak carmen/pak jaren/pak cermin → 董事长`；`julong/culong → 聚龙`；`starling → 星链`、`starship → Starship`、`satelit → 卫星`。 |
| 词典“未生效”根因定位 | 已完成 | 证实术语本就生效（id→zh 支持），问题是个别词条本身标错（humas→环境部）、一词一译无法随语境、专业词未覆盖；已产出合并修正总表供清空重导。 |

### 可调参数（运维调优用）

| 参数（环境变量） | 默认 | 线上建议 | 含义 / 调法 |
|---|---|---|---|
| `OPENAI_ID_ZH_LLM_TRANSLATE_ENABLED` | true | true | id→zh 是否走 LLM 纠错翻译；false 回退普通 Google 翻译。 |
| `OPENAI_ID_ZH_LLM_TRANSLATE_MODEL` | anthropic/claude-haiku-4.5 | 同默认 | 纠错翻译模型；需快、控延迟。 |
| `OPENAI_ID_ZH_LLM_TRANSLATE_TIMEOUT_MS` | 4000 | 4000~6000 | 超时即回退 Google；大量 fallback 时调大。 |
| `OPENAI_ID_ZH_LLM_TRANSLATE_CONTEXT_CHARS` | 600 | 600 | 滑动上下文字符数；调小略降延迟、消歧变弱。 |
| `AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS` | 24 | 24 | 印尼语 wtpsplit 最小句长；嫌碎调大(32)，黏句调小(16)。 |
| `AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS` | 300 | 800 | 停顿多长算一句结束；越大句子越完整、延迟越高。 |
| `AZURE_ASR_MAX_SEGMENT_WORDS` | 25 | 35 | 超长句兜底硬切词数；调大句子更完整、延迟略增。 |
| `SEGMENTATION_SERVICE_ENABLED` | false | true | 印尼语 wtpsplit 成句开关（需 speaker-service 装 wtpsplit + 模型）。 |

### 实施结果

- 实测同一篇“九宫格防火/卫星战略”讲话：从最初“近半看不懂、词堆”到现在整句通顺、专业术语正确、董事长称谓与公司名锁定。
- `dari→发件人`、`kupu/buron/pH→…`、碎句等系统性问题基本消除；剩余为个别 ASR 偶发听错（如 bernilai→香草）。

### 影响范围

- 后端：`OpusStreamEncoder`、`TranslationService`、`LlmIntegration`、`TerminologyService`、`RealtimeInterpretationFacade`、`AzureAsrIntegration`、`AzureSpeechProperties`、`OpenAiProperties`、`application.yml`。
- 前端：无（纯后端 + 配置）。
- 配置 / 数据：backend.env 新增上述环境变量；术语建议清空后导入“合并修正总表”（用开会账号导入，account 与会话 userId 必须一致）。
- speaker-service：启用 `/segment-boundary`（wtpsplit `sat-3l-sm`）。

### 验收标准

- 日志可见 `idZhCorrectTranslate` 与 `translate end (llm id->zh)`，无大量 `fallback to google`。
- 日志可见 `force-segment by=sentence-wtpsplit`，且无 <24 字的印尼语碎片段。
- 译文中无 `发件人`、无 LLM 元话语（无法判断/说明/疑似识别错误）。
- `pacarmen` 等→“董事长”，`julong`→“聚龙”，数字量级正确。
- 全量 `mvn test` 通过（339+）。

### 遗留问题

- 个别 ASR 听错无法靠文本恢复（bernilai→香草、akre→等）；后续可考虑印尼语 Azure 自定义语音模型（Custom Speech）。
- LLM 纠错翻译比 Google 多约 1~2.5s/句延迟（并发执行、不累加）；按需用 timeout/context 调。
- 合并修正总表需运维手动清空重导；后续可考虑内置默认术语种子。

## Weekly Optimization Record: 2026-W26 Meeting File Full-Text Extraction Wiring

### Goal

- Fix the gap where "upload meeting report file" used `/api/meetings/{meetingId}/files` and only saved/vectorized content, while full-text hotword, terminology, and meeting knowledge extraction only ran for `/api/pre-meeting/upload`.
- Ensure uploaded report PDFs such as the three bilingual fertilizer budget documents feed the same extraction pipeline as pre-meeting materials.
- Keep extraction asynchronous and best-effort so upload latency and success are not blocked by LLM or parsing failures.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Shared extraction orchestrator | Done | Added `MeetingMaterialExtractionService` to run hotword extraction, meeting knowledge pack generation, terminology extraction, and agenda entity hotwords with isolated step-level failures. |
| Ordinary meeting file upload wiring | Done | `MeetingService.uploadFile` now enqueues full-text extraction after persisting `/api/meetings/{meetingId}/files`; the extraction input includes both file name and parsed text. |
| Pre-meeting upload reuse | Done | `PreMeetingController.upload` now delegates to the shared orchestrator instead of duplicating async extraction logic. |
| Tests | Done | Added focused orchestrator tests and updated meeting upload tests to assert extraction is enqueued for ordinary report uploads. |

### Affected Modules

- Backend services: `MeetingService`, `MeetingMaterialExtractionService`, `PreMeetingController`.
- Existing extraction services remain unchanged: `HotwordExtractionService`, `MeetingKnowledgeService`, `TerminologyExtractionService`.
- No frontend API contract change; the existing upload buttons keep their current endpoints.

### Acceptance Criteria

- Uploading via `/api/meetings/{meetingId}/files` logs `MeetingService uploadFile done` followed by `MeetingMaterialExtractionService extract start`.
- The same upload produces downstream logs from `HotwordExtractionService`, `LlmIntegration extractMeetingKnowledgePack`, and `TerminologyExtractionService`.
- Upload response is not blocked by extraction failures; a failed step logs `MeetingMaterialExtractionService step failed` and later steps still run.
- Focused backend tests pass, then full `mvn test` passes.

### Residual Issues

- Real extraction still depends on configured LLM credentials and runtime quota.
- Large files trigger multiple asynchronous LLM calls; operators should leave processing time after upload before checking hotword/terminology pages.

## Weekly Optimization Record: 2026-W26 Meeting Material Extraction LLM Stabilization

### Goal

- Stabilize the asynchronous extraction path for uploaded meeting materials after the full-text wiring fix was deployed.
- Make hotword, terminology, and meeting knowledge extraction return usable JSON instead of failing when a reasoning-heavy model consumes the output budget.
- Keep the extraction model configurable independently from the realtime compression / translation model.

### Evidence From Production Logs

- Deployment of `8934134` proved the upload wiring is correct: `/api/meetings/53/files` saved the three uploaded PDFs and then started `MeetingMaterialExtractionService` for fileIds `95`, `96`, and `97`.
- The remaining failure was not upload or async orchestration. Logs showed many OpenRouter responses from `deepseek/deepseek-v4-pro` with `finishReason=length`, `content=null`, and a large `reasoning` field.
- Because extraction expects strict JSON in `message.content`, those responses produced empty-content failures or partial chunk results.
- Some terminology chunks did succeed, proving the parser and persistence path can work when the LLM returns normal content.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Chunk-level tolerance and diagnostics | Deployed | `8934134` keeps later chunks/steps running when one LLM response is empty or malformed, and logs extracted `terms=` / `phrases=` samples when chunks succeed. |
| Dedicated extraction model | Implemented, pending server deployment | Added `OPENAI_EXTRACTION_MODEL`, defaulting to `anthropic/claude-haiku-4.5`, so extraction no longer has to share the reasoning-heavy summary/compression model. |
| OpenRouter no-reasoning request options | Implemented, pending server deployment | Added `OPENAI_EXTRACTION_DISABLE_REASONING=true` and sends `reasoning.effort=none`, `reasoning.exclude=true`, and `include_reasoning=false` for extraction calls when using OpenRouter. |
| Deployment template update | Implemented | `deploy/linux/env/backend.env.example` now documents the extraction model and reasoning-disable settings. |
| Regression tests | Done locally | Added request-option tests and reran focused extraction tests plus the full backend test suite. |

### Affected Modules

- Backend config: `OpenAiProperties`.
- LLM integration: `LlmIntegration` request construction for meeting knowledge, terminology, and hotword extraction.
- Deployment env example: `deploy/linux/env/backend.env.example`.
- Backend tests: `LlmRequestOptionsTest` plus existing extraction repair/chunking coverage.

### Acceptance Criteria

- After the next server deployment, extraction logs should show `model=anthropic/claude-haiku-4.5` or the explicitly configured `OPENAI_EXTRACTION_MODEL` for meeting knowledge, terminology, and hotword extraction.
- Re-uploading the three bilingual PDFs should no longer produce repeated extraction failures with `finishReason=length` and `content=null`.
- Logs should show successful chunk samples such as `chunk extracted ... terms=` and `chunk extracted ... phrases=`.
- The database should receive new `terminology` rows with `source_sheet='AUTO_DOC'` and new `asr_hotword` rows with `source_type='AUTO_EXTRACTED'` when the model returns candidates.

### Residual Issues

- Existing failed uploads, including fileIds `95`, `96`, and `97`, will not automatically reprocess. Operators should re-upload the reports or add a manual reprocess endpoint/job.
- Extraction quality remains model-dependent; after the no-reasoning model is deployed, the next tuning round should review duplicate terms, cross-language coverage, and noisy domain phrases.
- The mojibake seen in some terminal output is an encoding/display issue and is separate from extraction correctness.

## Weekly Optimization Record: 2026-W26 ASR Final Remainder Alignment

### Goal

- Fix forced ASR segmentation seams where Azure interim text was emitted early, then Azure final text rewrote casing, punctuation, or token formatting.
- Stop using the interim `emittedLen` character offset directly against final text.
- Preserve realtime forced segmentation while making final remainders align by previously emitted text.

### Evidence From Logs

- `dbg-stream.log` showed `force-segment by=sentence-wtpsplit` emitted `amerika industri indonesia semua akan digabungkan menjadi 1`.
- The following Azure final remainder started with `i 1 sistem industri...`, proving the interim offset landed inside a rewritten final token.
- The issue is caused by final text drift, not by queue delay or translation latency.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Track complete emitted text | Done | `AzureAsrIntegration.AsrSession` now records every forced final segment in an emitted-text buffer, not only `emittedLen` and suffix. |
| Final text realignment | Done | Final remainder calculation aligns final text by the complete emitted text first, then uses suffix alignment as fallback. |
| Normalized comparison | Done | Alignment ignores casing, punctuation, and whitespace, and normalizes simple English/Indonesian number words such as `satu` / `one` to `1`. |
| Boundary cleanup | Done | If fallback offset lands inside a word, the remainder start moves to the next token boundary, then performs token-level overlap cleanup. |
| Regression coverage | Done | Added tests for the observed `i 1 sistem` seam, punctuation insertion, number normalization, fallback half-word cleanup, and duplicate-tail trimming. |

### Affected Modules

- Backend ASR integration: `AzureAsrIntegration`.
- Backend ASR regression tests: `AzureAsrFinalRemainderTest`.
- Existing downstream overlap protection in `AsrService` remains in place as a second safety net.

### Acceptance Criteria

- Azure final remainders no longer start with leaked tails such as `i 1`, `epan`, `nal`, or duplicated boundary words after a forced segment.
- Logs may show `final remainder aligned` when final text had to be realigned by emitted text or overlap cleanup.
- Existing realtime segmentation still emits from the stable interim prefix and does not wait for full Azure utterance finalization.
- Focused ASR seam tests pass and full backend `mvn test` passes.

### Residual Issues

- This fix handles deterministic text drift at forced/final seams. It does not correct underlying ASR word substitutions such as domain words being misheard.
- More complex number expressions beyond simple `zero` through `ten` / `nol` through `sepuluh` may still need future normalization.

## Weekly Optimization Record: 2026-W26 Realtime TTS Queue and Indonesian Segmentation Stability

### Goal

- Keep the user's hard requirement: audio that has already been synthesized and sent to the frontend must play in order and must not be skipped by backend catch-up logic.
- Allow only unsynthesized TTS items to be skipped after they have waited too long behind earlier audio, reducing live backlog without cutting already-started playback.
- Reduce Indonesian ASR fragmenting by raising the minimum sentence boundary length and preventing fallback split paths from emitting very short Indonesian pieces.
- Reduce Cartesia odd-audio risk after long-run WebSocket failures by invalidating failed TTS connections instead of returning them to the pool.
- Tighten id->zh LLM correction so meta commentary is discarded and falls back before it can enter transcript/TTS.

### Evidence From 2026-06-27 Long Test Logs

- `dbg-stream-full.log` covered about 40 minutes and produced 366 transcript rows for session `0b9a50b7-7ad0-4092-9d62-96b75c505879`.
- Forced ASR segmentation still had 73 Indonesian segments shorter than 48 characters, and several `force-backstop` segments reached 419-576 characters.
- Previous final remainder tail leakage such as `i 1 sistem` was gone, but 10 final remainders still started with punctuation such as `. Dunia...` or `, dana...`.
- Client latency was dominated by playback backlog: `playbackRateMilli=1000`, average backlog about 4.0s, p95 about 16.6s, max about 22.9s.
- Cartesia logged three WebSocket ping timeout failures during the meeting; no TTS playback timeout occurred, so failed idle connection reuse was treated as the main backend-side odd-audio risk.
- id->zh LLM meta commentary was detected and fell back twice; the transcript export did not contain those meta strings, but the prompt/request path still needed tighter prevention.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Ordered TTS synthesis gate | Done | `RealtimeInterpretationFacade` now waits for the previous TTS reservation before starting synthesis for the next item. Already-sent audio remains ordered and uninterrupted. |
| Unsynthesized skip gate | Done | Added `CARTESIA_TTS_UNSYNTHESIZED_SKIP_WAIT_MS` / `cartesia.tts.unsynthesized-skip-wait-ms`, default 12000ms. Only items that have not started synthesis can be skipped. |
| Indonesian minimum segment length | Done | Raised default `AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS` from 24 to 48 and applied the minimum to wtpsplit, comma, boundary, backstop, and optional LLM boundary paths. |
| Backstop segment cap | Done | `force-backstop` now uses the configured word/character limit before falling back to the near-whole working buffer, reducing 400-500 character bursts. |
| Final remainder punctuation cleanup | Done | Final remainders are trimmed through the existing separator cleanup so the next segment does not start with isolated punctuation. |
| Cartesia failed-connection invalidation | Done | TTS WebSocket failures now invalidate the pooled client and clear the failed socket, instead of returning a failed client for reuse. |
| id->zh no-reasoning and meta guard | Done | Realtime id->zh correction uses no-reasoning chat options on OpenRouter and catches the meta phrases observed in the long test. |
| Deployment env documentation | Done | `deploy/linux/env/backend.env.example` documents the ASR and TTS queue tuning variables. |

### Affected Modules

- Backend realtime pipeline: `RealtimeInterpretationFacade`.
- Backend ASR integration: `AzureAsrIntegration`, `AzureSpeechProperties`, `application.yml`.
- Backend TTS integration/config: `CartesiaStreamingIntegration`, `CartesiaProperties`, `application.yml`.
- Backend LLM integration: `LlmIntegration`.
- Deployment template: `deploy/linux/env/backend.env.example`.
- Tests: `RealtimeInterpretationOrderTest`, `AzureAsrFinalRemainderTest`, `LlmIdZhSanitizeTest`.

### Acceptance Criteria

- Logs show `TTS unsynthesized skipped` only for items that waited longer than the configured threshold before synthesis started.
- Logs show `TTS first chunk ... orderedWaitMs=...`, proving synthesis begins after the ordered wait rather than before it.
- No `sent tts_audio` or share-audio stream is intentionally cut by the backend once chunks have started.
- Indonesian `force-segment` rows shorter than 48 visible characters should disappear except for Azure finalization paths that are not forced interim emits.
- Final remainders should not start with `.`, `,`, `?`, or similar standalone punctuation.
- Cartesia WebSocket failures should be followed by failed-client invalidation logs and should not reuse the failed client.
- id->zh meta strings such as `这句话在输入中...`, `根据上文...`, or `咨询词汇上下文后...` should not appear in exported transcripts.

### Residual Issues

- Skipping unsynthesized TTS reduces backlog but may omit late, not-yet-audible translations when the meeting is already too far behind. This is an explicit trade-off requested for long-waiting unsynthesized audio.
- The frontend playback speed was still `playbackRateMilli=1000` in the long test. If 1.35x playback remains required, the frontend/runtime setting must be verified separately.
- Cartesia source audio quality or provider-side artifacts can still produce odd sound even after failed connection invalidation; future tests should compare default voice vs cloned voice and inspect provider context errors.

## 周度优化记录：2026-W26 印尼语流式分段完整性 Guard（去掉盲切 + 弱边界降级 partial）

### 本周目标

- 解决印尼语 id-ID → 中文链路中「中途强切」把半词/残句当 final 送翻译，导致中文乱码的问题。
- 准确率优先：宁可延迟略升，也不让半词/残句进入翻译、入库、TTS。
- 采用增量改造，不重写分段：保留现有 `emittedLen`、`emittedSuffix` 指纹、`finalRemainderAfterForcedSegments`、`comparableText`、final remainder 重对齐、`TranscriptOverlapTrimmer`。
- 取舍采用「折中策略」：强边界（句末标点 / wtpsplit 可信边界 / 编号标题前）且通过 Guard 才作为 final；弱边界（逗号 / 词边界 / 超长 / 超时 backstop）一律降级为 partial。

### 现状核查（生产，2026-06-27）

- `SEGMENTATION_SERVICE_ENABLED=true`、`sat_model_loaded:true`、端点延迟约 7ms：wtpsplit 真在工作且是主力切法（实测 `sentence-wtpsplit` 占约 59%）。
- 但 `force-boundary` 盲切约占 35%（6 秒超时后无语言学判断的词边界硬切），叠加 `MIN_SENTENCE_EMIT_ID_CHARS` 默认 48 把短 wtpsplit 强边界推给盲切 —— 这是半词/残句的根因。
- `OPENAI_ID_ZH_LLM_SEGMENT_ENABLED=false` 保持（实测效果不佳，方案明确不启用）。

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| `IndonesianIncompleteGuard`（新类，纯逻辑可单测） | 已完成 | `check`（半词尾/连接词尾/可疑词头/纯噪声）、`boundaryVeto`（切断固定短语/数字↔单位词/制造可疑词头）、`isNumberedTitle`/`findNumberedTitleBoundary`、`decideEmit`（综合给出 EMIT_FINAL/DOWNGRADE_PARTIAL/HOLD/DROP）。 |
| 弱边界降级 partial（去掉盲切） | 已完成 | `AzureAsrIntegration.AsrSession` 三处发段点（force-backstop / 正常 force-segment / llm-boundary）接入 `decideEmit`，弱边界不再以 `isFinal=true` 发出，不推进 `emittedLen`，由现有 interim 显示路展示 partial。 |
| final remainder 发出前 Guard | 已完成 | `transcribed` 终稿 remainder 发出前过 `check`，HOLD/DROP 抑制（不进翻译/入库/TTS）。 |
| 翻译入口纵深防御 | 已完成 | `RealtimeInterpretationFacade.translateAndStreamTts` 对 id 源文本进翻译前再过一次 `check`，非 PASS 直接释放 TTS 预约并返回。 |
| minId 软化 | 已完成 | Guard 开启时不再用 `minSentenceEmitIdChars` 死卡 wtpsplit 强边界；短强边界交给 `decideEmit`（强边界 + 完整性检查）放行/扣回。 |
| 编号标题强边界 | 已完成 | 新增 `numbered-title` 强边界候选，在 `13 pemikiran` 这类列表编号前切；`10 juta`（数字+单位词）不识别为编号、不被切断。 |
| 发段前 Guard 日志 | 已完成 | 新增 `[IdGuard] ...`（init/HOLD/DOWNGRADE/DROP/final remainder suppressed/facade suppress）。 |
| 开关与回滚 | 已完成 | `azure.asr.id-segment-guard-enabled`（env `AZURE_ASR_ID_SEGMENT_GUARD_ENABLED`，默认 `true`）；置 false 完整回退旧强切行为。 |
| 不启用 LLM 实时分句 | 维持关闭 | `id-zh-llm-segment-enabled` 保持 false（实测效果不佳）。 |

### 生产配置建议

| 配置 | 现状 | 建议 |
|---|---|---|
| `SEGMENTATION_SERVICE_ENABLED` | true | 保持 true（wtpsplit 为强边界主力） |
| `AZURE_ASR_ID_SEGMENT_GUARD_ENABLED` | 新增 | true（准确率优先） |
| `SEGMENTATION_SERVICE_TIMEOUT_MS` | 100 | 100~120（实测约 7ms，余量充足） |
| `AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS` | 600 | 700~800（更依赖 Azure 终稿，换更完整整句） |
| `AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS` | 48 | 保持 48（现仅作弱边界地板，强边界已由 Guard 放行） |
| `AZURE_ASR_MAX_SEGMENT_WORDS` / `MAX_SEGMENT_CHARS` | 0 / 0 | 保持 0（其强切为弱边界，已降级 partial，不产 final） |
| `OPENAI_ID_ZH_LLM_SEGMENT_ENABLED` | false | 保持 false |

### 影响范围

- 后端：新增 `service/IndonesianIncompleteGuard.java`；改动 `integration/AzureAsrIntegration.java`、`facade/RealtimeInterpretationFacade.java`、`config/AzureSpeechProperties.java`、`application.yml`；测试 `service/IndonesianIncompleteGuardTest.java`、`facade/RealtimeInterpretationOrderTest.java`（构造参数补 Guard mock）。
- 前端：无。
- 配置 / 数据：`backend.env` 建议补 `AZURE_ASR_ID_SEGMENT_GUARD_ENABLED=true`（与 `SEGMENTATION_SERVICE_ENABLED=true`）。

### 验收标准

- 正式 final 印尼语段不再出现：`…peng`、`tuk setiap`、`sepak/bola`、`Amerika/Serikat`、`masa/depan`、连接词结尾残句。
- 部署后日志：`force-segment by=force-boundary` 占比大幅下降，final 以 `sentence-wtpsplit`/`sentence-punct`/`numbered-title` 为主；出现 `[IdGuard]` HOLD/veto；翻译/TTS 不再收到半词。
- 单测全绿（见对应周度验证记录）。

### 遗留问题

- 连续无停顿长句的 final 延迟会上升（等 Azure 静音终稿）；partial 原文仍实时显示，属准确率优先的预期代价。
- final remainder 的 HOLD/DROP 当前为「抑制」，未做跨 utterance 的 heldRemainder 接续（P0 不做，避免跨句/跨说话人风险）；如线上发现明显内容丢失再评估二期接续。
- wtpsplit 仍可能给出切断固定短语的强边界，已由 `boundaryVeto` 二次拦截；词表/短语表当前为常量，后续可外置配置。
- 服务器侧验证待部署后用真实印尼语长会议日志包复核。

## Weekly Optimization Record: 2026-W26 Indonesian Output Floor Follow-up

### Goal

- Enforce the Indonesian short-sentence output floor on every final output path, not only `sentence-wtpsplit`.
- Remove the short-reply allowlist so `ya`, `baik`, `terima kasih`, and similar short replies are held/merged instead of being emitted as standalone final segments.
- Avoid dropping short Azure final remainders by holding them in a pending buffer and merging them into the next Indonesian final segment.
- Make deployment validation distinguish between "configuration loaded" and "latest output-floor code actually running".

### Progress Update

| Item | Status | Notes |
|---|---|---|
| Remove short-reply allowlist | Implemented locally | `IndonesianIncompleteGuard` no longer contains `SHORT_REPLY_WHITELIST` or `isWhitelistedShortReply`; all short Indonesian segments use `shouldHoldForOutputFloor`. |
| Apply floor before boundary strength | Implemented locally | `decideEmit` now checks output-floor before `isStrongBoundary`, so `sentence`, `sentence-punct`, `sentence-wtpsplit`, `numbered-title`, `force-*`, and `llm-boundary` all follow the same minimum. |
| Final remainder pending merge | Implemented locally | `AzureAsrIntegration.AsrSession` now stores short Indonesian final remainders in `pendingIdFloorText`, logs `HOLD output-floor`, and logs `pending output-floor merged` when the next Indonesian segment releases the combined text. |
| Session-close visibility | Implemented locally | If the meeting ends while a pending short Indonesian fragment is still below the floor, the backend logs `pending output-floor not emitted on close` for explicit audit. |
| Automated regression | Passed locally | Focused `IndonesianIncompleteGuardTest,AzureAsrFinalRemainderTest` passed 30 tests; full `mvn clean test` passed 413 tests. |
| Server deployment check | Gap found | The server was restored to a healthy `--network host --add-host si-mysql:127.0.0.1` deployment, but the `20260628-033113` log package shows the running image did not include the local output-floor follow-up code. |

### Evidence From 2026-06-28 Server Log Package

- Log package: `si-test-logs-20260628-033113.tar.gz`.
- Session under review: `0ff924ad-72b6-4385-9f80-7ca8cd3ae10c`, 96 transcript rows, database time range `2026-06-27 19:19:00` to `2026-06-27 19:30:56`.
- Runtime config was loaded: `minSentenceEmitIdChars=48`, `idSegMinInputChars=40`, `IdGuard enabled=true`.
- The new output-floor logs were absent: `HOLD output-floor=0`, `pending output-floor=0`.
- Short segments still entered transcript/TTS in that server run: one forced `numbered-title` segment had visible length 45, and 11 Indonesian transcript rows were below 48 visible characters.
- Conclusion: this log package is useful as a deployment-gap finding, but it is not a pass/fail validation of the local follow-up implementation.

### Affected Modules

- Backend ASR guard: `IndonesianIncompleteGuard`.
- Backend ASR integration: `AzureAsrIntegration`.
- Backend regression tests: `IndonesianIncompleteGuardTest`, `AzureAsrFinalRemainderTest`.
- Deployment process: after local changes are committed/pushed, the server must pull that exact commit, rebuild the jar with Java 21, rebuild `si-backend:latest`, and restart with the documented host-network command.

### Acceptance Criteria

- Server logs after the corrected deployment show `HOLD output-floor` for short final remainders and `pending output-floor merged` when they are joined into the next Indonesian segment.
- Exported `transcript.tsv` for Indonesian source contains no rows below `AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS` visible characters, except for an explicitly logged session-close pending warning that does not enter transcript/TTS.
- Short replies such as `ya`, `baik`, and `terima kasih` do not appear as standalone final transcript/TTS rows.
- `numbered-title` no longer bypasses the floor when the emitted text is below the configured minimum.

### Residual Issues

- The latest local follow-up still needs a proper release commit/push and server redeploy before another live test can validate it.
- Holding the final short fragment at session close satisfies the "all output goes through the floor" rule, but it means a terminal fragment below the floor is logged rather than emitted. If the business requirement later demands terminal flush with no exception, the floor rule and no-drop rule need an explicit priority decision.

## Weekly Optimization Record: 2026-W27 TTS Indonesian Synthesis Speed

### Supersession Note

- Superseded on 2026-07-02 by `Weekly Optimization Record: 2026-W27 Backend PCM TTS Speed Control`.
- Cartesia synthesis speed is no longer used for zh/id acceleration; it now stays `1.0`, and backend PCM speed-up controls the real forwarded audio duration.

### Goal

- Make zh-CN -> id Indonesian TTS more compact for live interpretation by sending Cartesia `generation_config.speed=1.3` for Indonesian target audio.
- Keep id -> zh-CN Chinese target audio at the existing 1.1 speed and keep English/default targets at 1.0.
- Avoid changing the currently deployed pre-Realtime baseline beyond this TTS speed selection.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Split zh/id TTS speed constants | Done | Replaced the shared zh/id speed constant with `TTS_SPEED_ZH=1.1` and `TTS_SPEED_ID=1.3`. |
| Target-language speed routing | Done | `RealtimeInterpretationFacade.resolveTtsSpeed` now returns 1.3 for `id`/`id-ID`, 1.1 for `zh*`, and 1.0 for other languages. |
| Regression coverage | Done | Added a TTS-layer test that captures the speed passed to `TtsService.synthesizeStream` for id, id-ID, zh-CN, and en-US targets. |

### Affected Modules

- Backend constants: `Constants`.
- Backend realtime orchestration: `RealtimeInterpretationFacade`.
- Backend regression tests: `RealtimeInterpretationOrderTest`.

### Acceptance Criteria

- Logs for zh-CN -> id or zh-CN -> id-ID TTS queueing show `speed=1.3`.
- Logs for id -> zh-CN TTS queueing still show `speed=1.1`.
- Logs for English/default target TTS still show `speed=1.0`.
- Focused `RealtimeInterpretationOrderTest` and full backend `mvn test` pass.

### Residual Issues

- Cartesia speed is provider guidance, not a mechanical time-stretch guarantee. A live listening test is still needed to confirm 1.3 remains intelligible for fast Indonesian output.
- This hotfix line is based on the user-selected server commit `48d8186` to avoid reintroducing later OpenAI Realtime changes.

## Weekly Optimization Record: 2026-W27 TTS Duration Rolling Calibration (Rolled Back)

### Goal

- This passive rolling calibration slice was removed on 2026-07-02 after live-log review showed source speech-window timing was not reliable enough for a hard zh-CN -> id duration budget.
- Keep the active production path simple: Cartesia synthesis speed stays `1.0`, backend PCM speed-up remains Chinese `1.1` and Indonesian `1.3`, and synthesized audio is not truncated or dropped by duration-budget logic.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Rolling estimator service | Rolled back | `SpeechDurationCalibrationService` and its tests were deleted. |
| Queue-time visibility | Rolled back | `estimatedAudioMs`, `calibrationWordSamples`, and `calibrationCharSamples` were removed from `TTS queued`. |
| Completion feedback | Rolled back | `tts-audio-duration` no longer updates a rolling duration estimator. |
| Regression coverage | Updated | Removed calibration-specific tests and kept coverage for backend PCM speed-up, audio forwarding, TTS ordering, and text normalization. |

### Affected Modules

- Backend realtime orchestration: `RealtimeInterpretationFacade`.
- Backend text/TTS helpers: `TtsTextNormalizer`, `TtsPcmSpeedService`.
- Backend regression tests: `RealtimeInterpretationOrderTest`, `TtsTextNormalizerTest`, `TtsPcmSpeedServiceTest`.

### Acceptance Criteria

- `rg` over backend source finds no active `SpeechDurationCalibrationService`, `maxForwardAudioMs`, `overBudget`, `estimatedAudioMs`, or calibration sample fields.
- `TTS queued` keeps speed/order visibility but no queue-time duration estimate.
- `tts-audio-duration` keeps raw and forwarded PCM duration visibility with `truncated=false`, but no duration-budget decision.
- Focused `RealtimeInterpretationOrderTest,TtsTextNormalizerTest,TtsPcmSpeedServiceTest` and full backend `mvn test` pass.

### Residual Issues

- No active duration-budget enforcement remains. Future latency work should focus on Chinese segmentation quality and concise zh-CN -> id translation before TTS, not post-synthesis truncation.

## Weekly Optimization Record: 2026-W27 Backend PCM TTS Speed Control

### Goal

- Stop relying on Cartesia `speed` as the real timing control.
- Send all zh/id/en TTS requests to Cartesia with neutral synthesis speed `1.0`.
- Apply deterministic backend PCM speed-up before audio enters the ordering queue, duration logging, and WebSocket send path: Chinese `1.1`, Indonesian `1.3`, English/default `1.0`.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Neutral Cartesia speed | Done | Replaced target-language Cartesia speed routing with `CARTESIA_TTS_SYNTHESIS_SPEED=1.0`. |
| Backend PCM speed-up | Done | Added `TtsPcmSpeedService` for mono 16-bit PCM time compression using target-language speed rules. |
| Forwarded-duration semantics | Updated | `RealtimeInterpretationFacade` applies PCM speed-up before `forwardedPcmBytes`, `tts-audio-duration`, and `TtsBufferedChunk` enqueueing; removed the obsolete duration-budget and calibration hooks. |
| Log visibility | Done | `TTS queued`, `TTS first chunk`, `TTS synth complete`, and `tts-audio-duration` now log `cartesiaSpeed` and/or `backendPcmSpeed`. |
| Regression coverage | Done | Added `TtsPcmSpeedServiceTest` and updated facade tests to prove Cartesia receives `1.0` while forwarded PCM bytes shrink to real backend speeds. |

### Affected Modules

- Backend constants: `Constants`.
- Backend PCM speed service: `TtsPcmSpeedService`.
- Backend realtime orchestration: `RealtimeInterpretationFacade`.
- Backend regression tests: `TtsPcmSpeedServiceTest`, `RealtimeInterpretationOrderTest`, `TtsTextNormalizerTest`.

### Acceptance Criteria

- Cartesia synthesis calls receive `speed=1.0` for Indonesian, Chinese, and English/default targets.
- Backend logs show `backendPcmSpeed=1.3` for Indonesian target output, `backendPcmSpeed=1.1` for Chinese target output, and `backendPcmSpeed=1.0` for English/default output.
- A 1000 ms, 24 kHz PCM chunk forwards as about 769 ms for Indonesian and about 909 ms for Chinese.
- Forwarded duration logging uses the accelerated PCM duration, not the raw Cartesia duration.
- Focused `TtsPcmSpeedServiceTest,RealtimeInterpretationOrderTest,TtsTextNormalizerTest` and full backend `mvn test` pass.

### Residual Issues

- This PCM speed-up is deterministic time compression and does not attempt pitch-preserving WSOLA/phase-vocoder processing. If listening quality is not acceptable, evaluate a pitch-preserving audio processor as a follow-up.
- The zh-CN -> id brevity-budget retry remains a later pre-TTS step; this change only makes the post-TTS speed control real and measurable.

## Weekly Optimization Record: 2026-W27 Chinese Segmentation Replay and Backend TTS Safety

### Goal

- Fix zh-CN ASR replay and seam problems found in live logs, especially repeated prefixes and swallowed words around Azure interim/final rewrites.
- Keep the server on the pre-OpenAI-Realtime hotfix line while adding the Chinese segmentation fixes.
- Preserve full TTS audio by removing duration-budget cancellation/truncation behavior from the active path.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| Generic ASR emit ledger | Done | `AzureAsrIntegration` now applies normalized duplicate/overlap suppression to every source language, not only Indonesian guarded output. |
| Final remainder safety | Done | Failed final-boundary alignment no longer cuts from a stale offset; it emits from a safe zero boundary and lets overlap trimming plus the ledger remove repeated text. |
| CJK comparable offsets | Done | CJK source offsets are tracked per code point while Latin/digit tokens still normalize as words, fixing Chinese seam alignment that could swallow `这里面呃`-style text. |
| Chinese forced segmentation guard | Done | zh-CN forced segmentation no longer falls back to unsafe character cuts without punctuation, and short forced segments under the zh minimum are deferred. |
| No read-half TTS truncation | Updated | Removed the duration-budget warning path; TTS audio is forwarded completely and `tts-audio-duration` logs `truncated=false`. |
| PCM odd-byte carry | Done | `TtsPcmSpeedService` carries an incomplete PCM byte into the next chunk and drops only a final orphan byte on stream finish. |

### Affected Modules

- Backend ASR integration: `AzureAsrIntegration`.
- Backend realtime TTS pipeline: `RealtimeInterpretationFacade`.
- Backend PCM speed service: `TtsPcmSpeedService`.
- Backend regression tests: `AzureAsrFinalRemainderTest`, `AzureAsrLedgerDedupTest`, `RealtimeInterpretationOrderTest`, `TtsPcmSpeedServiceTest`.

### Acceptance Criteria

- Replayed zh-CN prefixes are logged as `[AsrLedger] duplicate suppressed` or `[AsrLedger] overlap trimmed` before translation/TTS.
- Chinese final seam tests keep the missing span instead of swallowing words.
- zh-CN forced segmentation cuts only on safe punctuation/comma boundaries or waits for a better boundary.
- TTS completion logs `tts-audio-duration ... truncated=false` and no active duration-budget cancel or `overBudget` decision remains.
- Full backend `mvn test` passes on the deployment branch.

### Residual Issues

- If Azure or the punctuation model does not provide a safe Chinese boundary for a long span, the backend now waits instead of character-cutting. This trades some latency for correctness.
- A later zh-CN -> id brevity retry can reduce long Indonesian output before synthesis, but it must not reintroduce audio truncation or post-synthesis dropping.

## Weekly Optimization Record: 2026-W28 zh-CN -> id Compression and VoiceMeeter Frontend Routing Deployment

### Goal

- Make zh-CN -> id live output shorter before TTS by lowering the compression trigger threshold and using a less conservative Indonesian compression prompt.
- Keep TTS timing predictable by using backend PCM acceleration only, with Indonesian target output set to `1.1` and Chinese target output locked at natural `1.0`.
- Prevent very stale zh-CN -> id Indonesian audio from blocking the live queue when it has already synthesized but has not started playback after 40 seconds.
- Restore the host frontend audio output path from the temporary VB-CABLE mapping back to the earlier VoiceMeeter routing scheme.
- Deploy the current tested version to the Aliyun server for live validation without disturbing unrelated backend translation, ASR, TTS voice, or share-page behavior.

### Optimization Items

| Item | Status | Notes |
|---|---|---|
| zh-CN -> id compression threshold | Done and deployed | `OPENAI_COMPRESSION_MIN_TEXT_LENGTH` and backend default changed to `40`, so source text with 40+ Chinese characters can enter Indonesian compression. |
| Indonesian compression prompt | Done and deployed | Replaced the previous "only delete filler" style prompt with a real-time Indonesian interpretation editor prompt that may safely merge repetition and rewrite awkward literal Indonesian while preserving facts, numbers, entities, decisions, and causal relations. |
| Indonesian backend PCM speed | Done and deployed | `TTS_BACKEND_SPEED_ID` is now `1.1`; Cartesia synthesis speed remains neutral. |
| Chinese TTS natural speed and literal text | Done | `TTS_BACKEND_SPEED_ZH` is locked at `1.0`, and the TTS path now synthesizes the same translated text persisted for records and shown on the shared page instead of a separate normalized text variant. |
| Indonesian synthesized unread skip | Done and deployed | Added `CARTESIA_TTS_SYNTHESIZED_ID_SKIP_WAIT_MS` / `cartesia.tts.synthesized-indonesian-skip-wait-ms`, default `80000`. Only Indonesian target TTS that has already synthesized and then waits behind earlier audio longer than the threshold is skipped. |
| Compression safety coverage | Done | Tests cover prompt content, default threshold, the inclusive 40-character boundary, terminology protection around compression, and over-compression fallback. |
| Frontend audio output routing | Done and deployed | Host TTS playback now uses `VoiceMeeterOutput` and `VOICEMEETER` constants: zh -> `VoiceMeeter Input`, id -> `VoiceMeeter Aux Input`, en -> `VoiceMeeter VAIO3`. Runtime source no longer references `VB-CABLE`, `TTS_OUTPUT_CABLE`, or `CABLE-A`. |
| Server deployment | Done | Backend compression/speed deployment commit `8b381d4`; frontend VoiceMeeter deployment commit `9dae16c`; synthesized Indonesian unread skip deployment commit `031abdf`; synthesized skip wait raised to 80000 ms in backend deployment commit `a4abfa3`; server branch `codex/zh-id-compression-prompt-threshold-40`; frontend assets synced to `/var/www/si`. |

### Implementation Results

- Backend compression and speed changes were committed in `8b381d4` and deployed to `8.215.98.126`.
- Server environment now has `OPENAI_COMPRESSION_MIN_TEXT_LENGTH=40`.
- Backend synthesized Indonesian unread skip was committed in `031abdf` and deployed to `8.215.98.126`; the unread wait was later raised from 40000 ms to 80000 ms in commit `a4abfa3`, rebuilt as `si-backend:a4abfa3`, and the running container confirms `CARTESIA_TTS_SYNTHESIZED_ID_SKIP_WAIT_MS=80000`.
- Backend Chinese TTS follow-up removes the separate `TtsTextNormalizer` path, keeps `tts-audio-duration` on PCM metrics only, and requires Chinese target output to stay at `backendPcmSpeed=1.0`.
- Frontend VoiceMeeter routing was committed in `9dae16c`, pushed to GitHub, built on the server, synced into `/var/www/si`, and nginx was reloaded.
- Public frontend now references `/assets/index-BEAeQIh2.js`, whose bundle contains `VoiceMeeterOutput`.
- `si-backend` remained healthy after the frontend deployment; no backend container restart was required for the frontend-only change.

### Affected Modules

- Backend integration/config: `LlmIntegration`, `OpenAiProperties`, `application.yml`, env templates.
- Backend TTS speed, literal shared-page TTS text, and stale Indonesian playback queue control: `Constants`, `CartesiaProperties`, `RealtimeInterpretationFacade`, `TtsPcmSpeedService`, `PcmAudioMetrics`, `TtsPcmSpeedServiceTest`, `PcmAudioMetricsTest`, `RealtimeInterpretationOrderTest`, `CartesiaPropertiesTest`.
- Backend translation compression coverage: `LlmRequestOptionsTest`, `TranslationTerminologyProtectionTest`.
- Frontend host playback: `si-frontend/src/api/constants.ts`, `si-frontend/src/lib/voiceMeeterOutput.ts`, `si-frontend/src/views/InterpretationView.tsx`.
- Deployment: `/opt/syncLingo`, `/var/www/si`, nginx reload.

### Acceptance Criteria

- zh-CN -> id source text below 40 characters does not call Indonesian compression; exactly 40 characters does call compression.
- Indonesian compression prompt allows safe concise rewrite while preserving factual/business meaning and protected terminology.
- TTS logs for Indonesian target output show `backendPcmSpeed=1.1`; Chinese target output is always `1.0`; English/default remains `1.0`.
- Chinese target TTS synthesizes the exact persisted/shared translated text and the runtime has no separate `ttsTextLen`, `ttsTextChanged`, or `TTS text normalized` path.
- If Indonesian target TTS has already synthesized but is still waiting behind earlier audio for more than `CARTESIA_TTS_SYNTHESIZED_ID_SKIP_WAIT_MS` (default 80000 ms), backend logs `TTS synthesized skipped` and releases that queue slot without sending the stale audio. Non-Indonesian target TTS is not affected by this threshold.
- Frontend static bundle contains `VoiceMeeterOutput` and VoiceMeeter device labels, and no longer contains VB-CABLE device labels.
- Public health and frontend asset checks pass after deployment.

### Residual Issues

- Real VoiceMeeter audio routing still requires manual live validation on a host with VoiceMeeter installed, browser audio-device permission granted, and the expected `VoiceMeeter Input` / `VoiceMeeter Aux Input` / `VoiceMeeter VAIO3` devices visible to Chrome or Edge.
- `npm ci` on the server still reports existing npm audit warnings. They did not block this deployment and were not introduced by the VoiceMeeter routing change.
- The production Indonesian compression model is currently configured by server env as `google/gemini-2.5-flash-lite`; prompt behavior should be judged in a live zh-CN -> id meeting sample before deciding whether to tighten or loosen the prompt further.
