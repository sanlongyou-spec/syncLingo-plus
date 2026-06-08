# 交付记录 2026-06-08：同传 UI 微调 + 成本分析两档重做

- 分支：`final-version`
- 背景：上一版相关改动被回滚，本次在回滚后的基线上按既定方案重新实现。

---

## 1. 前端 UI 调整（[InterpretationView.tsx](../si-frontend/src/views/InterpretationView.tsx)）

### 1.1 语言选择：中文/印尼语必选，仅英语可选
- 中文(`zh-CN`)、印尼语(`id-ID`)是开会**必须语种**：复选框外观不变，但**恒为勾选且不可取消**（点击无效）；只有**英语(`en-US`)可自由勾选/取消**。
- `enabledLanguages` 始终包含 `zh-CN`、`id-ID`：
  - 初始默认 `[zh-CN, id-ID]`；
  - 读取用户偏好时用 `Set` 合并强制注入 zh/id；
  - 勾/取消英语后再次确保 zh/id 在列。
- 实现：渲染时按 `required = (值是 zh-CN 或 id-ID)` 决定 `checked` 恒真、`onChange` 对必选项直接 `return`。

### 1.2 侧边栏
- **「配置」改名「设置」**，并**移到侧边栏最下方**（路由仍是 `ROUTES.TERMINOLOGY`，即设置/术语页）。
- 顺序：Teams Bot → 音色克隆 → 历史记录 → 成本分析 → 分享链接 → **设置**。
- **「复制分享链接」改名「分享链接」**（功能不变，仍是复制链接）。

---

## 2. 成本分析：按官网定价重做 + LLM 两档计费

### 2.1 问题
原费率严重偏低、且实时压缩与会议总结**两种 LLM 挤在一个计数器、用同一单价**计费，导致便宜的总结被按贵价高估、TTS 又被大幅低估。

### 2.2 费率改为官网价（2026-06）
| 项 | 原值 | 改为 | 依据 |
|---|---|---|---|
| TTS Cartesia Sonic | $1.50/M字符 | **$35.00/M字符** | 1 credit/字符≈$35（克隆音色 1.5×≈$52） |
| LLM 实时压缩 输入 (Haiku 4.5) | $0.25/M tok | **$1.00/M tok** | Anthropic 官网 |
| LLM 实时压缩 输出 (Haiku 4.5) | $1.25/M tok | **$5.00/M tok** | Anthropic 官网 |
| LLM 总结/文档 输入 (DeepSeek V4 Pro) | —（原并入上一档） | **$1.74/M tok** | DeepSeek 官网 |
| LLM 总结/文档 输出 (DeepSeek V4 Pro) | — | **$3.48/M tok** | DeepSeek 官网 |
| ASR / 翻译 | $1/小时 / $10/M字符 | 不变（已符官网） | Azure |

### 2.3 LLM 拆两档（结构性改动）
- 会话表新增列 `llm_summary_input_tokens` / `llm_summary_output_tokens`（经 `addColumnIfMissing` 自动建列，无需手动迁移）。
- **实时压缩**(Haiku)仍记入原 `llm_input/output_tokens`；**会议总结/文档**(DeepSeek)改记入新列，各按各自单价计费。
- 路由调整：`MeetingSummaryService` / `MeetingMaterialService` 由 `addLlmTokens` 改调用新增的 `addSummaryLlmTokens`。
- 会前准备(`pre_meeting_usage_record`，用文档总结模型=DeepSeek)在前端改按总结档单价计算。

### 2.4 涉及文件
- 后端：`CostRatesProperties`、`application.yml`、`InterpretationSession`(实体)、`InterpretationSessionVo`、`InterpretationFacade`、`InterpretationSessionMapper`(新列 + 月度 SQL)、`InterpretationSessionService`(initColumns + `addSummaryLlmTokens` + 两处计费)、`CostController`(getRates + 两处计费)、`TeamsBotQueryService`(月度计费)、`MeetingSummaryService`、`MeetingMaterialService`。
- 前端：`types/index.ts`、`CostAnalysisView.tsx`（计费逻辑、默认费率、会前准备按总结档、月度 token 列、CSV、底部说明）。

### 2.5 注意事项
- 历史老会话的总结 token 仍混在 `llm_input_tokens` 里，只会按 Haiku 价算（差异极小）；**新产生的**总结才进新列、按 DeepSeek 价。
- 费率均可用环境变量覆盖（`COST_TTS_PER_MILLION_CHARS_USD`、`COST_SUMMARY_LLM_IN_PER_MILLION_TOKENS_USD` 等）。
- ⚠️ 待核对：`application.yml` 的 `compression-model` 默认是 `anthropic/claude-haiku-4.5`，但延迟实测里实时压缩用的是 **Qwen3-max**。若实际主用 Qwen3-max，实时档单价应按其官网价再校准（本次按配置中的 Haiku 落地）。

---

## 3. 测试结果（2026-06-08）

| 检查 | 结果 |
|---|---|
| 后端 `mvn -o compile` | ✅ 通过 |
| 后端 `mvn -o test-compile` | ✅ 通过 |
| 前端 `tsc --noEmit` | ✅ 通过 |

> 说明：上述为编译/类型层面的回归验证。成本数值的端到端核对需在有真实用量后，对照各服务商实际账单二次校准（尤其 TTS 套餐价与实时压缩实际所用模型）。
