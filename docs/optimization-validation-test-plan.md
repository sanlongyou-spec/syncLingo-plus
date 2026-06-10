# optimization-implementation-plan 验证测试计划

本文用于验证 `docs/optimization-implementation-plan.md` 中的优化项。验证原则：

1. 优先通过后端日志、前端控制台日志、接口返回、数据库记录判断。
2. 只有浏览器授权、真实 ASR 效果、TTS 听感、VoiceMeeter 声道这类无法完全由日志证明的项目，才进入人工判断。
3. 每轮验证必须记录 `sessionId`、测试时间、执行人、日志截取时间范围。
4. 日志中不得出现完整敏感原文；检查链路时优先看长度、语言、方向、任务 ID、状态。

## 0. 通用准备

### 0.1 编译门禁

```powershell
cd d:\data\syncLingo-plus\si-frontend
npm.cmd exec tsc -- --noEmit

cd d:\data\syncLingo-plus\si-backend
mvn -q -DskipTests compile
```

通过标准：

- 前端 TypeScript 编译通过。
- 后端 Maven 编译通过。

### 0.2 启动与健康检查

```powershell
cd d:\data\syncLingo-plus
cmd /c start-all.bat
Invoke-RestMethod http://localhost:8080/api/health
Invoke-RestMethod http://localhost:8080/actuator/health
```

日志分析：

```powershell
docker logs --since 5m si-backend |
  Select-String -Pattern "Started SiBackendApplication|ERROR|Application run failed|BadSqlGrammarException"
```

通过标准：

- `/api/health` 返回 `UP` 或成功响应。
- `/actuator/health` 返回 `UP`。
- 启动日志无 `ERROR`、`Application run failed`、`BadSqlGrammarException`。

### 0.3 配置确认

```powershell
docker exec si-backend printenv `
  AZURE_ASR_LANGUAGES `
  AZURE_ASR_END_SILENCE_TIMEOUT_MS `
  AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS `
  AZURE_ASR_MAX_SEGMENT_CHARS `
  OPENAI_API_KEY `
  OPENAI_BASE_URL `
  OPENAI_COMPRESSION_ENABLED `
  OPENAI_COMPRESSION_MIN_TEXT_LENGTH `
  OPENAI_COMPRESSION_MODEL `
  OPENAI_SUMMARY_MODEL `
  CARTESIA_DEFAULT_VOICE_ID_EN
```

通过标准：

- `AZURE_ASR_LANGUAGES` 包含 `zh-CN,id-ID,en-US`。
- `AZURE_ASR_END_SILENCE_TIMEOUT_MS` 建议为 `1500`。
- `AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS` 建议为 `2000`。
- `AZURE_ASR_MAX_SEGMENT_CHARS` 建议为 `150`。
- `OPENAI_API_KEY` 已配置。
- 使用官方 OpenAI API key 时，`OPENAI_BASE_URL` 为 `https://api.openai.com/v1`。
- 使用 OpenRouter key 时，`OPENAI_BASE_URL` 为 `https://openrouter.ai/api/v1`。
- OpenRouter 下 `OPENAI_COMPRESSION_MODEL` 为 `openai/gpt-5-nano` 或当前预期模型。
- OpenRouter 下 `OPENAI_SUMMARY_MODEL` 为 `openai/gpt-5-mini` 或当前预期模型。
- `CARTESIA_DEFAULT_VOICE_ID_EN` 有值，允许为 `default`。

## 1. 阶段 1：稳定性与延迟优化

### 1.1 TTS 30 秒超时保护

场景：

1. 启动一次同传。
2. 让 TTS 正常生成一段音频。
3. 如有条件，可临时使用错误 Cartesia key 或断网模拟 TTS 不回调。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "TTS queued|TTS first chunk|TTS synth complete|TTS synth error|TTS playback timeout waiting chunk|TTS stream complete|processFinalRecognition|ERROR"
```

通过标准：

- 正常链路出现 `TTS queued -> TTS first chunk -> TTS synth complete -> TTS stream complete`。
- TTS 异常或无回调时，最多约 30 秒后出现 `TTS playback timeout waiting chunk`，且后续新识别结果仍能进入 `processFinalRecognition` 和 `TTS queued`。
- 不出现线程永久卡死、后续 session 无响应。

人工判断：

- 仅当日志显示链路完整但听感仍疑似丢句时，人工记录丢句时间点并回查 `ttsTaskId`。

### 1.2 健康检查

见 `0.2`。本项完全通过接口和日志判断，不需要人工判断。

### 1.3 链路耗时日志

执行一次真实同传后分析日志：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "recognized|processFinalRecognition|translate done|compress start|compress end|TTS first chunk|TTS synth complete|sent tts_audio|costMs|textLen|resultLen|translatedLen"
```

通过标准：

- 单次识别能串起 `recognized -> processFinalRecognition -> translate done -> TTS first chunk -> sent tts_audio`。
- 日志包含 `sessionId`、语言方向、文本长度、耗时。
- 日志不打印完整识别文本或完整译文。

### 1.4 WebSocket 断连清理

场景：

1. 开始同传。
2. 浏览器直接刷新或关闭标签页。
3. 等待 5 秒后重新开始同传。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "connection closed|cleanupSession|stopRecognition|stopSession|session closed|startInterpretation done|TTS queued|ERROR"
```

通过标准：

- 关闭或刷新后出现 `connection closed` 和 `cleanupSession done`。
- 重新开始后出现新的 `startInterpretation done`。
- 新 session 不被旧 TTS 队列阻塞。

人工判断：

- 刷新页面后浏览器不再继续播放旧音频。

### 1.5 ASR 静音参数优化

日志分析：

```powershell
docker logs --since 5m si-backend |
  Select-String -Pattern "ASR silence config"
```

通过标准：

- 日志包含 `endSilenceMs=1500` 或当前配置值。
- 日志包含 `segmentationSilenceMs=2000` 或当前配置值。

人工判断：

- 短句停顿后 final 明显更快。
- 没有大量异常截断句子。

### 1.6 OpenAI 压缩策略

场景 A：短文本不压缩。

```powershell
Invoke-RestMethod -Method POST http://localhost:8080/api/translate `
  -ContentType "application/json" `
  -Body '{"text":"欢迎来到系统","sourceLang":"zh-CN","targetLang":"id"}'
```

日志分析：

```powershell
docker logs --since 5m si-backend |
  Select-String -Pattern "compress skipped|compress start|OpenAI response done|translate end"
```

通过标准：

- 短文本出现 `compress skipped`，不出现 `OpenAI response done`。

场景 B：长文本触发压缩。

```powershell
Invoke-RestMethod -Method POST http://localhost:8080/api/translate `
  -ContentType "application/json" `
  -Body '{"text":"今天我们需要讨论项目进度、接口稳定性、实时传译延迟、术语库准确性以及会议结束后的纪要生成流程，请大家按照优先级逐项确认。","sourceLang":"zh-CN","targetLang":"id"}'
```

通过标准：

- 日志出现 `compress start`。
- 日志出现 `OpenAI response done`，模型为 `gpt-5-nano` 或配置值。
- 日志出现 `compress end`。
- `zh-CN -> en` 不触发压缩。

### 1.7 跳读防护与 TTS 顺序追踪

场景：

1. 快速连续说 6 到 10 句。
2. 中途切换中文、印尼语、英语。

后端日志：

```powershell
docker logs --since 15m si-backend |
  Select-String -Pattern "lang switch|keep queued TTS|TTS queue backlog high|TTS queued|TTS first chunk|sent tts_audio|TTS stream complete|TTS skipped|TTS playback skipped|TTS chain reset|TTS queue backlog trimmed|TTS playback timeout|ERROR"
```

前端控制台应观察：

```text
[TTS] chunk received, taskId=..., sequence=..., chunkIndex=...
```

通过标准：

- 语言切换时允许出现 `keep queued TTS to avoid skip`。
- 积压时允许出现 `TTS queue backlog high`。
- `sent tts_audio` 必须带 `taskId`、`sequence`、`chunkIndex`。
- 不允许出现 `TTS skipped`、`TTS playback skipped`、`TTS chain reset`、`TTS queue backlog trimmed`。
- 正常网络下前端不应出现 `possible missing chunk` 或 `duplicate or old chunk ignored`。

人工判断：

- 若日志完整但听感仍跳读，记录大致时间点，回查同时间段 `taskId/sequence/chunkIndex`。

### 1.8 ASR 分段长度控制

场景 A：正常短句。

```powershell
docker logs --since 5m si-backend |
  Select-String -Pattern "processFinalRecognition|force-segment"
```

通过标准：

- 正常短句不出现 `force-segment`。
- `processFinalRecognition` 的文本长度不超过当前阈值预期。

场景 B：连续朗读超过 150 字。

```powershell
docker logs --since 5m si-backend |
  Select-String -Pattern "force-segment at|force-segment remainder|processFinalRecognition"
```

通过标准：

- 出现 `force-segment at N chars`。
- 如果 Azure 最终结果有剩余文本，出现 `force-segment remainder`。
- `force-segment` 日志只记录长度，不打印完整文本。

人工判断：

- 前端字幕被切成至少 2 段，不是一整段长文本。

## 2. 阶段 2：英语三语链路

### 2.1 后端语言常量与支持判断

接口验证六个方向：

```powershell
$cases = @(
  @{ text = "欢迎来到同传系统"; sourceLang = "zh-CN"; targetLang = "id" },
  @{ text = "Selamat datang di sistem interpretasi"; sourceLang = "id"; targetLang = "zh-CN" },
  @{ text = "欢迎来到同传系统"; sourceLang = "zh-CN"; targetLang = "en" },
  @{ text = "Welcome to the interpretation system"; sourceLang = "en"; targetLang = "zh-CN" },
  @{ text = "Selamat datang di sistem interpretasi"; sourceLang = "id"; targetLang = "en" },
  @{ text = "Welcome to the interpretation system"; sourceLang = "en"; targetLang = "id" }
)

foreach ($case in $cases) {
  Invoke-RestMethod -Method POST http://localhost:8080/api/translate `
    -ContentType "application/json" `
    -Body ($case | ConvertTo-Json -Compress)
}
```

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "TranslationService.*translate end|GoogleTranslateIntegration.*translate end|unsupported source lang|unsupported target lang|TRANSLATE_ERROR"
```

通过标准：

- 六个方向全部返回成功。
- 不出现 `unsupported source lang` 或 `unsupported target lang`。

### 2.2 Azure ASR 英语支持

场景：开始同传，说 2 句英文。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "ASR silence config|recognized|processFinalRecognition|detected=.*en|sourceLang=en"
```

通过标准：

- ASR 配置中包含 `en-US`。
- 英语识别后 `processFinalRecognition` 中 `sourceLang=en`。

人工判断：

- 英文原文字幕内容大体正确。

### 2.3 目标语言策略

自动模式：

- WebSocket start 使用 `targetLang=auto`。
- 中文输入应产生 `id` 和 `en` 两个目标语。
- 印尼语输入应产生 `zh-CN` 和 `en` 两个目标语。
- 英语输入应产生 `zh-CN` 和 `id` 两个目标语。

固定目标语言模式：

- WebSocket start 分别使用 `targetLang=zh-CN`、`targetLang=id`、`targetLang=en`。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "startInterpretation|processFinalRecognition|targetLangs|fixed target equals source|translateAndStreamTts"
```

通过标准：

- 自动模式下 `targetLangs` 为源语言之外的两种语言。
- 固定模式下只出现一个目标语言。
- 如果源语言等于固定目标语言，出现 `fixed target equals source, skip translation`。

### 2.4 英语 TTS

场景：让目标语言包含英语。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "targetLang=en|voiceId|TTS queued|TTS first chunk|recordUsage end|sent tts_audio"
```

通过标准：

- 英语目标语进入 `TTS queued`。
- `recordUsage end` 中有英语 targetLang 对应记录。
- 无英语 voice 配置时，日志明确 fallback 到默认 voice。

人工判断：

- 英语 TTS 实际可听，且路由到预期 VoiceMeeter 英语输出设备。

### 2.5 前端三语显示与 chunk 追踪

前端控制台观察：

```text
[TTS] chunk received, taskId=..., sequence=..., chunkIndex=..., lang=...
```

通过标准：

- 中文、印尼语、英语 badge 显示正常。
- 英语译文不显示印尼语内容。
- 正常情况下没有 `possible missing chunk`。

人工判断：

- 三语字幕布局无明显遮挡、滚动正常。

## 3. 阶段 3：术语库与对话记录

### 3.1 对话记录

场景：完成一次同传，记录 `sessionId`。

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/interpretation/records/{sessionId}
```

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "saveTranslatedRecord start|saveTranslatedRecord end|getSessionRecords end"
```

通过标准：

- 每条 final recognition 对应至少一条保存记录。
- 返回记录包含 `sessionId`、`seq`、`sourceLang`、`targetLang`、`sourceText`、`targetText`。
- `seq` 按会话递增。

### 3.2 术语库

步骤：

1. 新增术语。
2. 使用包含该术语的原文调用翻译。
3. 禁用术语。
4. 再次调用翻译。

接口示例：

```powershell
$term = Invoke-RestMethod -Method POST http://localhost:8080/api/terminology `
  -ContentType "application/json" `
  -Body '{"termZh":"聚龙","termId":"Julong","termEn":"Julong","category":"test","enabled":true}'

Invoke-RestMethod -Method POST http://localhost:8080/api/translate `
  -ContentType "application/json" `
  -Body '{"text":"欢迎来到聚龙同传系统","sourceLang":"zh-CN","targetLang":"en"}'

Invoke-RestMethod -Method PATCH "http://localhost:8080/api/terminology/$($term.data.id)/enabled?enabled=false"
```

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "applyBeforeTranslate|applyAfterTranslate|terminology corrected|updateEnabled"
```

通过标准：

- 启用术语时出现 `applyBeforeTranslate` 和 `applyAfterTranslate`。
- 译文包含目标术语。
- 禁用术语后不再参与修正。
- 日志只记录术语长度或数量，不打印完整敏感文本。

## 4. 阶段 4：字幕、队列控制、会议纪要

### 4.1 三语字幕

优先判断：

- 后端日志证明 recognized、translated、tts_audio 均已下发。
- 前端控制台证明 chunk 连续。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "recognized|translated|sent tts_audio|share|broadcast"
```

人工判断：

- 实时字幕持续刷新。
- 历史记录区按顺序显示。
- 切换语言后旧音频不占用新语言声道。

### 4.2 播放队列积压控制

同 `1.7`，重点检查：

- 允许：`TTS queue backlog high`
- 禁止：`TTS queue backlog trimmed`
- 禁止：静默丢句但日志无错误

人工判断：

- 快速发言 2 分钟后没有明显整句缺失。

### 4.3 会议纪要

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/summary/{sessionId}
```

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "generateSummary start|summarizeMeeting start|OpenAI response done|summarizeMeeting end|generateSummary end|generateSummary empty records|ERROR"
```

通过标准：

- 有记录的 session 返回摘要。
- 日志显示使用 OpenAI summary model。
- 空记录 session 出现 `generateSummary empty records`，返回明确业务错误。

人工判断：

- 摘要内容包含“会议摘要、重点事项、待办事项”。

## 5. 阶段 5：音色、授权、成本、说话人克隆

### 5.1 音色使用记录

场景：产生一次 TTS。

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/interpretation/voice-usage/{sessionId}
```

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "recordUsage start|recordUsage end|voiceId|targetLang|textLen"
```

通过标准：

- 每次 TTS 调用有 `voiceId`、`targetLang`、`textLen`。
- 接口可按 session 查询使用记录。

### 5.2 音色授权

步骤：

1. 禁用一个用户 voice。
2. 使用该 voice 开始同传。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "updateAuthorization|isVoiceUsable|voice disabled or unauthorized|fallback default|TTS queued"
```

通过标准：

- 禁用 voice 不被直接使用。
- 日志出现 fallback default。
- TTS 链路不中断。

### 5.3 成本统计

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/interpretation/status/{sessionId}
```

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "addAsrAudioMs|addTranslateChars|addTtsChars|addLlmTokens"
```

通过标准：

- `asrAudioMs`、`translateChars`、`ttsChars` 随同传增加。
- 触发 OpenAI 压缩或纪要时，`llmInputTokens` 和 `llmOutputTokens` 有累加。

### 5.4 会中说话人自动音色克隆

场景：

1. 至少两个人轮流说话。
2. 每人累计清晰语音不少于 8 秒。
3. 记录 sessionId。

日志分析：

```powershell
docker logs --since 20m si-backend |
  Select-String -Pattern "ConversationTranscriber|speakerId=Guest|speaker audio collected|speaker voice clone ready|speaker voice clone failed|speaker voice resolved"
```

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/interpretation/speaker-voices/{sessionId}
```

通过标准：

- ASR 使用 `ConversationTranscriber`。
- 多人发言时出现不同 `speakerId`。
- speaker voice 状态从 `COLLECTING` 到 `CLONING`，成功后为 `READY`。
- 克隆失败时同传不中断。
- 克隆成功后后续 TTS 出现 `speaker voice resolved`。

人工判断：

- 克隆成功后的声音是否接近对应说话人。

## 6. OpenAI 替换 Qwen 专项验证

### 6.1 配置与依赖

日志和配置检查：

```powershell
docker exec si-backend printenv OPENAI_API_KEY OPENAI_BASE_URL OPENAI_COMPRESSION_MODEL OPENAI_SUMMARY_MODEL
docker logs --since 5m si-backend | Select-String -Pattern "official OpenAI Java client initialized"
```

代码残留检查：

```powershell
rg -n "qwen|Qwen|dashscope|DashScope|DASHSCOPE|DASHSCOPE_API_KEY|dashscope-api-key" si-backend docs -g "!target"
```

通过标准：

- 环境变量只使用 `OPENAI_*`。
- OpenRouter key 场景下，`OPENAI_BASE_URL=https://openrouter.ai/api/v1`。
- 后端首次 LLM 调用时出现 `official OpenAI Java client initialized`。
- 代码中无 Qwen/DashScope 调用残留。

### 6.2 压缩调用

使用长中文触发 `zh-CN -> id` 压缩。

日志分析：

```powershell
docker logs --since 10m si-backend |
  Select-String -Pattern "compress start|OpenAI response done|compress end|qwen|DashScope|DASHSCOPE"
```

通过标准：

- 出现 `compress start`。
- 出现 `OpenAI response done`。
- 出现 `compress end`。
- 不出现 `qwen`、`DashScope`、`DASHSCOPE`。

### 6.3 纪要调用

使用有记录的 session 调用：

```powershell
Invoke-RestMethod http://localhost:8080/api/summary/{sessionId}
```

通过标准：

- 日志出现 `summarizeMeeting start`，模型为 `OPENAI_SUMMARY_MODEL`。
- 日志出现 `OpenAI response done`。
- 不出现 Qwen/DashScope 相关日志。

## 7. 最终验收记录模板

```text
测试日期：
测试人：
代码版本/提交：
后端启动时间：
前端启动时间：
sessionId：

编译门禁：
- 前端 tsc：
- 后端 compile：

日志时间范围：

阶段 1：
- TTS 超时保护：
- 健康检查：
- 链路耗时日志：
- WS 断连清理：
- ASR 静音：
- OpenAI 压缩：
- TTS 顺序追踪：
- ASR 分段：

阶段 2：
- 六方向翻译：
- 英语 ASR：
- 固定/自动目标语言：
- 英语 TTS：
- 前端三语显示：

阶段 3：
- 对话记录：
- 术语库：

阶段 4：
- 字幕：
- 队列积压：
- 会议纪要：

阶段 5：
- 音色使用：
- 音色授权：
- 成本统计：
- 说话人克隆：

OpenAI 替换 Qwen：
- OPENAI_* 配置：
- OpenAI SDK 日志：
- Qwen/DashScope 残留搜索：

人工判断项：
- ASR 准确性：
- TTS 听感：
- VoiceMeeter 声道：
- 字幕 UI：
- 说话人克隆音色：

结论：
- 通过 / 不通过
- 遗留问题：
- 需回归项：
```

## 8. 周度验证记录规则

从后续迭代开始，每周优化完成后，都必须把对应测试方案和验证结果统一追加在本文档中，不再为每一周单独新建测试文档。每周验证记录必须与 `docs/optimization-implementation-plan.md` 中的周度优化记录使用同一周标识。

### 8.1 记录要求

1. 优先通过后端日志、前端控制台日志、接口返回和数据库记录判断。
2. 只有日志和客观记录无法证明的内容，才进入人工判断。
3. 每周至少记录：
   - 对应优化范围；
   - 验证目标；
   - 日志 / 接口 / 数据库验证步骤；
   - 人工判断项；
   - 通过标准；
   - 结果记录与遗留问题。
4. 如果某个优化项本周暂缓或未完成，测试记录中必须同步说明，不得把未实现项写成已通过。

### 8.2 周度追加模板

```markdown
## 周度验证记录：2026-W20

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W20`

### 验证目标

- ...

### 优先通过日志 / 接口 / 数据库验证

```powershell
# commands
```

通过标准：

- ...

### 必要时再做人工判断

- ...

### 结果记录

- 通过 / 不通过：
- 遗留问题：
- 需回归项：
```

## 周度验证记录：2026-W21

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W21`
- 范围：Teams Bot 个人聊天发送会议链接自动入会 PoC。

### 验证目标

- 验证 Bot 能从个人聊天消息中识别完整 Teams 会议链接。
- 验证 Bot 能解析 `ChatInfo` 与 `MeetingInfo` 并调用现有 Graph calling 入会逻辑。
- 验证真实会议中出现 Bot 参会者，并产生 call 生命周期日志。

### 优先通过日志 / 接口 / 数据库验证

```powershell
dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj -o .\.tmp-build\callingbot-link-test
```

运行时验证：

```powershell
# 1. 重启 CallingBotSample，使新代码生效
# 2. 保持 ngrok 在线，并确认 Azure Bot Messaging endpoint / Calling webhook 指向当前 ngrok 地址
# 3. 在 Teams Bot 个人聊天中发送完整 Teams 会议加入链接
# 4. 观察本地 CallingBotSample 日志
```

通过标准：

- 编译成功。
- Bot 个人聊天返回“Joining the Teams meeting now...”提示。
- 本地日志出现 Graph call 创建请求和后续 call lifecycle 事件。
- 如果 Graph 返回错误，记录完整错误 code、request-id、时间和调用场景。

### 必要时再做人工判断

- Teams 会议成员列表中能看到 Bot。
- Bot 退会或会议结束后，本地出现 `call ended` 或等价结束日志。

### 结果记录

- 通过 / 不通过：编译通过；真实会议链接入会已通过。
- 遗留问题：`Create Call` 主动创建通话路径返回 `7504`，不作为当前加入已有会议主线阻塞。
- 需回归项：meeting chat 内 `Join scheduled meeting` 路径仍需在 App 能添加到会议聊天后回归。

补充验证记录：

- Teams 会议成员列表中可看到 `syncLingo Teams Bot`。
- 本地日志多次收到 `/callback` 请求。
- 本地日志显示 Teams 请求 `/audio/please-record-your-message.wav`。
- 本地日志显示 `TeamsRecordingService` 下载 recorded stream。
- `Failure converting speech to text. Cognitive services is not enabled.` 属于官方样例录音转文字配置缺失，不影响入会验证结论。

日志增强回归：

```powershell
dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj -o .\.tmp-build\callingbot-lifecycle-log-test
```

通过标准：

- 编译成功。
- 重启 Bot 并重新入会后，应能在本地控制台看到：
  - `Teams call notification received`
  - `Teams call established`
  - `Teams participants notification received`
  - 必要时出现 `Teams record operation notification received`

## 周度验证记录：2026-W21 Teams 用户摘要私聊发送改造

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W21 Teams 用户摘要私聊发送改造`

### 验证目标

- 验证摘要发送链路不再依赖会议聊天。
- 验证前端会把默认 Teams 用户作为 `recipients` 传给 C# Bot。
- 验证 C# Bot 能解析 Teams 用户并尝试通过 personal chat 发送摘要。

### 优先通过日志 / 接口 / 数据库验证

```powershell
rg -n "sendToMeetingChat|sendToParticipants|TryInstallAppInMeetingChatAsync|SendMessageToChatAsync|InstallApp\\(" `
  si-frontend/src external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample

dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj `
  -o .\.tmp-build\callingbot-teams-user-send-test

cd si-frontend
npm.cmd run build
```

接口验证：

```powershell
Invoke-RestMethod http://localhost:3978/api/meetings/summary `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"content":"test summary","recipients":["someone@example.com"]}'
```

通过标准：
- 残留搜索不再命中旧会议聊天发送路径。
- C# Bot 构建通过。
- 前端构建通过。
- Bot 日志出现 `[MeetingSummaryService] Send summary to Teams users start/end`。
- Bot 日志出现 `[ChatService] Send message to Teams user start/end`，或返回明确的用户解析 / App 安装权限错误。

### 必要时再做人工判断

- 在 Teams 客户端确认目标用户收到 Bot 私聊摘要。
- 如果没有收到，优先检查 `Bot:CatalogAppId` 和 Azure AD `TeamsAppInstallation.ReadWriteSelfForUser.All` 应用权限。

### 结果记录

- 通过 / 不通过：代码级验证通过；真实 Teams 用户端到端收信待验证。
- 已执行：旧会议聊天发送路径残留搜索通过；C# Bot `dotnet build` 通过；前端 `npm.cmd run build` 通过。
- 遗留问题：需要完成用户 personal scope 自动安装配置后验证实际收信。
- 需回归项：同传页结束后的一键推送、Teams 用户摘要页面手工发送。

## 周度验证记录：2026-W21 Teams Bot 历史会议查询

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W21 Teams Bot 历史会议查询`

### 验证目标

- 验证 Teams Bot 可以把个人聊天消息转发给 Java 后端查询接口。
- 验证 Java 后端按 Teams 用户身份映射 syncLingo 用户。
- 验证用户可以查询最近会议、关键词搜索和单场会议摘要。

### 优先通过日志 / 接口 / 数据库验证

```powershell
mvn.cmd -q -DskipTests package

dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj `
  -o .\.tmp-build\callingbot-history-query-test
```

接口验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/teams-bot/query `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"mail":"user@example.com","userPrincipalName":"user@example.com","message":"最近"}'
```

如果配置了 `TEAMS_BOT_API_SECRET`，接口验证需补充：

```powershell
Invoke-RestMethod http://localhost:8080/api/teams-bot/query `
  -Method Post `
  -Headers @{"X-SyncLingo-Bot-Secret"="your-secret"} `
  -ContentType "application/json" `
  -Body '{"mail":"user@example.com","userPrincipalName":"user@example.com","message":"最近"}'
```

通过标准：

- Java 编译成功。
- C# Bot 编译成功。
- Java 日志出现 `[TeamsBotQueryController] query start/end`、`[TeamsBotQueryService] query start/end`。
- C# 日志出现 `[MessageBot] Message received` 和 `[SyncLingoBotQueryService] Query start/end`。
- 未匹配用户返回绑定提示。
- 配置共享密钥后，缺少或错误的 `X-SyncLingo-Bot-Secret` 返回未授权。
- 匹配用户返回的会议记录只属于该 syncLingo 用户。

### 必要时再做人工判断

- 在 Teams 个人聊天中发送 `最近`，确认 Bot 回复最近会议列表。
- 发送 `搜索 关键词`，确认 Bot 回复匹配会议。
- 发送 `摘要 <sessionId>`，确认 Bot 回复对应会议摘要；对不属于该用户的 `sessionId` 返回无权限/未找到提示。

### 结果记录

- 通过 / 不通过：代码级验证通过；真实 Teams 个人聊天端到端查询待部署后验证。
- 已执行：Java 后端 `mvn.cmd -q -DskipTests package` 通过；C# Bot `dotnet build` 通过。
- 遗留问题：需要在真实租户中确认 `TeamsInfo.GetMemberAsync` 返回的 mail / UPN 与 `si_user.email` 映射一致。
- 需回归项：Teams 用户摘要私聊发送、同传页一键推送摘要、Bot 入会 PoC。

## 周度验证记录：2026-W21 Teams Bot 方案 B 自动安装固化

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W21 Teams Bot 方案 B 自动安装固化`

### 验证目标

- 验证 Teams 用户私聊发送不再使用 `CreateConversationAsync` fallback。
- 验证缺少 `CatalogAppId` 时返回明确错误。
- 验证配置 `CatalogAppId` 与 Graph 权限后，Bot 可以自动安装到用户 personal scope 并发送摘要。

### 优先通过日志 / 接口 / 数据库验证

```powershell
rg -n "CreateConversationAsync|CreateConversationWithUserAsync|CatalogAppId not configured|InstallApp\\(" `
  external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample/Services/MicrosoftGraph/ChatService.cs

dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj `
  -o .\.tmp-build\callingbot-plan-b-auto-install-test
```

配置后接口验证：

```powershell
Invoke-RestMethod http://localhost:3978/api/meetings/summary `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"content":"方案B自动安装测试","recipients":["someone@example.com"]}'
```

通过标准：

- 残留搜索不再命中 `CreateConversationAsync` fallback。
- C# Bot 构建通过。
- 未配置 `CatalogAppId` 时，响应错误包含 `Bot:CatalogAppId is required`。
- 配置正确后，Bot 日志出现 `[ChatService] Installing bot app for user` 或 `App already installed for user`。
- 目标 Teams 用户收到 Bot 私聊摘要。

### 必要时再做人工判断

- Teams 管理中心确认 `syncLingo-teams-app.zip` 已上传并允许组织使用。
- Azure AD 确认 `User.Read.All`、`TeamsAppInstallation.ReadWriteSelfForUser.All` 均已授予管理员同意。
- Graph 查询确认 `Bot:CatalogAppId` 使用的是 app catalog ID，不是 Azure AD Client ID。

### 结果记录

- 通过 / 不通过：代码级验证通过；Azure / Teams 管理后台配置已完成；真实 Teams 用户端到端收信待验证。
- 已执行：旧 fallback 关键字 `CreateConversationAsync` / `CreateConversationWithUserAsync` / `InstallApp(` 在 `ChatService` 中无残留；C# Bot `dotnet build` 通过（14 个既有 sample warning，0 error）；`Bot:CatalogAppId` 已回填为 Teams 管理中心 App ID 并重启 Bot。
- 遗留问题：需要真实 Teams 用户执行摘要私聊发送，确认自动安装和收信成功。
- 需回归项：Teams 用户摘要私聊发送、Teams Bot 历史会议查询。

## 周度验证记录：2026-W21 Teams Bot 主动消息二次修复

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W21 Teams Bot 主动消息二次修复`

### 验证目标

- 验证本次真实日志中的会议聊天错误不是 CatalogAppId / 权限问题，而是缺少当前进程可用的 `ConversationReference`。
- 验证个人私聊路径中 Graph 安装与 `conversationUpdate` 已成功，失败点在 Bot Framework 发送活动。
- 验证修复后 C# Bot 能成功编译并重新启动。

### 优先通过日志 / 接口 / 数据库验证

```powershell
$log = Join-Path $env:TEMP 'bot.log'
Select-String -Path $log -Pattern "EnsureBotInMeetingChat|SendToMeetingChat|SendMessageToUser|ConversationReference|SendActivityAsync failed|BadRequest" -Context 2,4

dotnet build external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample\CallingBotSample.csproj `
  -o .\.tmp-build\callingbot-proactive-fix-test
```

Graph 验证 meeting chat 安装状态：

```powershell
# 使用 appsettings.json 中的 AzureAd ClientId / ClientSecret 换取 app-only token 后：
GET https://graph.microsoft.com/v1.0/chats/{encoded-thread-id}/installedApps?$expand=teamsAppDefinition
POST https://graph.microsoft.com/v1.0/chats/{encoded-thread-id}/installedApps
```

通过标准：

- Graph `GET /installedApps` 返回 `meeting bot`，`teamsAppId` 为 `99867208-21ac-40ec-b946-3fe2f9659006`。
- Graph `POST /installedApps` 对已安装 meeting chat 返回 409 `AppEntitlementAlreadyExists`，证明安装状态存在。
- C# Bot build 通过。
- Bot 重启后监听 `http://0.0.0.0:3978`。
- 后续真实发送时，日志出现 `TextFormatTypes.Markdown` 路径对应的 `Message sent via adapter`，且 Teams 客户端收到消息。

### 必要时再做人工判断

- 在 Teams Bot 页面重新加入会议。
- 点击"发送到会议聊天"，确认 Teams 会议聊天中出现消息。
- 点击"发送给参会人员"，确认目标用户收到 personal chat 消息。
- 如果 personal chat 仍失败，让该用户先在 Teams 中给 `meeting bot` 发送 `最近`，再重试发送。

### 结果记录

- 通过 / 不通过：代码级验证通过；真实 Teams 客户端收信待用户回归。
- 已执行：读取 `%TEMP%\bot.log` 定位两条失败路径；Graph 直接验证 meeting chat 已安装 `meeting bot`；C# Bot `dotnet build` 通过；backend、frontend、Bot、ngrok 已重新拉起。
- 遗留问题：`start-all.bat` 本次在 Docker Desktop build export 阶段遇到 snapshot/cache 错误，需要清理 Docker build cache 或重启 Docker Desktop 后回归脚本全流程。
- 需回归项：会议聊天摘要发送、参会人员 personal chat 摘要发送、Teams Bot 历史会议查询。

## 周度验证记录：2026-W24 分享页 1.35x 加速端到端延迟分组统计

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W24 分享页 1.35x 加速端到端延迟分组统计`

### 验证目标

- 确认分享页播放积压达到 4 秒后实际进入 `1.35x`。
- 测量 `1.35x` 样本中“开始说话到听众开始播放对应 TTS”的端到端延迟。
- 对比 `1.00x`、加速中和 `1.35x` 三组延迟。

### 优先通过日志 / 接口 / 数据库验证

在分享页 `/share/user/<userId>` 选择收听语言，连续讲话制造播放积压。测试结束后在服务器执行：

```bash
docker logs --since 30m si-backend 2>&1 \
  | grep -a "e2e client latency" \
  | grep -a "sessionId=<本次sessionId>" \
  | python3 /opt/syncLingo/tests/analyze_latency.py
```

快速确认是否达到最高速：

```bash
docker logs --since 30m si-backend 2>&1 \
  | grep -a "e2e client latency" \
  | grep -a "sessionId=<本次sessionId>" \
  | grep -a "playbackRateMilli=1350"
```

通过标准：

- 日志至少出现一个 `playbackRateMilli=1350`。
- 分析结果包含 `1.35x` 分组，且样本数大于 0。
- `1.35x` 分组输出端到端均值、中位数、p90 与播放积压。

### 必要时再做人工判断

- 使用 Chrome 或 Edge 收听分享页，确认 `1.35x` 时内容仍可理解。
- 确认加速期间没有跳句、缺字、明显爆音或长时间静音。

### 结果记录

- 通过 / 不通过：代码级验证通过；待部署后进行真实连续语音测试。
- 已执行：前端 `npm.cmd run build` 通过；后端 `mvn.cmd clean test` 通过（78 项测试）；延迟分析脚本语法检查及模拟 `1.00x / 加速中 / 1.35x` 分组验证通过。
- 遗留问题：动态加速改变音调；需要结合真实印尼语与英语 TTS 判断可接受程度。
- 需回归项：分享页自动重连、切换语言、关闭声音后重新收听。

## 周度验证记录：2026-W24 TTS 严格句序播放

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W24 TTS 严格句序播放`。

### 验证目标

- 验证后一句先完成翻译和 TTS 合成时，仍不能先于前一句播放。
- 验证翻译失败、会话停止或播放异常不会永久阻塞后续句子。
- 验证不同目标语言仍保持独立播放链。

### 优先通过自动化测试验证

```powershell
cd D:\data\syncLingo-++\si-backend
mvn.cmd clean test
```

重点测试：

- `RealtimeInterpretationOrderTest.laterTranslationCannotPlayBeforeEarlierSentence`
- 测试主动阻塞第一句翻译，让第二句先完成翻译与 TTS 合成。
- 通过标准：实际播放回调收到的 sequence 必须为 `[1, 2]`。

### 服务器日志验证

```bash
docker logs --since 30m si-backend 2>&1 \
  | grep -a -E "TTS order reserved|TTS order released|TTS queued"
```

通过标准：

- 同一个 `sessionId` 和 `lang` 的预留顺序与 ASR final 句序一致。
- 同一个 `sessionId` 和 `lang` 的释放 sequence 不倒退；不同语言的 sequence 可以交错或跳号。
- 不出现某个任务长期只有 `reserved`、没有 `released`，且会话仍持续活动。

### 必要时再做人工判断

- 连续说出带明确编号的句子，例如“第一句……、第二句……、第三句……”，让第一句内容明显更长、更复杂。
- 分别收听中文、印尼语和英语频道，确认没有后句先读、前句后补。
- 确认前句处理慢时后句只是等待，不出现长时间永久静音。

### 结果记录

- 通过 / 不通过：本地代码级验证通过；服务器真人收听待验证。
- 已执行：后端 `mvn.cmd clean test` 通过（79 项测试）；第二句先完成翻译和合成时，实际播放 sequence 仍为 `[1, 2]`。
- 需回归项：连续快速短句、长短句交替、翻译服务瞬时失败、停止同传后重新开始。
