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
