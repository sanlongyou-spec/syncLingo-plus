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

## 周度验证记录：2026-W24 发言摘要姓名可编辑与乱码防护

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W24 发言摘要姓名可编辑与乱码防护`。

### 验证目标

- 验证汇报人姓名和摘要正文可持久化，且修改后同步刷新向量索引。
- 验证常见 UTF-8 错解乱码、替换字符和拒答内容会被判为不可用摘要。
- 验证读取已有乱码摘要时会基于原始发言文本自动刷新。

### 优先通过日志 / 接口 / 数据库验证

```powershell
cd D:\data\syncLingo-++\si-backend
mvn.cmd test

cd D:\data\syncLingo-++\si-frontend
npm.cmd run build

git diff --check
```

重点自动化测试：

- `SpeakerSummaryServiceTest.detectsCommonUtf8Mojibake`
- `SpeakerSummaryServiceTest.persistsManualSpeakerNameAndSummaryCorrection`
- `SpeakerSummaryServiceTest.refreshesPersistedMojibakeFromOriginalTranscript`

接口回归：

```http
PUT /api/meetings/speaker-summaries/{id}
Content-Type: application/json

{
  "speakerName": "修正后的姓名",
  "summary": "修正后的摘要正文"
}
```

通过标准：

- 后端日志出现 `SpeakerSummaryService update start/end`，Mapper 更新姓名和摘要正文。
- 乱码历史摘要读取时出现 `persisted speaker summary is unusable, regenerating`。
- 更新、重新生成或自动刷新后调用 `asyncEmbedSpeakerSummary`。
- 后端完整测试、前端生产构建和 `git diff --check` 通过。

### 必要时再做人工判断

- 在历史记录“发言摘要”页修改姓名和正文，点击“保存”，刷新页面确认两项修改均保留。
- 确认姓名输入框、保存、重新生成和 Teams 发送按钮在常见桌面宽度下无重叠。
- 直接发送未保存的当前编辑内容到 Teams，确认消息中的姓名和正文使用页面当前值。

### 结果记录

- 通过 / 不通过：代码级验证通过；真实历史数据页面端到端保存待本机数据库恢复后回归。
- 已执行：后端 `mvn.cmd test` 通过（88 项测试）；前端 `npm.cmd run build` 通过；`git diff --check` 通过；本地前端可正常打开登录页。
- 遗留问题：本机后端启动时 MySQL 连接被重置，无法进入真实历史摘要卡片完成保存点击验证。
- 需回归项：姓名/正文保存后刷新、已有乱码摘要自动刷新、修改后跨会议检索姓名、Teams 发送当前编辑内容。

## 周度验证记录：2026-W25 用户管理与权限管理方案

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W25 用户管理与权限管理方案`。

### 验证目标

- 证明认证、功能权限、数据范围和非人员身份边界均按统一策略执行。
- 证明修改前端 `userId`、资源编号、WebSocket `sessionId` 或分享地址不能访问其他用户数据。
- 证明权限改造不会中断操作员正式会议流程、查看者只读流程、分享页收听和 Teams Bot 服务链路。

### 必须建立的自动化测试基线

- 后端新增权限矩阵参数化测试，覆盖 `ADMIN / OPERATOR / VIEWER` 与 `OWN / ASSIGNED / OTHER / ALL`。
- 后端新增 Controller 集成测试，覆盖全部受保护接口的 401、403/404、正常路径和错误资源路径。
- 前端建立组件与路由权限测试，当前前端测试文件为 0，不能只依赖生产构建。
- WebSocket 新增握手、会话绑定、跨会话劫持、分享能力令牌过期与撤销测试。
- Bot 新增 Java 代理权限测试和 C# 服务密钥测试。
- 数据库迁移新增空库升级、现有库升级、角色回填、唯一约束和回滚验证。

### 角色与数据范围验证矩阵

| 场景 | ADMIN | OPERATOR | VIEWER |
|---|---:|---:|---:|
| 查看自己的会议 | 允许 | 允许 | 按分配允许 |
| 查看被分配会议 | 允许 | 允许 | 允许 |
| 查看未分配的他人会议 | 允许并审计 | 拒绝 | 拒绝 |
| 创建会议 | 允许 | 允许 | 拒绝 |
| 修改/删除会议 | 允许并审计 | 仅自有或操作级分配 | 拒绝 |
| 启动/停止同传 | 允许并审计 | 仅自有或操作级分配 | 拒绝 |
| 查看摘要/记录/资料 | 允许 | 自有或被分配 | 被分配只读 |
| 修改摘要/行动项/资料 | 允许 | 自有或操作级分配 | 拒绝 |
| 管理术语/热词 | 允许 | 仅自有 | 拒绝 |
| 发送 Teams 消息或控制 Bot | 允许 | 自有或操作级分配 | 拒绝 |
| 查看本人费用 | 允许 | 允许 | 拒绝 |
| 查看全部费用、日志、审计 | 允许 | 拒绝 | 拒绝 |
| 管理用户、角色、状态 | 允许 | 拒绝 | 拒绝 |

### 认证与账号生命周期测试

- 正确账号密码登录，`/api/auth/me` 返回用户资料、角色和权限。
- 错误密码、禁用账号、锁定账号和待启用账号不能登录。
- 缺失、过期、篡改、错误签名、错误 issuer/audience 的 JWT 返回 401。
- 角色变更、账号禁用、密码重置和撤销会话后，旧 tokenVersion 令牌立即失效。
- 不能停用或降级最后一个有效管理员。
- 公开注册关闭；如启用邀请流程，新账号不能直接获得操作员权限。
- 登录、失败登录、角色变更、禁用、解禁、重置密码和撤销会话均产生脱敏审计记录。

### 接口与 IDOR 越权测试

- 对所有原先接收 `userId` 的接口，将参数修改为其他用户编号，确认服务端忽略或拒绝该参数。
- 对会议、会话、文件、摘要、发言摘要、行动项、音频记录、术语和热词逐一替换资源编号，确认无权访问。
- 验证 `meetingId` 与 `fileId` 不匹配时不能读取、更新、重新加载或下载文件。
- 验证 `sessionId` 与会议归属不匹配时不能读取记录、摘要、说话人映射、行动项或停止会话。
- 验证无权限用户无法调用系统人员目录写接口、管理任务、日志下载、全部费用和 Bot 操作。
- 验证无权用户不能通过“资源不存在”和“资源无权访问”的响应差异枚举敏感资源。

### WebSocket、分享页与 Bot 测试

- ASR WebSocket 缺少、过期或篡改 JWT 时握手失败，日志不记录完整 token 或查询字符串。
- 已认证用户向 start 消息写入他人 `sessionId` 时拒绝；连接绑定后向 audio/stop 写入其他 `sessionId` 时拒绝并关闭连接。
- VIEWER 即使持有有效 JWT 也不能启动或停止同传。
- 分享能力令牌只允许连接一个会话；过期、撤销、篡改或用于其他会话时失败。
- 删除 `/public/user/{userId}/active` 后，不能通过连续用户编号发现活动会话。
- Java `/bot-api/**` 对 viewer 和未登录请求拒绝；operator 仅能操作有权限会议。
- Java 到 C# Bot 缺少或错误服务密钥时 C# 返回拒绝；正确密钥下加入会议、发送通知、发送摘要和查询参与者正常。
- Bot Framework `/api/messages` 平台回调继续正常，不受业务代理权限错误影响。

### 前端浏览器验证

- ADMIN 登录后可见用户管理、角色状态、审计和全局管理入口；操作员和查看者不可见。
- OPERATOR 可完成会议创建、资料准备、启动同传、生成摘要和发送 Teams 的完整流程。
- VIEWER 只能进入被分配会议的只读页面；所有编辑、删除、启动、重生成和发送按钮不可见或禁用。
- 手工修改 localStorage 中的 `userId`、角色或权限不能提升实际后端权限。
- 401 自动清理登录状态并跳转登录页；403 展示明确无权限状态，不误显示为网络错误。
- 在桌面与移动宽度验证登录、用户管理、权限不足、空列表、加载、保存成功和保存失败状态。

### 数据库、配置与运行验证

- 空数据库能按迁移顺序创建用户状态、会议成员、分享令牌和审计表。
- 现有数据库升级后，历史用户、会议和会话数据不丢失，角色回填符合清单。
- `role`、`status`、`token_version` 非法值被约束拒绝；`meeting_member` 不产生重复成员记录。
- 生产环境缺少或使用默认 `DB_PASSWORD / JWT_SECRET / ADMIN_API_SECRET / TEAMS_BOT_API_SECRET` 时启动失败并给出明确错误。
- 审计日志包含 actor、权限、资源、结果、requestId 和时间，不包含密码、JWT、服务密钥或完整敏感正文。
- 报告模式记录本应拒绝的请求但不影响会议；强制模式下相同请求被拒绝。

### 建议执行命令

```powershell
cd D:\data\syncLingo-++\si-backend
mvn.cmd clean test

cd D:\data\syncLingo-++\si-frontend
npm.cmd test -- --run
npm.cmd run build

cd D:\data\syncLingo-++\external\Microsoft-Teams-Samples\samples\bot-calling-meeting\csharp\Source\CallingBotSample
dotnet build CallingBotSample.csproj

cd D:\data\syncLingo-++\speaker-service
python -m compileall -q .
python -m pytest -q

cd D:\data\syncLingo-++
git diff --check
```

### 通过标准

- 权限矩阵、认证生命周期、IDOR、WebSocket、分享能力令牌、Bot 服务身份和前端权限流程测试全部通过。
- 所有受保护接口有显式策略；不存在仅依赖前端隐藏按钮或客户端 userId 的授权路径。
- 正式会议操作员端到端流程、查看者只读流程、公开分享收听和 Teams Bot 流程无回归。
- 日志、数据库和审计记录能证明允许与拒绝行为正确，且无敏感信息泄漏。

### 结果记录

- 通过 / 不通过：方案设计与代码基线盘点完成，尚未进入实施和执行验证阶段。
- 已执行：完成接口、认证、前端、WebSocket、Teams Bot、数据库结构和现有测试覆盖盘点；本次仅修改规划文档。
- 遗留问题：需先确认现有账号角色回填清单和 operator 对被分配会议的修改范围。
- 需回归项：全部权限矩阵、正式会议同传、分享页持续收听、Teams Bot 主动消息、历史数据读取与导出。

## Weekly Validation Record: 2026-W25 AI Q&A Optimization Final Implementation

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W25 AI Q&A Optimization Final Implementation`.
- Detailed plan: `docs/ai-qa-optimization-final-plan-2026-W25.md`.

### Validation Goals

- Prove existing server data is not deleted or overwritten by the optimization.
- Prove old/default and new embedding profiles can coexist.
- Prove Q&A quality changes are measured against a baseline.
- Prove grounded source output, multi-turn context, and streaming completion paths work.

### Log, API, and Database Validation First

```powershell
cd D:\data\syncLingo-++\si-backend
mvn.cmd test

cd D:\data\syncLingo-++
git diff --check
```

Database checks before and after any server-side migration or profile rebuild:

```sql
SELECT COUNT(*) FROM interpretation_embedding;
SELECT embedding_profile, embedding_model, embedding_dim, index_status, COUNT(*)
FROM interpretation_embedding
GROUP BY embedding_profile, embedding_model, embedding_dim, index_status;
```

Pass criteria:

- Row count must not decrease during optimization except for explicit business deletion tests.
- New profile rows can be added without deleting default/legacy rows.
- Retrieval logs include candidate count, profile, hybrid status, and rerank status.
- No logs include full sensitive transcript text.

### Automated Behavior Validation

- Profile normalization and content hash tests pass.
- Profile-aware vector search reads the requested profile and default/legacy fallback.
- Current profile writes metadata on new embeddings.
- Teams Bot bounded history and grounded source context tests pass.
- Q&A evaluation scoring tests pass for pass/fail and mismatched-answer-count paths.
- Rebuild logic creates missing profile rows without overwriting older profile rows.
- RAG helper failures fail open.
- Streaming Q&A emits answer chunks and a final source block.

### Manual Validation After Automation

- Copy `docs/examples/qa-evaluation-run-request.example.json`, replace the identity and expected keywords with real server data, then call `POST /api/admin/qa-evaluation/run` with `X-Admin-Secret`.
- Preferred local command: `powershell -ExecutionPolicy Bypass -File scripts\run-qa-evaluation.ps1 -BaseUrl http://localhost:8080 -AdminSecret $env:ADMIN_API_SECRET -RequestFile docs\examples\qa-evaluation-run-request.example.json`.
- Ask real cross-meeting questions against historical server data.
- Ask follow-up questions with pronouns and omitted subjects.
- Confirm source meeting, speaker, time, and file/chunk references are correct.
- Confirm old data remains searchable after enabling the optimized profile.
- Confirm perceived first-token latency and total response time are acceptable.

### Result Record

- Pass / Fail: Automated backend and Bot build checks passed; production data validation remains manual.
- Automated tests executed:
  - `powershell -ExecutionPolicy Bypass -File scripts\run-qa-evaluation.ps1 -AdminSecret dummy -DryRun` passed.
  - `mvn -q "-Dtest=QaEvaluationFacadeTest,QaEvaluationServiceTest" test` passed.
  - `mvn -q -DskipTests compile` passed.
  - `mvn -q "-Dtest=HybridRetrievalTest,AgenticDecisionParseTest,QaEvaluationFacadeTest,QaEvaluationServiceTest,TeamsBotQueryHistoryTest" test` passed.
  - `mvn -q "-Dtest=QaEvaluationFacadeTest,QaEvaluationServiceTest,TeamsBotQueryHistoryTest,EmbeddingProfileMetadataTest,VectorSearchProfileTest" test` passed.
  - `mvn -q test` passed.
  - `dotnet build CallingBotSample.csproj` passed with existing nullable/deprecation warnings and 0 errors.
  - `git diff --check` passed with line-ending warnings only.
- Residual issues: Production migration requires backup and rehearsal before enabling on the server; final retrieval tuning needs real historical data questions and baseline/post-optimization comparison reports.

## Weekly Validation Record: 2026-W25 Commercial Release Hardening and Linux Deployment Cleanup

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W25 Commercial Release Hardening and Linux Deployment Cleanup`.

### Validation Goals

- Prove active startup/shutdown scripts no longer depend on ngrok.
- Prove current deployment docs describe Linux + Nginx + HTTPS, not a tunnel.
- Prove Teams notification ingress is documented as Java-authorized `/bot-api/**`.
- Prove GitHub-facing files avoid committing secrets and generated artifacts.

### Automated Checks

```powershell
rg -n "ngrok|si-ngrok|auction-uncombed|external/Microsoft-Teams-Samples|summary-file|SharePoint" `
  README.md docs deploy start-all.bat stop-all.bat si-frontend bot si-backend scripts `
  --glob '!**/node_modules/**' --glob '!**/target/**' --glob '!**/dist/**' --glob '!**/bin/**' --glob '!**/obj/**'

cd si-backend
mvn test

cd ..\si-frontend
npm run build

cd ..\bot\CallingBotSample
dotnet build CallingBotSample.csproj

cd ..\CallingBotSample.Tests
dotnet test CallingBotSample.Tests.csproj

cd ..\..\speaker-service
python -m compileall -q .
```

### Manual Validation

- Read `docs/deployment-runbook.md` end to end and confirm no step installs or starts a tunnel.
- Confirm Azure Bot Messaging endpoint is configured to the production domain `/api/messages`.
- Confirm Nginx routes `/bot-api/**` to Java backend and `/api/messages` to C# Bot.
- Confirm the Teams app package has production IDs and domain before upload.
- Confirm server-only `backend.env` and Bot `appsettings.Production.json` are not committed.

### Pass Criteria

- Searches only show old terms inside explicit "deprecated/not used" notes or preserved historical weekly records.
- Full affected module builds/tests pass.
- Deployment can be followed from clean Linux server templates without ngrok.

### Result Record

- Pending execution after this documentation and cleanup change set.

## 周度验证记录：2026-W26 印尼语→中文翻译质量增强 + 分享音量统一

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W26 印尼语→中文翻译质量增强（ASR 后处理 + LLM 纠错翻译）+ 分享音量统一`。

### 验证目标

- 证明 id→zh 走 LLM 纠错翻译、失败可回退 Google。
- 证明印尼语成句与最小句长闸门生效（无碎片）。
- 证明数字归一化、术语（精确/模糊）、专名/称谓锁定、元话语拦截生效。
- 证明分享各语言音量趋于一致。

### 优先通过日志 / 接口 / 数据库验证

后端单测（含本周新增）：

```powershell
cd si-backend
mvn test
# 关键用例：TranslationNumberNormalizationTest / TranslationLlmIdZhTest /
#           LlmIdZhSanitizeTest / TerminologyFuzzyHintTest / OpusLoudnessNormalizationTest / OpusBandwidthTest
```

线上运行日志（开印尼语会议后）：

```bash
# LLM 纠错翻译在跑、且很少回退
docker logs si-backend 2>&1 | grep -E "idZhCorrectTranslate|translate end \(llm id->zh\)|fallback to google"
# 成句：有 wtpsplit 边界，且参数生效（开会创建会话时打印）
docker logs si-backend 2>&1 | grep -E "ASR silence config|force-segment by=|asr-segment final"
# 术语命中（精确+模糊）
docker logs si-backend 2>&1 | grep -E "glossaryLines|terminology .* restored"
# 数字归一化
docker logs si-backend 2>&1 | grep "indonesian number normalized"
# 健康检查
curl -sf http://127.0.0.1:8080/api/health
```

通过标准：

- `mvn test` 全绿（339+，0 失败）。
- 出现 `idZhCorrectTranslate` / `translate end (llm id->zh)`，`fallback to google` 仅偶发。
- `ASR silence config` 显示 `segmentationSilenceMs=800`、`maxSegmentWords=35`、印尼语最小句长生效；无 <24 字印尼语碎片。
- 译文无 `发件人`、无元话语（无法判断/说明/疑似识别错误）、无残留 `（疑似…）`。

### 必要时再做人工判断

- 对照导出的会议转写：`pacarmen`→“董事长”、`julong`→“聚龙”、`pupuk/boron/LSU/pH` 术语正确、整句通顺、数字量级正确。
- 分享页切换中文/英文/印尼语，三者音量基本一致、无爆音、静音不被放大。
- 体感延迟可接受（LLM 多约 1~2.5s/句）。

### 结果记录

- 通过 / 不通过：后端 `mvn test` 通过（339）；线上部署 `c3e0b52`，健康检查 OK；实测转写质量显著改善（pacarmen→董事长、聚龙、术语、断句、数字均正确）。
- 遗留问题：个别 ASR 偶发听错（bernilai→香草等）文本层无法恢复；术语合并修正总表需运维清空重导。
- 需回归项：长会议下 LLM 延迟与 `fallback to google` 比例；不同发言人停顿习惯下 `min-sentence-emit-id-chars` / 静音阈值是否需再调。

## Weekly Validation Record: 2026-W26 Meeting File Full-Text Extraction Wiring

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W26 Meeting File Full-Text Extraction Wiring`.

### Validation Goals

- Prove ordinary report uploads through `/api/meetings/{meetingId}/files` enqueue the same full-text extraction pipeline as `/api/pre-meeting/upload`.
- Prove extraction runs asynchronously and a failed extraction step does not fail the upload or block later steps.
- Prove file names are included in extraction text so speaker names and domain hints in report names can become hotwords or knowledge hints.

### Log / API / Database Validation First

```bash
# After uploading one report file from the meeting detail page:
docker logs --since 10m si-backend 2>&1 | grep -E \
 "MeetingController.*uploadFile|MeetingService.*uploadFile|MeetingMaterialExtractionService|HotwordExtractionService|extractMeetingKnowledgePack|TerminologyExtractionService"

# Expected: ordinary upload plus extraction start/end lines.
# For the fertilizer reports, the endpoint should be /api/meetings/<id>/files, not /api/pre-meeting/upload.

# Optional database checks after async extraction has time to finish:
docker exec -i si-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" sync_lingo -e \
 "SELECT COUNT(*) AS hotwords FROM asr_hotword WHERE user_id=<USER_ID> AND enabled=1;"
docker exec -i si-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" sync_lingo -e \
 "SELECT COUNT(*) AS terms FROM terminology WHERE user_id=<USER_ID> AND source_sheet='AUTO_DOC' AND enabled=1;"
```

Pass criteria:

- Logs include `MeetingMaterialExtractionService extract start` with `sourceType=MEETING_FILE` after `MeetingService uploadFile done`.
- Logs include hotword, knowledge pack, and terminology extraction activity or a step-level warning that does not stop later steps.
- Upload API still returns success before async extraction completes.
- Hotword/terminology rows increase when LLM extraction returns valid candidates.

### Automated Tests

```powershell
cd si-backend
mvn "-Dtest=MeetingMaterialExtractionServiceTest,MeetingServiceSecurityTest,UserIdBoundaryControllerTest" test
mvn test
```

### Manual Validation

- Upload the three bilingual report PDFs from the meeting detail page.
- Wait for async extraction to finish, then refresh hotword and terminology pages.
- Confirm extracted items are global/multilingual where expected and that report file names such as Rudi/Joshua/Gomgom are available to extraction.

### Result Record

- Focused tests passed locally for the new orchestrator, meeting file upload wiring, and controller constructor regression.
- Full backend `mvn test` passed locally: 366 tests, 0 failures, 0 errors.
- Server validation still requires redeploying this change and re-uploading or reprocessing the report files.

## Weekly Validation Record: 2026-W26 Meeting Material Extraction LLM Stabilization

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W26 Meeting Material Extraction LLM Stabilization`.

### Validation Goals

- Prove the production issue is the extraction LLM response shape, not the upload endpoint or async extraction wiring.
- Prove extraction requests can use a dedicated non-reasoning model and OpenRouter no-reasoning options.
- Prove hotword, terminology, and meeting knowledge extraction still tolerate bad chunks and continue processing later chunks.

### Log / API / Database Validation First

```bash
# Confirm the backend is running the newly deployed image.
curl -sf http://127.0.0.1:8080/api/health
docker logs --tail 80 si-backend 2>&1 | grep -E "Started|ERROR|Exception"

# Re-upload the three report PDFs, then confirm ordinary upload and async extraction start.
docker logs --since 20m si-backend 2>&1 | grep -E \
 "MeetingController.*uploadFile|MeetingService.*uploadFile|MeetingMaterialExtractionService|extractMeetingKnowledgePack|TerminologyExtractionService|HotwordExtractionService"

# Confirm extraction uses the dedicated model and no longer repeatedly fails with null content.
docker logs --since 60m si-backend 2>&1 | grep -E \
 "extractMeetingKnowledgePack start|extractTerminologyPairs|extractHotwordsJson|chunk extracted|terms=|phrases=|finishReason=length|content=null|empty content|repaired partial"

# Optional database checks after async extraction has finished.
docker exec -i si-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" sync_lingo -e \
 "SELECT COUNT(*) AS auto_doc_terms FROM terminology WHERE user_id=<USER_ID> AND source_sheet='AUTO_DOC' AND enabled=1;"
docker exec -i si-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" sync_lingo -e \
 "SELECT COUNT(*) AS auto_hotwords FROM asr_hotword WHERE user_id=<USER_ID> AND source_type='AUTO_EXTRACTED' AND enabled=1;"
```

Pass criteria:

- Upload logs still show `/api/meetings/<id>/files` followed by `MeetingMaterialExtractionService extract start`.
- Extraction logs show the dedicated extraction model, normally `anthropic/claude-haiku-4.5` unless `OPENAI_EXTRACTION_MODEL` is overridden.
- `finishReason=length` / `content=null` is not repeated for extraction calls after deployment.
- At least one successful extraction path logs `chunk extracted` with `terms=` or `phrases=`, and corresponding database rows increase.

### Automated Tests

```powershell
cd si-backend
mvn "-Dtest=LlmRequestOptionsTest,LlmMeetingKnowledgePackTest,HotwordExtractionServiceJsonRepairTest,TerminologyExtractionServiceJsonRepairTest,HotwordExtractionServiceDocumentChunkTest" test
mvn clean test
```

### Manual Validation

- Re-upload the same three bilingual PDFs after deploying this change, because previously failed fileIds are not automatically reprocessed.
- Refresh the hotword and terminology pages after async extraction finishes and confirm extracted items are visible.
- Spot-check that extracted terms cover Chinese, English, and Indonesian material names instead of only a small prefix of each report.

### Result Record

- Production logs after `8934134` deployment confirmed the full-text upload wiring works and isolated the remaining failure to reasoning-heavy LLM responses with empty `message.content`.
- Local focused tests passed: 7 tests, 0 failures, 0 errors.
- Local full backend verification passed: 373 tests, 0 failures, 0 errors.
- Server validation remains pending until this stabilization change is rebuilt on the server and the report files are re-uploaded or reprocessed.

## Weekly Validation Record: 2026-W26 ASR Final Remainder Alignment

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W26 ASR Final Remainder Alignment`.

### Validation Goals

- Prove Azure final text is no longer sliced by stale interim character offsets after forced segmentation.
- Prove final remainders align by complete emitted text, tolerate punctuation/case/spacing changes, and handle simple number normalization.
- Prove downstream ASR overlap trimming still works as a second safety net.

### Log / API / Data Validation First

```bash
# After deploying and running a meeting with long Indonesian speech:
docker logs --since 30m si-backend 2>&1 | grep -E \
 "force-segment by=sentence-wtpsplit|asr-segment final=remainder|final remainder aligned|adjacent overlap removed"

# Bad patterns should not appear at final-remainder starts:
docker logs --since 30m si-backend 2>&1 | grep "asr-segment final=remainder" | grep -E \
 "text='(i 1|epan |nal\\.|tal\\.|benar benar|8 tahun\\.|sebut\\.)"
```

Pass criteria:

- Forced segmentation continues to appear for long Indonesian segments.
- Final remainders after forced segments do not start with leaked tails from the previous segment.
- `final remainder aligned` appears when Azure final text drift required emitted-text or suffix realignment.
- `adjacent overlap removed` should be rare; it remains acceptable as downstream safety but should not be the primary cleanup path.

### Automated Tests

```powershell
cd si-backend
mvn "-Dtest=AzureAsrFinalRemainderTest,TranscriptOverlapTrimmerTest,AsrServiceOverlapTest" test
mvn test
```

### Manual Validation

- Reproduce a long Indonesian paragraph containing `menjadi 1 sistem` or `menjadi satu sistem`.
- Confirm transcript export contains only `sistem industri...` as the next segment, not `i 1 sistem...`.
- Confirm no audio or transcript segment is dropped; the fix only changes final remainder alignment.

### Result Record

- Local focused ASR seam tests passed: 13 tests, 0 failures, 0 errors.
- Local full backend verification passed: 388 tests, 0 failures, 0 errors.
- Server validation remains pending until this change is rebuilt and exercised in a live meeting.

## Weekly Validation Record: 2026-W26 Realtime TTS Queue and Indonesian Segmentation Stability

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W26 Realtime TTS Queue and Indonesian Segmentation Stability`.

### Validation Goals

- Prove synthesized/sent TTS audio is still delivered in sequence and is not cut by backend catch-up logic.
- Prove only unsynthesized TTS items can be skipped after a long ordered wait.
- Prove Indonesian forced segmentation no longer emits short fragments and no longer produces very large backstop bursts.
- Prove final remainders do not start with leaked punctuation.
- Prove id->zh LLM meta commentary is rejected before transcript/TTS.
- Prove failed Cartesia WebSocket clients are invalidated instead of reused.

### Log / API / Database Validation First

```bash
# After deployment, start from a fresh container log if possible.
docker logs --timestamps si-backend > /var/www/si/dbg-stream-full.log 2>&1

docker logs --timestamps si-backend 2>&1 | grep -E \
"ASR silence config|loaded hotwords|asr-stream|wtpsplit query|force-segment by=|force-segment deferred by min length|final remainder aligned|asr-segment final|adjacent overlap removed|idZhCorrectTranslate io|meta-commentary detected|fallback to google|glossaryLines|translate end|latency-breakdown|TTS queued|TTS first chunk|orderedWaitMs|TTS unsynthesized skipped|TTS order released|tts-audio-duration|CartesiaWsClient|invalidated failed client|e2e client latency" \
> /var/www/si/dbg-stream.log

DU=$(grep -m1 '^DB_USERNAME=' /opt/syncLingo/backend.env | cut -d= -f2- | tr -d '\r')
DP=$(grep -m1 '^DB_PASSWORD=' /opt/syncLingo/backend.env | cut -d= -f2- | tr -d '\r')
DN=$(grep -m1 '^DB_NAME=' /opt/syncLingo/backend.env | cut -d= -f2- | tr -d '\r')
SESSION_ID=$(docker exec -i si-mysql mysql --default-character-set=utf8mb4 -N -B -u"$DU" -p"$DP" "$DN" -e \
"SELECT session_id FROM interpretation_record GROUP BY session_id ORDER BY MAX(create_time) DESC LIMIT 1;")

docker exec -i si-mysql mysql --default-character-set=utf8mb4 -u"$DU" -p"$DP" "$DN" -e \
"SELECT seq, source_lang, target_lang, source_text, target_text, create_time
 FROM interpretation_record
 WHERE session_id='$SESSION_ID'
 ORDER BY seq;" > /var/www/si/transcript.tsv

docker exec -i si-mysql mysql --default-character-set=utf8mb4 -u"$DU" -p"$DP" "$DN" -e \
"SELECT id, speaker_id, source_lang, target_lang, source_text, translated_text, create_time
 FROM interpretation_result
 WHERE session_id='$SESSION_ID'
 ORDER BY id;" > /var/www/si/transcript-result.tsv

wc -l /var/www/si/dbg-stream-full.log /var/www/si/dbg-stream.log /var/www/si/transcript.tsv /var/www/si/transcript-result.tsv
```

Pass criteria:

- `TTS first chunk` includes `orderedWaitMs`, and TTS sequences are released in order for non-skipped items.
- `TTS unsynthesized skipped` appears only when `waitMs` exceeds `CARTESIA_TTS_UNSYNTHESIZED_SKIP_WAIT_MS`; skipped items have no earlier `TTS first chunk`.
- Forced Indonesian segments shorter than `AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS` are absent or logged as deferred.
- No `asr-segment final=remainder` text starts with standalone `.`, `,`, `?`, `;`, or `:`.
- `invalidated failed client` appears after Cartesia WebSocket failures, if any failures occur.
- Exported `transcript.tsv` and `transcript-result.tsv` contain no LLM meta phrases such as `无法确定`, `根据上文`, `可能的原句`, or `咨询词汇上下文后`.

### Automated Tests

```powershell
cd si-backend
mvn "-Dtest=RealtimeInterpretationOrderTest,AzureAsrFinalRemainderTest,LlmIdZhSanitizeTest,LlmRequestOptionsTest" test
mvn test
```

### Manual Validation

- Run at least one 60-minute meeting with Chinese, Indonesian, and English enabled.
- Keep the same report files/materials loaded unless the test is specifically about material extraction; the realtime queue and segmentation changes do not require re-uploading files.
- Monitor the share-audio page on the target language that previously accumulated backlog, and note whether audio remains ordered when backlog grows.
- If odd sound appears, repeat a shorter comparison using default voice and cloned voice separately.

### Result Record

- Local focused tests passed: 14 tests, 0 failures, 0 errors.
- Local full backend verification passed: 390 tests, 0 failures, 0 errors.
- Server validation remains pending until this change is deployed and a new long meeting log package is exported.

## 周度验证记录：2026-W26 印尼语流式分段完整性 Guard（去掉盲切 + 弱边界降级 partial）

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W26 印尼语流式分段完整性 Guard（去掉盲切 + 弱边界降级 partial）`。

### 验证目标

- 半词尾/连接词尾/可疑词头的 id 段不进入翻译（HOLD/DROP）。
- 固定短语（masa depan / sepak bola / Amerika Serikat 等）不被切断；`10 juta` 不识别为编号、`13 pemikiran` 识别为编号标题。
- 强边界即使 <48、过 Guard 也放行；弱边界即使够长也降级 partial，不作为 final。
- 部署后 `force-boundary` 盲切占比显著下降，final 以强边界为主。

### 优先通过日志 / 接口 / 数据库验证

本地单元（已执行，全绿）：

```powershell
cd D:\data\syncLingo-plus\si-backend
mvn -q -Dtest=IndonesianIncompleteGuardTest test
mvn test   # 全量回归
```

通过标准（本地）：

- `IndonesianIncompleteGuardTest` 13 条 P0 用例全过（半词尾 HOLD、可疑词头 HOLD、连接词尾 HOLD、masa depan / sepak bola / Amerika Serikat 不可切、10 juta 非编号、13 pemikiran 为编号、强边界短句放行、弱边界长句降级、HOLD/EMIT_FINAL 动作正确、final remainder 半词拦截、开关关闭回退）。
- 全量后端 404 tests，0 failures，0 errors，BUILD SUCCESS。

部署后（服务器，待执行）：

```bash
# 1) 确认开关与依赖
docker exec si-backend env | grep -E 'ID_SEGMENT_GUARD_ENABLED|SEGMENTATION_SERVICE_ENABLED'
docker logs si-backend 2>&1 | grep -E '\[IdGuard\] init|\[SegmentationService\] enabled='

# 2) 跑一段印尼语会议后，看切法占比（force-boundary 应明显下降）
docker logs si-backend 2>&1 | grep -oE 'force-segment by=[a-z-]+' | sort | uniq -c | sort -rn

# 3) 看 Guard 是否在拦截半词/残句
docker logs si-backend 2>&1 | grep -E '\[IdGuard\] (HOLD|DROP|DOWNGRADE_PARTIAL|final remainder suppressed|facade suppress)'

# 4) 确认翻译入库不再出现半词残句（DB 抽样）
docker exec si-mysql mysql -usync_lingo -p'<app-password>' si_backend \
  -e "select source_text from interpretation_result where source_lang like 'id%' order by id desc limit 50;"
```

通过标准（服务器）：

- `[IdGuard] init, enabled=true`；`force-segment by=force-boundary` 占比相对改造前显著下降，final 以 `sentence-wtpsplit`/`sentence-punct`/`numbered-title` 为主。
- 出现 `[IdGuard] ... HOLD/DOWNGRADE/veto`，被拦内容为半词/连接词尾/切断短语候选。
- `interpretation_result` 中 id 源文本不再出现 `…peng`、`tuk setiap`、`sepak`/`bola`、`Amerika`/`Serikat`、`masa`/`depan`、连接词结尾残句。

### 必要时再做人工判断

- 至少跑一场含印尼语的会议，主观确认中文译文通顺、无半句乱码、TTS 不念半词。
- 关注连续无停顿长句的 final 延迟是否在可接受范围（准确率优先的预期代价）。
- 如线上发现明显内容丢失（final remainder 被 HOLD/DROP 抑制掉的真实尾词），记录样本，评估二期 heldRemainder 跨句接续。

### 结果记录

- 本地：通过（全量 404 tests，0 失败；P0 13 用例全过）。
- 服务器：待部署后用真实印尼语长会议日志包复核。
- 遗留问题：见对应优化记录「遗留问题」。
- 需回归项：现有 `AsrServiceOverlapTest`、`finalRemainderAfterForcedSegments` 相关、`RealtimeInterpretationOrderTest`（已随本次回归通过）。

## Weekly Validation Record: 2026-W26 Indonesian Output Floor Follow-up

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W26 Indonesian Output Floor Follow-up`.

### Validation Goals

- Prove every Indonesian final output path obeys the configured short-sentence floor.
- Prove short replies and short `numbered-title` segments no longer bypass the floor.
- Prove short Azure final remainders are held in pending state and merged into the next Indonesian final output instead of entering transcript/TTS by themselves.
- Prove deployment uses the actual follow-up build, not only the old Guard configuration.

### Log / Database Validation First

Use the same one-file log package format after each live test:

```bash
tar -tzf /var/www/si/si-test-logs-<timestamp>.tar.gz

# Required evidence inside the package:
# - be-full.log
# - be-focused.log
# - speaker-full.log
# - speaker-focused.log
# - sessions.tsv
# - session-id.txt
# - transcript.tsv
# - result.tsv
```

Pass criteria:

- `be-focused.log` contains `ASR silence config` with `minSentenceEmitIdChars=48` or the intended configured value.
- `be-focused.log` contains `HOLD output-floor` when the ASR final remainder or forced candidate is below the floor.
- `be-focused.log` contains `pending output-floor merged` when a pending short Indonesian segment is released with the next Indonesian segment.
- `transcript.tsv` contains no Indonesian `source_text` rows below the floor, except terminal pending fragments that are logged as `pending output-floor not emitted on close` and do not enter transcript/TTS.
- `force-segment by=numbered-title` rows below the floor are absent or logged as held.

### 2026-06-28 Log Package Result

Log package reviewed:

```text
https://julongtongchuan.icu/si-test-logs-20260628-033113.tar.gz
```

Package contents:

- `be-focused.log`: 12,761 lines.
- `be-full.log`: 20,611 lines.
- `speaker-focused.log`: 1,745 lines.
- `speaker-full.log`: 3,516 lines.
- `transcript.tsv`: 97 lines including header.
- `result.tsv`: 97 lines including header.

Session:

- `session_id=0ff924ad-72b6-4385-9f80-7ca8cd3ae10c`.
- Database row count: 96 interpretation records.
- Database time range: `2026-06-27 19:19:00` to `2026-06-27 19:30:56`.

Observed evidence:

- Config loaded: `ASR silence config ... minSentenceEmitIdChars=48, idSegMinInputChars=40`.
- TTS ordering path was active: `TTS order reserved=97`, `TTS first chunk=96`, `TTS order released=97`; no `TTS unsynthesized skipped` in this run.
- id->zh LLM correction was active: `translate end (llm id->zh)=93`, `google translate done=3`, `meta-commentary detected=1`.
- Output-floor follow-up evidence was absent: `HOLD output-floor=0`, `pending output-floor=0`.
- Forced segments: 73 total; 72 `sentence-wtpsplit`, 1 `numbered-title`.
- One forced `numbered-title` segment still bypassed the intended floor: visible length 45, text `untuk perencanaan industri 3 5 bahkan 8 tahun ke depan`.
- Final ASR segments: 26 total; 23 `remainder`, 3 `full`.
- Short final segments below 48 visible characters: 13 total, including `4 nilai terbesar satelit.`, `11 berfokus p.`, `Pupuk yang terencana.`, `mencapai 52.672 ton`, and `kebetulan.`.
- Exported Indonesian transcript rows below 48 visible characters: 11.

### Result Record

- Local code validation: passed. Focused tests passed 30 tests, and full backend `mvn clean test` passed 413 tests.
- Server package `20260628-033113`: not a pass for the output-floor follow-up. It proves the old/partial server image was still running because short final remainders and a short `numbered-title` row reached transcript/TTS, and the new `HOLD output-floor` / `pending output-floor` logs never appeared.
- Required next action: commit/push the follow-up code, pull that exact commit on `/opt/syncLingo`, rebuild with Java 21, rebuild the Docker image, restart with `--network host --add-host si-mysql:127.0.0.1`, then run a new log package test.

### Manual Validation After Corrected Deployment

- Repeat the Indonesian test phrases: `ya`, `baik`, `terima kasih`, `ke depan`, `8 tahun`, `13 pemikiran...`, `14 kesimpulan...`, and long sentences with pauses.
- Confirm the UI transcript no longer shows short standalone Indonesian rows for those phrases.
- Confirm no sentence tail is duplicated into the next segment and no already-synthesized audio is skipped.
- If the last spoken phrase is intentionally shorter than the floor, check backend logs for `pending output-floor not emitted on close` and decide whether the product wants a terminal flush exception.

## Weekly Validation Record: 2026-W27 TTS Indonesian Synthesis Speed

### Matching Optimization Scope

- Matches `docs/optimization-implementation-plan.md` section `Weekly Optimization Record: 2026-W27 TTS Indonesian Synthesis Speed`.

### Validation Goals

- Prove Indonesian target TTS receives speed 1.3.
- Prove Chinese target TTS remains speed 1.1.
- Prove English/default target TTS remains speed 1.0.
- Prove the deployed server stays on the pre-Realtime hotfix line.

### Log / Runtime Validation First

```bash
cd /opt/syncLingo
git rev-parse --short HEAD
docker exec si-backend printenv AZURE_ASR_MIN_SENTENCE_EMIT_ID_CHARS
docker logs si-backend --since 20m 2>&1 | grep -E \
  "TTS queued|speed=1.3|speed=1.1|speed=1.0|OpenAI Realtime|openai_realtime|RealtimeFallbackCoordinator|ERROR|Exception"
```

Pass criteria:

- `git rev-parse --short HEAD` returns the hotfix commit based on `65a8e2f`, not the later Realtime line.
- zh-CN -> id/id-ID target logs contain `TTS queued ... speed=1.3`.
- id -> zh-CN target logs contain `TTS queued ... speed=1.1`.
- English/default target logs, if exercised, contain `speed=1.0`.
- Realtime logs (`OpenAI Realtime`, `openai_realtime`, `RealtimeFallbackCoordinator`) do not appear in the rollback deployment.

### Automated Tests

```powershell
cd si-backend
mvn -q -Dtest=RealtimeInterpretationOrderTest test
mvn -q test
```

Pass criteria:

- `targetLanguageControlsTtsSynthesisSpeed` passes and captures speed 1.3 for `id` and `id-ID`.
- Full backend test suite passes.

### Manual Validation

- Run a short Chinese -> Indonesian interpretation sample and listen for whether 1.3 remains intelligible.
- If the output sounds clipped, too rushed, or harms comprehension, compare 1.2 vs 1.3 in a follow-up change rather than changing frontend playback behavior.

### Result Record

- Local focused test passed: `mvn -q -Dtest=RealtimeInterpretationOrderTest test`.
- Local full backend verification passed: `mvn -q test`.
- Server validation pending after deployment and live test logs.
