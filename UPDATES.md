# syncLingo-plus 项目更新记录

## 2026-05-25（当前版本 · final-version 分支）

### 新增功能

#### 1. Teams Bot — 会前准备模块
- 支持上传 Word（.doc/.docx）、PDF、ZIP 包（自动解包内含文件）
- 多文件管理：同一会话可累计上传多个文件，逐个切换查看
- 并发 AI 总结：多个文件可同时提交，互不阻塞
- 默认总结要求：全局持久化配置，每次生成自动带入；支持叠加"本次额外要求"
- 服务端 docx 导出：用 Apache POI 在原文件上追加 AI 总结，完整保留原文 Word 格式
- 导出 PDF：浏览器打印方式，支持另存为 PDF
- 费用追踪：会前准备 AI 总结的 token 用量记录到数据库，并合并进成本分析页面

#### 2. Teams Bot — 会议集成
- 粘贴 Teams 会议链接让机器人加入会议
- 自动获取参会人员列表（姓名 + 邮箱）
- 摘要发送到会议聊天（所有人可见）
- 摘要私聊发送给每位参会人员

#### 3. 历史记录 — AI 会议总结
- 新增"摘要"Tab，查看或生成每次同传会议的 AI 总结
- 支持自定义总结要求（持久化到 localStorage），重新生成时自动带入
- 导出总结为 Word / PDF

#### 4. 术语表 — ASR 热词管理
- 独立 Tab 管理 ASR 识别热词（语言、权重、分类、启用开关）
- AI 提取热词：从历史同传记录中分析候选热词，两步流程（选会话 → 预览 → 确认导入）
- 从术语表批量同步热词
- 批量文本导入热词
- 按语言 / 分类过滤，分组折叠显示

#### 5. 成本分析（全新页面）
- 多维度费率计算：ASR（按音频时长）、翻译（按字符数）、TTS（按字符数）、LLM（按 token 数）
- 环形饼图展示各项费用占比
- 堆叠柱状图按日展示费用趋势
- Top 10 高成本会话排名
- 日期范围过滤（7天 / 30天 / 90天 / 全部）
- 会前准备 AI 总结费用同步纳入统计

#### 6. 主界面同传优化
- VoiceMeeter 多声道路由：中文 / 印尼语 / 英语分别输出到不同声道
- TTS 流式管道重构，降低延迟
- ASR 热词在启动时自动加载
- 支持多目标语言并发翻译

### 后端新增接口
| 接口 | 说明 |
|------|------|
| `POST /api/pre-meeting/upload` | 上传会议文件（Word/PDF/ZIP） |
| `POST /api/pre-meeting/summarize` | AI 总结文件内容 |
| `POST /api/pre-meeting/export/{fileId}` | 导出带 AI 总结的 docx 文件 |
| `GET  /api/pre-meeting/usage` | 查询会前准备费用记录 |
| `POST /api/summary/{sessionId}` | 生成/重新生成会议 AI 摘要 |
| `GET  /api/asr-hotwords/extract-preview/{sessionId}` | 预览从会话提取的热词建议 |
| `POST /api/asr-hotwords/extract-confirm` | 确认导入热词 |

### 待办 / 计划中
- [ ] AI 问答机器人：针对会议文件或同传记录的对话式查询

---

> 分支：`final-version`
> 上次提交：cf5bedc feat: add Teams meeting assistant workflow
