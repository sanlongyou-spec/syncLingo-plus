# 流式标点还原分段方案

> 替代当前字符数强切，改用句末标点作为 ASR 分段依据，提升翻译准确性并降低客户端积压。

## 1. 背景与问题

### 1.1 现状

Azure zh-CN ConversationTranscriber 在 `Transcribing`（中间）和 `Transcribed`（最终）事件中均不返回标点符号。当前分段依赖两种机制：

1. **静音检测分段**：Azure 检测到句末静音（`segmentation-silence-timeout-ms: 300ms`）后触发 `Transcribed`，这是主要分段方式。
2. **字符数强切**（兜底）：稳定前缀超过 60 字且计时器超过 6s 时，在安全边界（标点 > 逗号 > 词/字）强制切段，防止超长句拖高延迟。

### 1.2 问题

字符数强切产生"尾段"问题：

- 强切取走稳定前缀后，剩余文字在下一个 ASR 事件中以极短语音窗口（200-600ms）触发
- 该尾段的 `sourceSpeechWindowMs`（`translateStart - speechStartAtMs`）只有 200-600ms
- 但这批文字是强切前 6s 积累的，和 200ms 没有实际对应关系
- 结果：TTS 音频时长 / 说话窗口 比率在日志中呈现极端值（最高 9.43x，均值 7.71x）
- **注意**：这是度量失真，不是同传本身的问题。强切尾段的 `sourceSpeechWindowMs` 量的是"距上次切段的时间间隔"，不是"这批文字对应多少说话时长"，导致均值被严重拉高（真实 p50 为 1.25x）。实现标点还原后，需同步修正该指标的计算口径。
- 导致客户端积压持续积累，长会议后积压超过 20s

### 1.3 目标

在 `Transcribing` 流式事件中实时预测标点位置，**按优先级在合适的标点处切段**，替代字符数强切。字符数强切保留为最终兜底。

预期效果：
- 分段在语义完整的句子/子句边界，翻译质量提升
- 消除强切产生的计时失真尾段
- 客户端长会议积压降低

---

## 2. 技术方案

### 2.1 标点还原模型

使用 **FunASR CT-Transformer**：

- 模型：`damo/punc_ct-transformer_zh-cn-common-vocab272727-pytorch`
- 特点：专为流式/不完整文本设计，支持中英混合，CPU 推理 ~10ms
- 部署方式：新增到现有 speaker-service（uvicorn，Python，已在 port 7000 运行）

### 2.2 分段优先级逻辑

```
参数（可配置）：
  MIN_CHARS        = 15   // 最短切段长度，避免切出无意义碎片
  CLAUSE_THRESHOLD = 30   // 超过此长度才考虑子句级标点
  MAX_CHARS        = 60   // 兜底强切阈值

标点优先级（高→低）：
  P1（句末）: 。！？；            → 语义完整，随时可切
  P2（分句）: ，、：——            → 仅当 length > CLAUSE_THRESHOLD 时考虑
  P3（兜底）: 字符数强切（原有逻辑）→ 仅当 length > MAX_CHARS 时触发

切点选择规则（在 punctuated_text 中从右往左找）：
  1. 找最后一个 P1 标点，位置 >= MIN_CHARS → 在此切
  2. 无 P1 且 length > CLAUSE_THRESHOLD → 找最后一个 P2 标点，位置 >= MIN_CHARS → 在此切
  3. 无 P1/P2 且 length > MAX_CHARS → P3 兜底强切

切段后：
  - 切点之前（含标点）→ emit 给翻译
  - 切点之后的剩余文本 → 退回，作为下一段的起始内容参与下一轮分段判断（不丢字）
    例：60 字阈值，在第 53 字处找到句末标点 → 前 53 字 emit，后 7 字退回下一轮
```

### 2.3 整体流程

```
Transcribing 事件
  → 计算新稳定前缀
  → 异步 POST /punctuate（超时 40ms）
      → 超时或失败 → 降级，走 P3 字符数兜底
      → 成功 → 按 2.2 优先级逻辑判断是否切段
          → 触发切段 → emit，重置计时器
          → 未触发   → 继续累积
  → P3 兜底：稳定前缀 > 60 字 且 计时器 > 8s → 强切

Transcribed 事件（静音检测触发）
  → 不变，直接 emit 完整句
```

关键设计决策：
- 兜底定时器从 6s 改为 8s（标点检测覆盖大部分情况，给更多时间）
- 标点服务调用**非阻塞**，不影响 `Transcribing` 事件处理速度
- 服务不可用时自动降级为现有字符数强切，不影响主链路

---

## 3. 改动清单

### 3.1 speaker-service（Python）

**文件**：`speaker-service/app.py`（或新建 `punctuation.py`）

新增端点：

```python
POST /punctuate
Request:  {"text": "今天会议主要讨论三个议题第一个是预算"}
Response: {"punctuated": "今天会议主要讨论三个议题，第一个是预算"}
```

实现：
1. 启动时加载 CT-Transformer 模型（一次性，~200MB）
2. 每次请求推理，返回加了标点的文本
3. 推理失败返回原文（降级）

### 3.2 新增 PunctuationService.java

**路径**：`si-backend/src/main/java/com/si/backend/service/PunctuationService.java`

职责：
- HTTP POST 调用 `/punctuate`
- 超时 40ms，失败/超时返回 null（调用方按 null 降级）
- 复用现有 speaker service 的 HTTP 客户端模式

### 3.3 application.yml

```yaml
punctuation:
  service:
    enabled: ${PUNCTUATION_SERVICE_ENABLED:false}  # 默认关闭，验证后再开
    url: ${PUNCTUATION_SERVICE_URL:http://localhost:7000}
    timeout-ms: ${PUNCTUATION_SERVICE_TIMEOUT_MS:40}
```

### 3.4 AzureAsrIntegration.java

在稳定前缀更新逻辑中：

1. 若 `punctuation.service.enabled=true`，异步调用 `PunctuationService.punctuate(stablePrefix)`
2. 响应含 `。！？` → 在最后一个句末标点位置切段
3. 响应为 null 或不含句末标点 → 走现有逻辑
4. 兜底定时器阈值：6s → 8s（仅在 enabled=true 时调整）

---

## 4. 实施顺序

### Phase 1：部署标点服务端点（独立验证）

1. 在 speaker-service 安装 FunASR 并加载 CT-Transformer 模型
2. 新增 `/punctuate` 端点
3. 用 curl 手动测试几组中文句子，验证标点质量和延迟（目标 <30ms）

### Phase 2：Java 侧客户端

1. 实现 `PunctuationService.java`，配置项默认 disabled
2. 单元测试：超时、失败的降级路径

### Phase 3：接入 ASR 流程

1. 修改 `AzureAsrIntegration.java`，`enabled=false` 时走原有逻辑（零风险）
2. 本地联调：开启 `PUNCTUATION_SERVICE_ENABLED=true`，观察分段日志
3. 对比测试：相同内容，enabled vs disabled，对比分段数量、尾段比率

### Phase 4：服务器验证

1. 部署新版本，`backend.env` 中先保持 `PUNCTUATION_SERVICE_ENABLED=false`
2. 跑一次完整会议测试，确认无回归
3. 切换为 `PUNCTUATION_SERVICE_ENABLED=true`，重启后端
4. 运行 `analyze_latency.py`，对比音频/说话比率和积压数据

---

## 5. 验收指标

| 指标 | 当前基准 | 目标 |
|------|------|------|
| 音频/说话比 均值 | 7.71x | <2.5x |
| 音频/说话比 p90 | 5.28x | <3.0x |
| e2e 中位延迟 | 9.1s | <7.5s |
| 最大积压（30分钟会议）| 20.7s | <12s |
| 强切触发率 | ~27% | <5%（兜底触发） |

---

## 6. 风险与降级

| 风险 | 应对 |
|------|------|
| 标点服务推理超过 40ms | 超时直接降级，不影响主链路 |
| 标点服务崩溃/重启 | `enabled` 可随时通过环境变量关闭，重启后端即回滚 |
| 标点质量差（乱加标点）| Phase 3 本地联调时验证，不满足再调阈值或换模型 |
| 模型占内存影响 speaker service | CT-Transformer ~200MB，与声纹模型共存，需确认服务器内存余量 |
