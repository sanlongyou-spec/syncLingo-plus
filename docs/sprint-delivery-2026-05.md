# syncLingo-plus 功能交付记录 · 2026-05

- 分支：`final-version`
- 记录时间：2026-05-25
- 基准提交：cf5bedc

---

## 一、本期新增功能

### 0. Teams Bot — AI 流式问答（2026-05-25 追加）
- 在 Teams 私聊/群聊中直接用自然语言提问，Bot 实时检索历史同传记录并流式生成答案
- 指令（最近/搜索/摘要等）与自由提问自动区分，走不同路径
- 流式推送：答案边生成边更新，无需等待完整响应（UpdateActivityAsync 渐进更新）
- 后端新增 SSE 端点 `POST /api/teams-bot/query/stream`（`text/event-stream`）
- 关键词检索：从问题中提取语义词，LIKE 搜索 `interpretation_result` 跨会话检索
- OpenAI Chat Completions `stream: true` + Java `HttpClient` 逐块推送

### 1. Teams Bot — 会前准备
- 上传 Word（.doc/.docx）、PDF、ZIP 包（自动解包内含文件）
- 多文件管理：同一会话累计上传多个文件，逐个切换
- 并发 AI 总结：多个文件可同时提交，互不阻塞
- 默认总结要求：持久化配置，每次生成自动带入；支持叠加"本次额外要求"
- 服务端 docx 导出：Apache POI 在原文件追加 AI 总结，保留原文 Word 格式
- 导出 PDF：浏览器打印，支持另存为 PDF
- token 用量写入数据库，汇入成本分析页面

### 2. Teams Bot — 会议集成
- 粘贴链接让机器人加入 Teams 会议
- 自动拉取参会人员列表（姓名 + 邮箱）
- 发送摘要到会议聊天 / 私聊每位参会人

### 3. 历史记录 — AI 会议摘要
- "摘要"Tab：查看或生成每次同传的 AI 纪要
- 自定义摘要要求（持久化到 localStorage），重新生成时带入
- 导出摘要为 Word / PDF

### 4. 术语表 — ASR 热词管理
- 独立 Tab 管理 ASR 热词（语言、权重、分类、启用开关）
- AI 提取热词：从历史同传记录分析候选词，两步确认导入
- 从术语表批量同步热词，支持批量文本导入
- 按语言/分类过滤，分组折叠显示

### 5. 成本分析（全新页面）
- ASR / 翻译 / TTS / LLM 多维费率计算
- 环形饼图 + 堆叠柱状图（按日）
- Top 10 高成本会话排名
- 7天 / 30天 / 90天 / 全部 范围过滤
- 会前准备 AI 费用合并统计

### 6. 同传主界面优化
- VoiceMeeter 多声道路由（中 / 印尼 / 英分别输出）
- TTS 流式管道重构，降低延迟
- ASR 热词启动时自动加载

### 7. 会议管理 — 文件持久化与会话绑定（2026-05-25 新增，2026-05-25 优化）

> 解决了会前上传文件重启后丢失、文件与同传会话无法关联的问题。

- **会议实体**：新增 `meeting` 数据表，存储会议标题、计划时间、备注；通过 `meeting_id` 外键将文件与同传会话统一挂靠到同一会议下
- **文件持久化**：新增 `pre_meeting_file_persistent` 表，上传文件内容写入 MySQL，重启后不丢失
- **TeamsBotView 会议管理面板（优化）**：输入会议名称 → 上传文件时自动创建会议；不再需要单独点击"新建会议"按钮。下拉菜单可切换已有会议，切换时自动填入会议名称
- **InterpretationView 会议选择器**：开始同传前从下拉菜单选择已创建的会议；选中会议后，会议名称自动作为同传会话标题，历史记录中直接显示会议名称
- **数据库自初始化**：所有新表通过 `@PostConstruct` + `CREATE TABLE IF NOT EXISTS` 在应用启动时自动建表，无需手动执行 DDL

### 9. 永久分享链接（2026-05-29 新增）

- 每个账号拥有一条**永不变化的分享 URL**：`#/share/user/{userId}`
- 收到链接的人打开后，无需刷新：
  - 无活跃同传时显示"等待同传开始..."
  - 同传开始后自动检测并连接 WebSocket，实时展示识别 / 翻译文本
  - 下一次同传开始时，自动清空旧内容并重新接入
- "复制分享链接"按钮已更新为始终可用（不再需要先开始同传），复制的即为永久 URL
- 新增后端公开接口 `GET /api/interpretation/public/user/{userId}/active`，返回当前活跃会话 ID（无需鉴权）

### 8. 发言人自动摘要 — 声纹触发 + Teams 推送（2026-05-25 新增，2026-05-25 优化）

> 利用现有声纹识别（Speaker Diarization）信号，在发言人切换时自动总结上一位发言人的内容并推送到 Teams。

- **声纹切换检测**：监听 ASR WebSocket `recognized` 事件，当 `speakerId` 变化时视为上一位发言人发言结束
- **发言文本累积**：`speakerBufferRef`（`Record<speakerId, text>`）累积每位发言人的最终识别段落；会话结束时刷新最后一位发言人的缓冲
- **LLM 摘要（优化）**：LLM 先生成"标题：XXX"（不超过10字的发言主题），然后按要点列出；要点数量不限制，完整覆盖内容。`speaker_summary` 表新增 `title` 字段
- **摘要持久化**：`speaker_summary` 表保存 sessionId、speakerId、speakerName、title（主题标题）、文本片段（前 2000 字）、摘要内容
- **Teams 主动推送（优化）**：消息标题格式为「`发言人 · 主题标题`」；Teams ID 在 localStorage 永久保存，一次配置所有会议生效，无需每场重填
- **摘要显示位置（调整）**：实时摘要面板从同传界面底部移除；摘要仅保存到数据库，在历史记录"发言摘要"Tab 中查看
- **历史记录"发言摘要"Tab（新增）**：HistoryView 新增第三个 Tab，显示该会话所有发言人的摘要，每条含发言人姓名、主题标题、摘要要点列表

---

### 后端新增接口（全量）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/pre-meeting/upload` | 上传会议文件（内存存储，会前查询用） |
| POST | `/api/pre-meeting/summarize` | AI 总结文件 |
| POST | `/api/pre-meeting/export/{fileId}` | 导出带总结的 docx |
| GET  | `/api/pre-meeting/usage` | 费用记录查询 |
| POST | `/api/summary/{sessionId}` | 生成/重新生成会议摘要 |
| GET  | `/api/asr-hotwords/extract-preview/{sessionId}` | 预览提取热词 |
| POST | `/api/asr-hotwords/extract-confirm` | 确认导入热词 |
| POST | `/api/teams-bot/query/stream` | Bot AI 流式问答（SSE） |
| POST | `/api/meetings` | 创建会议 |
| GET  | `/api/meetings?userId=X` | 列出用户所有会议 |
| GET  | `/api/meetings/{meetingId}` | 获取单个会议详情（含文件列表） |
| POST | `/api/meetings/{meetingId}/files` | 上传文件到会议（持久化） |
| GET  | `/api/meetings/{meetingId}/files` | 列出会议文件 |
| DELETE | `/api/meetings/{meetingId}/files/{fileId}` | 删除会议文件 |
| POST | `/api/meetings/speaker-summary` | 生成并持久化发言人摘要 |
| GET  | `/api/meetings/speaker-summaries/{sessionId}` | 按会话查询发言人摘要列表 |
| GET  | `/api/interpretation/public/user/{userId}/active` | 查询用户当前活跃同传会话 ID（公开，无需鉴权） |

---

## 二、早期测试记录（2026-05-25 初版）

> 完整测试方案与最新结果见第四节。早期自动化测试结果（登录/热词/语言偏好/文件导出边界）均 ✅ 通过，此处保留归档。

---

## 三、Teams Bot AI 流式问答（2026-05-25 新增）

### 3.1 功能说明

用户在 Teams 私聊或群聊中向 Bot 发送**自由提问**（非已知指令），Bot 会：

1. 在回应气泡中先显示 "⏳ 正在查询..."
2. 后端在所有历史同传记录中检索相关片段（MySQL LIKE 关键词搜索）
3. 调用 OpenAI Chat Completions（`stream: true`）生成答案
4. 答案以 SSE 块流式推送，C# Bot 每 2 秒用 `UpdateActivityAsync` 更新同一条气泡
5. 完成后最终更新，去除加载指示

### 3.2 变更文件清单

#### Java 后端

| 文件 | 变更说明 |
|------|---------|
| `integration/LlmIntegration.java` | 新增 `streamChatUnified()` — SSE 流式对话；新增 `summarizeSpeakerSegment()` — 生成发言人摘要（标题 + 不限数量要点） |
| `service/PreMeetingService.java` | 新增 `buildUnifiedContext()` — 跨会话检索；新增 `extractFileText()` — 供 MeetingService 复用文件解析逻辑 |
| `service/TeamsBotQueryService.java` | 新增 `queryStream()` — 鉴权、上下文构建、流式推送到 SseEmitter |
| `facade/TeamsBotQueryFacade.java` | 新增 `queryStream()` 委托方法 |
| `controller/TeamsBotQueryController.java` | 新增 `POST /api/teams-bot/query/stream`（`text/event-stream`） |
| `controller/MeetingController.java` | 新增会议管理接口（CRUD + 文件上传 + 发言人摘要） |
| `service/MeetingService.java` | 新增会议创建、文件持久化、文件查询/删除逻辑 |
| `service/SpeakerSummaryService.java` | 新增发言人摘要生成与持久化逻辑 |
| `mapper/MeetingMapper.java` | 自初始化 `meeting` 表；CRUD 方法 |
| `mapper/PersistentPreMeetingFileMapper.java` | 自初始化 `pre_meeting_file_persistent` 表；文件 CRUD |
| `mapper/SpeakerSummaryRecordMapper.java` | 自初始化 `speaker_summary` 表（含 `title` 列）；`addTitleColumnIfNotExists()` 兼容迁移；insert + findBySessionId |
| `entity/Meeting.java` | 会议实体 |
| `entity/PersistentPreMeetingFile.java` | 持久化文件实体 |
| `entity/SpeakerSummaryRecord.java` | 发言人摘要记录实体（含 `title` 字段） |
| `dto/CreateMeetingRequest.java` | 创建会议请求 DTO |
| `dto/SpeakerSummaryRequest.java` | 发言人摘要请求 DTO |
| `vo/MeetingVo.java` | 会议视图对象 |
| `vo/MeetingFileVo.java` | 会议文件视图对象 |
| `vo/SpeakerSummaryVo.java` | 发言人摘要视图对象 |
| `entity/InterpretationSession.java` | 新增 `meetingId` 字段 |
| `mapper/InterpretationSessionMapper.java` | INSERT 加入 `meeting_id`；新增 `addMeetingIdColumnIfNotExists()` |
| `service/InterpretationSessionService.java` | `startSession()` 接收 `meetingId` 参数并写入 |
| `facade/InterpretationFacade.java` | 传递 `request.getMeetingId()` 到 service |
| `dto/StartInterpretationRequest.java` | 新增 `meetingId` 字段 |

#### TypeScript 前端

| 文件 | 变更说明 |
|------|---------|
| `types/index.ts` | 新增 `Meeting`、`MeetingFile`、`SpeakerSummaryResult`（含 `title`）、`SpeakerSummaryRecord` 接口；`StartInterpretationParams` 加入 `meetingId` |
| `api/index.ts` | 新增 `createMeeting`、`getMeetings`、`uploadFileToMeeting`、`getMeetingFiles`、`deleteMeetingFile`、`generateSpeakerSummary`、`getSpeakerSummaries` |
| `views/InterpretationView.tsx` | 会议选择器；选中会议自动设置会话标题；声纹切换检测；发言人文本累积；自动摘要触发；Teams 推送（标题含发言人名+主题）；移除实时摘要面板 |
| `views/InterpretationView.css` | 移除摘要面板样式 |
| `views/TeamsBotView.tsx` | 会议管理面板（名称输入→上传自动创建→文件列表）；移除独立"新建会议"按钮 |
| `views/TeamsBotView.css` | 会议名称输入框 `.tb-meeting-input--time` 修饰符 |
| `views/HistoryView.tsx` | 新增"发言摘要"Tab；加载并展示 speaker_summary 记录（发言人、主题标题、摘要要点） |
| `views/HistoryView.css` | 发言摘要记录卡片样式 |

#### C# Teams Bot

| 文件 | 变更说明 |
|------|---------|
| `Services/SyncLingo/ISyncLingoBotQueryService.cs` | 接口新增 `StreamQueryAsync()` |
| `Services/SyncLingo/SyncLingoBotQueryService.cs` | 实现 SSE 流读取 + chunk 回调 |
| `Bots/MessageBot.cs` | 指令检测 + 流式路径分支 |

### 3.3 架构要点

```
Teams 用户发消息
    │
    ├─ IsKnownCommand? ──Yes──→ QueryAsync (同步) → 现有指令处理逻辑
    │
    └─ No (自由提问)
        │
        ├─ SendActivityAsync("⏳ 正在查询...")  ← 获取 activityId
        │
        ├─ StreamQueryAsync
        │   ├─ POST /api/teams-bot/query/stream (SSE)
        │   ├─ buildUnifiedContext: 关键词提取 → LIKE 搜索 interpretation_result
        │   ├─ streamChatUnified: OpenAI /chat/completions stream
        │   └─ 每块 data: chunk → chunkCallback → 每 2s UpdateActivityAsync
        │
        └─ UpdateActivityAsync(final answer)
```

**声纹驱动发言人摘要流程**：
```
ASR WebSocket 'recognized' 事件
    │
    ├─ 当前 speakerId == 上次 speakerId → 追加文本到 speakerBufferRef[id]
    │
    └─ speakerId 变化（发言人切换）
        │
        ├─ bufferLen ≥ 30 字 → POST /api/meetings/speaker-summary
        │   ├─ LlmIntegration.summarizeSpeakerSegment() → OpenAI → 3-5 要点
        │   ├─ 写入 speaker_summary 表（sessionId, speakerId, speakerName, textSnippet, summary）
        │   └─ 若配置了 recipients → Teams Bot 主动推送摘要
        │
        └─ 清空上一发言人 buffer，开始新发言人计数
```

**指令前缀列表**（发送这些词走旧逻辑，不触发 AI 问答）：
`help / hi / hello / 帮助 / 菜单 / 说明 / ？ / ? / 最近 / 历史 / list / history / 摘要 / 总结 / 纪要 / summary / 搜索 / 查 / 查询 / search`

### 3.4 新增接口

| 方法 | 路径 | Content-Type | 说明 |
|------|------|-------------|------|
| POST | `/api/teams-bot/query/stream` | `text/event-stream` | Bot AI 流式问答，返回 SSE；消息不能为空（400） |

**SSE 格式**：
```
data: 第一段回答文字\n\n
data: 更多文字\n\n
data: [DONE]\n\n
```
- 用户未绑定：发送提示文本 + `[DONE]`，不调用 LLM
- 无匹配记录：发送"未找到相关内容"提示 + `[DONE]`
- LLM 出错：发送 `[ERROR]`

---

## 四、测试方案与结果

### 4.1 编译级检查（已执行 · 2026-05-25）

| 检查项 | 命令 | 结果 |
|--------|------|------|
| Java 编译 | `mvn package -DskipTests` | ✅ BUILD SUCCESS |
| TypeScript 类型检查 | `npx tsc --noEmit` | ✅ 0 错误 |
| C# 编译 | `dotnet build -c Release` | ✅ 0 错误，14 警告 |

---

### 4.2 自动化 API 测试脚本

> 以下脚本在后端运行时可执行（`python tests/api_test.py`）。
> 当前状态：后端未在本地启动，**API 测试结果标记为 ⏳ 待执行**。
> 编译级检查（4.1）均已通过。

将以下内容保存为 `tests/api_test.py`：

```python
"""
syncLingo-plus API 自动化测试
覆盖：会议管理、文件持久化、发言人摘要持久化
用法：python tests/api_test.py [--base-url http://host:8080] [--user-id 1]
"""
import argparse
import json
import os
import sys
import io
import time

try:
    import requests
except ImportError:
    print("请先安装依赖: pip install requests")
    sys.exit(1)

parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://localhost:8080")
parser.add_argument("--user-id", type=int, default=1)
args = parser.parse_args()

BASE = args.base_url
UID  = args.user_id

passed = failed = 0

def ok(name):
    global passed
    passed += 1
    print(f"  ✅ PASS  {name}")

def fail(name, reason=""):
    global failed
    failed += 1
    print(f"  ❌ FAIL  {name}" + (f" — {reason}" if reason else ""))

def check(name, cond, reason=""):
    if cond:
        ok(name)
    else:
        fail(name, reason)

# ── 1. 登录（回归）─────────────────────────────────────────────────────────
print("\n[1] 回归：登录")
r = requests.post(f"{BASE}/api/auth/login", json={"username": "admin", "password": "admin123"})
check("登录成功 code=200", r.status_code == 200)
body = r.json()
check("响应包含 code 字段", "code" in body)

r2 = requests.post(f"{BASE}/api/auth/login", json={"username": "admin", "password": "wrong"})
check("密码错误 code≠200", r2.status_code != 200 or r2.json().get("code") != 200)

# ── 2. 会议 CRUD ────────────────────────────────────────────────────────────
print("\n[2] 会议 CRUD")
r = requests.post(f"{BASE}/api/meetings", json={
    "userId": UID,
    "title": "自动化测试会议",
    "scheduledTime": "2026-06-01 10:00",
    "note": "api_test.py 创建"
})
check("POST /api/meetings → 201/200", r.status_code in (200, 201))
meeting_id = None
if r.status_code in (200, 201):
    data = r.json().get("data", {})
    meeting_id = data.get("id")
    check("响应包含 id", meeting_id is not None)
    check("标题匹配", data.get("title") == "自动化测试会议")
else:
    fail("无法获取 meeting_id，跳过后续会议测试")

if meeting_id:
    r = requests.get(f"{BASE}/api/meetings", params={"userId": UID})
    check("GET /api/meetings 返回列表", r.status_code == 200)
    items = r.json().get("data", [])
    check("列表包含新建会议", any(m.get("id") == meeting_id for m in items))

    r = requests.get(f"{BASE}/api/meetings/{meeting_id}")
    check("GET /api/meetings/{id} 返回详情", r.status_code == 200)
    check("详情 id 匹配", r.json().get("data", {}).get("id") == meeting_id)

# ── 3. 文件上传与管理 ────────────────────────────────────────────────────────
print("\n[3] 文件上传与管理")
file_id = None
if meeting_id:
    txt_content = b"This is a test agenda file for api_test."
    files = {"file": ("test_agenda.txt", io.BytesIO(txt_content), "text/plain")}
    r = requests.post(f"{BASE}/api/meetings/{meeting_id}/files", files=files)
    check("POST /api/meetings/{id}/files → 上传成功", r.status_code == 200)
    if r.status_code == 200:
        file_id = r.json().get("data", {}).get("id")
        check("响应包含文件 id", file_id is not None)
        check("文件名匹配", r.json().get("data", {}).get("fileName") == "test_agenda.txt")

    r = requests.get(f"{BASE}/api/meetings/{meeting_id}/files")
    check("GET /api/meetings/{id}/files 返回列表", r.status_code == 200)
    files_list = r.json().get("data", [])
    check("文件列表包含上传的文件", any(f.get("id") == file_id for f in files_list) if file_id else False)

    if file_id:
        r = requests.delete(f"{BASE}/api/meetings/{meeting_id}/files/{file_id}")
        check("DELETE /api/meetings/{id}/files/{fid} → 删除成功", r.status_code == 200)

        r = requests.get(f"{BASE}/api/meetings/{meeting_id}/files")
        files_after = r.json().get("data", [])
        check("删除后文件列表不包含该文件", all(f.get("id") != file_id for f in files_after))
    else:
        fail("file_id 为空，跳过删除测试")
else:
    fail("meeting_id 为空，跳过文件测试")

# ── 4. 发言人摘要接口 ────────────────────────────────────────────────────────
print("\n[4] 发言人摘要")
TEST_SESSION = f"api_test_session_{int(time.time())}"

r = requests.post(f"{BASE}/api/meetings/speaker-summary", json={
    "userId": UID,
    "sessionId": TEST_SESSION,
    "speakerId": "speaker_A",
    "speakerName": "张三",
    "text": "我们今年第一季度的销售额达到了1.2亿元，同比增长15%。"
           "主要增长来自东南亚市场，尤其是印尼和越南。"
           "建议下季度加大在这两个市场的投入，同时保持国内市场的稳定增长。"
})
check("POST /api/meetings/speaker-summary → 200", r.status_code == 200)
if r.status_code == 200:
    data = r.json().get("data", {})
    check("响应包含 summary 字段", bool(data.get("summary")))
    check("speakerName 匹配", data.get("speakerName") == "张三")
    summary_text = data.get("summary", "")
    check("摘要非空且长度 > 10", len(summary_text) > 10)

# ── 5. 发言人摘要持久化查询 ──────────────────────────────────────────────────
print("\n[5] 摘要持久化")
r = requests.get(f"{BASE}/api/meetings/speaker-summaries/{TEST_SESSION}")
check("GET /api/meetings/speaker-summaries/{sid} → 200", r.status_code == 200)
records = r.json().get("data", [])
check("数据库包含刚生成的摘要记录", len(records) >= 1)
if records:
    rec = records[0]
    check("记录 sessionId 匹配", rec.get("sessionId") == TEST_SESSION)
    check("记录 speakerName 匹配", rec.get("speakerName") == "张三")
    check("记录 summary 非空", bool(rec.get("summary")))
    check("记录 textSnippet 非空", bool(rec.get("textSnippet")))

# ── 6. 参数校验 ──────────────────────────────────────────────────────────────
print("\n[6] 参数校验（@Valid）")
r = requests.post(f"{BASE}/api/meetings", json={"userId": UID})  # 缺 title
check("缺 title → 400", r.status_code == 400)

r = requests.post(f"{BASE}/api/meetings/speaker-summary", json={
    "userId": UID, "sessionId": TEST_SESSION
    # 缺 text
})
check("缺 text → 400", r.status_code == 400)

r = requests.post(f"{BASE}/api/teams-bot/query/stream",
                  json={"userId": UID, "message": ""},
                  stream=True)
check("空消息 → 400", r.status_code == 400)

# ── 7. SSE 端点基础验证 ──────────────────────────────────────────────────────
print("\n[7] SSE 流式端点")
r = requests.post(f"{BASE}/api/teams-bot/query/stream",
                  json={"userId": 999999, "message": "这个月会议的主要议题是什么？"},
                  stream=True, timeout=15)
check("未绑定用户 → HTTP 200 + SSE 数据", r.status_code == 200)
if r.status_code == 200:
    lines = []
    for line in r.iter_lines(decode_unicode=True):
        if line:
            lines.append(line)
        if len(lines) >= 3:
            break
    has_data = any(l.startswith("data:") for l in lines)
    check("SSE 返回 data: 行", has_data)

# ── 汇总 ──────────────────────────────────────────────────────────────────────
print(f"\n{'='*50}")
total = passed + failed
print(f"结果：{passed}/{total} 通过")
if failed:
    print("部分测试失败，请检查后端日志。")
    sys.exit(1)
else:
    print("所有测试通过 ✅")
```

---

### 4.3 自动化测试结果

#### 编译级（已执行）

| # | 测试项 | 期望 | 结果 |
|---|--------|------|------|
| 1 | Java Maven 编译 | BUILD SUCCESS | ✅ PASS |
| 2 | TypeScript `tsc --noEmit` | 0 错误（含本次优化后重测） | ✅ PASS |
| 3 | C# `dotnet build -c Release` | 0 错误 | ✅ PASS |

#### 既有 API 回归（第一期已执行）

| # | 测试项 | 期望 | 结果 |
|---|--------|------|------|
| 4 | 登录成功 | code=200 | ✅ PASS |
| 5 | 登录失败（密码错误） | code≠200 | ✅ PASS |
| 6 | ASR 热词列表查询 | code=200 | ✅ PASS |
| 7 | 语言偏好查询 | code=200 | ✅ PASS |
| 8 | 跨会议问答 `/api/pre-meeting/chat` | code=200 | ✅ PASS |
| 9 | Bot 指令路由 `help/list/search/summary` | 正确 command | ✅ PASS |
| 10 | SSE 端点：未绑定用户 | HTTP 200 + 中文提示 | ✅ PASS |
| 11 | SSE 端点：空消息 | HTTP 400 | ✅ PASS |

#### 新增 API（待执行 — 需后端运行）

| # | 测试项 | 期望 | 结果 |
|---|--------|------|------|
| 12 | 创建会议 | 200，返回 meeting_id | ⏳ 待执行 |
| 13 | 列出会议 | 200，列表含新建会议 | ⏳ 待执行 |
| 14 | 获取会议详情 | 200，id 匹配 | ⏳ 待执行 |
| 15 | 上传文件到会议 | 200，返回 file_id | ⏳ 待执行 |
| 16 | 列出会议文件 | 200，文件在列表中 | ⏳ 待执行 |
| 17 | 删除会议文件 | 200，列表中消失 | ⏳ 待执行 |
| 18 | 生成发言人摘要 | 200，summary 非空 | ⏳ 待执行 |
| 19 | 摘要响应包含 title 字段 | title 非空字符串 | ⏳ 待执行 |
| 20 | 查询摘要持久化 | 200，DB 记录存在 | ⏳ 待执行 |
| 21 | DB 记录 title 字段持久化 | title 与生成时一致 | ⏳ 待执行 |
| 22 | 摘要 sessionId/speakerName 匹配 | 字段一致 | ⏳ 待执行 |
| 23 | 创建会议缺 title → 400 | 400 | ⏳ 待执行 |
| 24 | 摘要缺 text → 400 | 400 | ⏳ 待执行 |

**执行方法**：
```bash
# 启动后端
docker-compose up -d --build si-backend

# 等待健康检查
curl -f http://localhost:8080/actuator/health

# 运行测试（默认 userId=1，需该用户已存在）
pip install requests
python tests/api_test.py --base-url http://localhost:8080 --user-id 1
```

---

### 4.4 需人工验证的功能

以下功能依赖真实 Teams 会议环境、麦克风或声纹识别运行时，无法在 CI 中自动化。

#### 会议管理（新增/优化）

| # | 测试项 | 操作步骤 | 预期结果 | 状态 |
|---|--------|---------|---------|------|
| M-14 | 输入名称后上传自动创建会议 | 打开"会前准备"Tab → 在"会议名称"输入框填写名称 → 直接拖拽/选择文件上传 | 会议自动创建并出现在下拉列表；文件显示在会议文件列表中；无需单独点"新建会议"按钮 | ⬜ 待测 |
| M-15 | 切换已有会议时名称自动填入 | 在"切换至已有会议"下拉中选择一个已创建的会议 | 会议名称输入框自动更新为该会议的名称；文件列表显示该会议已上传文件 | ⬜ 待测 |
| M-16 | 选择会议后历史记录显示会议名（InterpretationView） | 在页脚下拉菜单选择刚创建的会议 → 点击"开始同传" → 结束后查看历史记录 | 历史记录中该会话的标题与会议名称一致 | ⬜ 待测 |
| M-17 | 文件重启持久化 | 上传文件后重启后端容器 → 再次访问会议文件列表 | 文件仍然存在（不丢失） | ⬜ 待测 |

#### 发言人自动摘要（新增/优化）

| # | 测试项 | 操作步骤 | 预期结果 | 状态 |
|---|--------|---------|---------|------|
| M-18 | 声纹切换触发摘要 | 启用"自动摘要"开关 → 开始同传 → 模拟两人轮流发言 | 发言人切换时，后台调用 LLM 生成摘要并写入 `speaker_summary` 表；同传界面底部**不**显示实时摘要 | ⬜ 待测 |
| M-19 | 摘要含标题字段 | 完成同传后，查看 `speaker_summary` 表或历史记录"发言摘要"Tab | 每条记录的 `title` 字段非空（如"Q1销售汇报"）；`summary` 包含不限数量的「•」要点 | ⬜ 待测 |
| M-20 | 历史记录"发言摘要"Tab | 在历史记录中打开一条有发言人摘要的会话 → 点击"发言摘要"Tab | 显示所有发言人的摘要卡片，每张卡片含发言人姓名、主题标题、要点列表 | ⬜ 待测 |
| M-21 | Teams 推送含标题 | 配置 Teams 接收者 aadId → 触发摘要 | Teams 收到的消息标题格式为「📋 张三 · Q1销售汇报」，后跟要点 | ⬜ 待测 |
| M-22 | Teams aadId 永久保存 | 填写 aadId → 刷新页面 → 重新打开同传页面 | aadId 仍然填写在输入框中（localStorage 持久化），无需重填 | ⬜ 待测 |
| M-23 | 会话结束刷新最后发言人 | 在发言人 A 发言时点击"停止同传" | A 的摘要也被触发（stopSession 会刷新最后一个 buffer） | ⬜ 待测 |

#### Teams Bot AI 流式问答（存量）

| # | 测试项 | 操作步骤 | 预期结果 | 状态 |
|---|--------|---------|---------|------|
| M-1 | Bot 收到提问并流式回答 | 在 Teams 私聊发送"这次会议讨论了什么预算？"（需有历史同传记录） | Bot 先显示"⏳ 正在查询..."，随后逐步更新为 AI 答案 | ⬜ 待测 |
| M-2 | 问题无对应记录 | 发送与任何历史会议无关的问题 | Bot 回复"未找到相关内容" | ⬜ 待测 |
| M-3 | 指令不触发 AI 问答 | 发送"最近" | Bot 回复最近会议列表（旧逻辑） | ⬜ 待测 |
| M-4 | 搜索指令不触发 AI 问答 | 发送"搜索 预算" | Bot 回复搜索结果列表 | ⬜ 待测 |
| M-5 | 用户未绑定时提示清晰 | 用未注册 Teams 账号发消息 | Bot 回复账号未绑定说明 | ⬜ 待测 |
| M-6 | 多轮流式推送正常完成 | 发送较长问题，观察气泡更新过程 | 中间有多次"…⏳"更新，最终显示完整答案 | ⬜ 待测 |

#### 会前准备（存量）

| # | 测试项 | 验证方法 | 状态 |
|---|--------|---------|------|
| M-7 | 上传真实 .docx 文件 | 上传含格式（粗体/表格）的 Word，检查解析不报错 | ⬜ 待测 |
| M-8 | 上传 PDF | 上传 PDF，检查文字提取正常 | ⬜ 待测 |
| M-9 | AI 总结生成 | 选文件点"生成 AI 总结"，等待结果显示 | ⬜ 待测 |
| M-10 | 导出 Word（.docx） | 导出后用 Word 打开，确认原文格式保留，AI 总结追加在末尾 | ⬜ 待测 |

#### 同传核心（存量）

| # | 测试项 | 验证方法 | 状态 |
|---|--------|---------|------|
| M-11 | TTS 实时播放 | 同传过程中 TTS 无明显延迟、无卡顿 | ⬜ 待测 |
| M-12 | ASR 热词生效 | 启用热词，开始同传后确认识别率提升 | ⬜ 待测 |
| M-13 | 机器人加入会议 | 粘贴真实 Teams 会议链接，确认加入成功 | ⬜ 待测 |

**人工测试前置条件**：
1. 后端已启动（`docker-compose up -d --build si-backend`）
2. 数据库已初始化（可手动执行 `si-backend/sql/2026-05-25-add-meeting-tables.sql`，或让后端自启动建表）
3. 至少存在一个用户（userId=1 或其他）
4. Teams Bot 已重启并安装到测试账号
5. ASR 订阅可用（Azure Cognitive Services Speech SDK 配置正确）

---

---

## 六、2026-05-28 新增功能（本次迭代）

### 6.1 历史记录设置提前显示

**问题**：摘要要求输入框、接收人选择器此前只有在同传开始后才会出现，操作不便。

**变更**（`si-frontend/src/views/HistoryView.tsx`）：
- "发言摘要"和"会议总结"两个 Tab 的**摘要要求**输入框、**接收人选择器**移出 `{selectedSessionId && ...}` 条件渲染，在选中历史记录之前就常驻显示
- 设置即刻生效并持久化到 localStorage（与同传会话无关）
- 新增"发送到会议聊天"复选框（默认不勾选），持久化 key: `MEETING_SUMMARY_INCLUDE_CHAT`

### 6.2 发言人误识别防抖

**问题**：单句短文本导致声纹识别误判换人，错误地触发摘要。

**变更**（`si-frontend/src/views/InterpretationView.tsx`）：
- 新增常量 `MIN_SPEAKER_CHANGE_CHARS = 30`
- 新增 `pendingSpeakerRef`：候选新发言人缓冲区，累积文本未达 30 字则回归给上一发言人
- `stopSession` 时同步判断并清空 pending buffer

**效果**：单句短文误识别不再触发发言人切换，减少无效摘要调用。

### 6.3 向量语义 RAG 升级（跨会议问答）

将原先 MySQL LIKE 关键词检索替换为 OpenAI Embedding + 余弦相似度向量检索，大幅提升跨会议问答的召回精度和语义相关性。

#### 架构变化

```
旧方案：问题 → 关键词提取 → LIKE 搜索 interpretation_result → 组织上下文 → LLM
新方案：问题 → OpenAI embed() → 余弦相似度搜索 interpretation_embedding → 组织上下文 → LLM
```

#### 新增文件

| 文件 | 说明 |
|------|------|
| `si-backend/sql/2026-05-28-add-embedding-table.sql` | `interpretation_embedding` 表 DDL（手动执行或启动自建） |
| `entity/InterpretationEmbedding.java` | 嵌入向量实体 |
| `dto/EmbedCandidate.java` | 批量构建嵌入的候选 DTO |
| `mapper/InterpretationEmbeddingMapper.java` | 向量表 CRUD + 动态查询接口 |
| `mapper/InterpretationEmbeddingMapper.xml` | 动态 WHERE 子句（meetingId/speakerName/since 过滤） |
| `service/VectorSearchService.java` | 余弦相似度搜索服务（float↔byte 转换、topK 过滤、minScore 阈值） |
| `controller/AdminController.java` | `POST /api/admin/embeddings/rebuild` — 批量补建历史嵌入 |
| `test/.../VectorSearchServiceTest.java` | 12 个纯数学单元测试（round-trip、余弦、边界） |

#### 修改文件

| 文件 | 变更 |
|------|------|
| `config/OpenAiProperties.java` | 新增 `embeddingModel`、`embeddingTopK`、`embeddingMinScore` 字段 |
| `application.yml` | 新增 `openai.embedding-*` 环境变量映射 |
| `integration/LlmIntegration.java` | 新增 `embed(String text)` — 调用 `/embeddings` API 返回 `float[]` |
| `service/InterpretationResultService.java` | `save()` 后异步调用 `asyncEmbed()` — 后台线程生成并持久化向量；新增 `rebuildEmbeddings(int limit)` 批量回填 |
| `service/PreMeetingService.java` | `buildUnifiedContext()` 和 `chatCrossMeeting()` 改用向量检索替代关键词 LIKE；注入 `VectorSearchService` |

#### 默认配置

| 参数 | 默认值 | 环境变量 |
|------|--------|---------|
| 嵌入模型 | `text-embedding-3-small` | `OPENAI_EMBEDDING_MODEL` |
| topK | 20 | `OPENAI_EMBEDDING_TOP_K` |
| 最低相似度 | 0.3 | `OPENAI_EMBEDDING_MIN_SCORE` |

#### 部署注意

1. 首次部署后执行一次回填：`POST /api/admin/embeddings/rebuild?batchLimit=500`
2. 表由 `InterpretationResultService.initTable()` 在应用启动时自动建表（`CREATE TABLE IF NOT EXISTS`）
3. 若 `OPENAI_API_KEY` 未配置，`asyncEmbed()` 会静默跳过（warn 日志，不影响同传主流程）

---

## 七、测试记录（2026-05-28）

### 7.1 编译级检查（已执行，2026-05-28 第二次）

| 检查项 | 命令 | 结果 |
|--------|------|------|
| Java 编译 | `mvn compile -q` | ✅ BUILD SUCCESS |
| TypeScript 类型检查 | `npx tsc --noEmit` | ✅ 0 错误 |

### 7.2 单元测试（已执行）

`VectorSearchServiceTest` — 覆盖向量序列化和余弦相似度纯数学逻辑（无外部依赖）：

| # | 测试用例 | 结果 |
|---|----------|------|
| 1 | `roundTripEmptyArray` — 空数组序列化/反序列化 | ✅ PASS |
| 2 | `roundTripSingleValue` — 单浮点精度 | ✅ PASS |
| 3 | `roundTripFullVector` — 含极值的完整向量 | ✅ PASS |
| 4 | `toFloatsNullReturnsEmpty` — null 输入 | ✅ PASS |
| 5 | `cosineIdenticalVectorsIsOne` — 同向量相似度=1 | ✅ PASS |
| 6 | `cosineOrthogonalVectorsIsZero` — 正交向量相似度=0 | ✅ PASS |
| 7 | `cosineOppositeVectorsIsMinusOne` — 反向量相似度=-1 | ✅ PASS |
| 8 | `cosineEmptyVectorIsZero` — 空向量相似度=0 | ✅ PASS |
| 9 | `cosineMismatchedLengthIsZero` — 维度不匹配=0 | ✅ PASS |
| 10 | `cosineKnownAngle45Degrees` — cos(45°)=√2/2 精度验证 | ✅ PASS |
| 11 | `cosineZeroVectorIsZero` — 零向量相似度=0 | ✅ PASS |
| 12 | `toBytesLengthIsFourTimesFloatCount` — 1536维字节长度 | ✅ PASS |

**汇总：12/12 通过，0 失败，运行时间约 0.1 s**

### 7.3 静态结构验证（已执行，2026-05-28）

在不启动服务的情况下，对代码结构和引用完整性执行以下检查：

| 检查项 | 方式 | 结果 |
|--------|------|------|
| 7 个新增 Java 文件全部存在 | shell 文件存在性断言 | ✅ 全部命中 |
| 8 个新增前端 API 函数全部导出 | grep export const | ✅ 全部可见 |
| 3 个新增 TypeScript 接口定义 | grep export interface | ✅ 全部可见 |
| `stopSession()` 返回类型变更在 3 处调用点全部兼容 | grep 交叉验证 | ✅ InterpretationFacade、InterpretationController 接收返回值；RealtimeInterpretationFacade 丢弃返回值（合法） |
| `application.yml` 新增 `app.admin`、`app.cost` 配置节点 | yaml grep | ✅ 含 8 个环境变量替换占位符 |
| `InterpretationSessionMapper` 新增月度聚合方法 | grep | ✅ `monthlySummaryByUser`、`currentMonthSummaryByUser` 均存在 |
| `LlmIntegration.extractActionItems()` 存在 | grep | ✅ 含 `ACTION_ITEM_SYSTEM_PROMPT` 常量 |
| `buildStopResult` / 预算警告逻辑 | grep | ✅ `budgetWarning` 在两处阈值判断中写入 map |
| HistoryView 行动项 JSX 块 | grep 行号 | ✅ 909-946 行渲染行动项列表 |

### 7.4 运行时 API 测试（已执行，2026-05-28）

`tests/api_test.py` 针对本地运行后端执行全量测试，**75/75 全部通过**。

| 测试区段 | 覆盖检查项 | 结果 |
|----------|-----------|------|
| [1] 登录回归 | 成功/失败路径 | ✅ 3/3 |
| [2] Bot 指令路由 | help/list/search/summary | ✅ 4/4 |
| [3] SSE 流式端点 | 空消息/未绑定用户/data 行 | ✅ 3/3 |
| [4] 会议 CRUD | 创建/列表/详情 | ✅ 7/7 |
| [5] 文件上传与管理 | 上传/列表/删除 | ✅ 8/8 |
| [6] 发言人摘要 | 生成/字段/摘要长度 | ✅ 5/5 |
| [7] 摘要持久化 | DB 落库/字段匹配 | ✅ 7/7 |
| [8] 参数校验 | 缺 title/缺 text → 400 | ✅ 2/2 |
| [9] 成本费率 API（V-4） | 7 字段存在 + 数值 > 0 | ✅ 10/10 |
| [10] 月度汇总 API（V-5） | list 类型/字段/格式 | ✅ 6/6 |
| [11] Admin 接口鉴权（V-7） | 无 secret → 200；有 secret → 401 | ✅ 3/3 |
| [12] 行动项 AI 提取（V-3/N-11）| 创建会话→插入记录→提取→非空 list | ✅ 10/10 |
| [13] 行动项 CRUD（V-6）| 查询/状态切换/删除 | ✅ 7/7 |

关于 V-1（rebuild 空库）和 V-2（embedding 异步写入）：V-1 由 [11] 中的 Admin 接口测试覆盖（`created` 字段存在且为整数）；V-2 需查询 MySQL 表，属于数据库层验证，列入人工测试 N-6。

---

## 八、需人工测试的功能（完整清单）

### 8.1 本次新增功能（2026-05-28）

| # | 测试项 | 操作步骤 | 预期结果 |
|---|--------|---------|---------|
| N-1 | 历史记录设置提前显示 | 打开历史记录，**不选择**任何会话 | "发言摘要"和"会议总结"的摘要要求输入框、接收人选择器已显示，可以填写 |
| N-2 | 设置持久化（刷新不丢失） | 填写摘要要求 → 刷新页面 | 上次填写的要求仍在输入框中 |
| N-3 | 发送到会议聊天复选框 | 进入"会议总结"Tab | 默认不勾选；勾选后刷新页面仍保持勾选；发送摘要时按此状态决定是否推送到聊天 |
| N-4 | 发言人防抖（30字阈值） | 同传中模拟声纹误识别：说几个字停顿，语音归属在两人间跳动 | 短于30字的候选新发言人不触发切换，内容归并到上一发言人；只有持续说话30字以上才算真正换人 |
| N-5 | 向量 RAG — 冷启动回填 | 启动后端 → 调用 `POST /api/admin/embeddings/rebuild?batchLimit=200` | 返回 `{"created": N}`，N = 已有历史记录数量（首次可能为 0 若无历史数据） |
| N-6 | 向量 RAG — 实时嵌入 | 开始同传说一段话 → 停止 → 等待约 5 秒 → 查询 MySQL `interpretation_embedding` 表 | 表中出现新行，`embedding` 字段为 BLOB（≈6144字节 = 1536×4） |
| N-7 | 向量 RAG — 跨会议问答 | 有两场以上历史同传后，在"Teams Bot 问答"提问与历史内容相关的问题 | 回答内容语义相关（不只是关键词匹配）；`contextSummary` 显示"语义检索了 X 场会议" |
| N-8 | 向量 RAG — 无 API Key 时主流程不受影响 | 清空 `OPENAI_API_KEY` 环境变量 → 进行同传 | 同传、翻译、TTS 正常工作；后端日志出现 warn "asyncEmbed failed"；embedding 表无新增记录 |

### 8.2 本次新增功能（2026-05-28 第二批）

| # | 测试项 | 操作步骤 | 预期结果 |
|---|--------|---------|---------|
| N-9 | 向量 RAG 上下文丰富（C1-C4）| Teams Bot 提问历史会议内容 | 回答中出现 `[来源：会议标题·日期]` 引用标注 |
| N-10 | Teams Bot 发言人过滤（B2/B3）| 提问"张三说了什么" | 检索结果只含张三的发言；无该发言人时提示无相关内容 |
| N-11 | 行动项 AI 提取（A4-A5）| 历史记录 → 会议总结 Tab → 点击"AI 提取行动项" | 1-2秒后出现行动项列表，每条含内容，有负责人时显示蓝色标注 |
| N-12 | 行动项状态切换（A6）| 点击行动项左侧圆形按钮 | 切换为"完成"状态，条目变绿并显示删除线；再次点击恢复"待办" |
| N-13 | 行动项持久化（A5）| 提取行动项 → 刷新页面 → 重新选择同一会话 | 行动项自动加载，无需重新提取 |
| N-14 | 成本分析费率 API（S3）| 浏览器访问 `GET /api/cost/rates` | 返回 JSON 含 `asrPerMs`, `transPerChar` 等字段 |
| N-15 | 成本分析月度汇总（S8）| 成本分析页 → 点击"月度汇总"Tab | 表格显示每月会话数和估计费用；点击"导出 CSV"下载文件 |
| N-16 | 月度预算进度条（S12）| 在 application.yml 设置 `app.cost.budget.monthly-usd: 10` 并重启 | 成本分析页顶部显示蓝色进度条；超出预算时变红 |
| N-17 | 停止会话预算警告（S10/S11）| 设置 `app.cost.budget.session-usd: 0.001`（极低阈值方便测试）→ 进行一段同传 → 停止 | 停止后约 300ms 弹出"⚠️ 预算提醒：本次会话费用..."警告框 |
| N-18 | Admin 接口鉴权（P2）| 未设 `ADMIN_API_SECRET` 时调用 `POST /api/admin/embeddings/rebuild` | 返回成功（未保护模式，日志有 warn）；设置后不带 `X-Admin-Secret` 头返回 401 |

### 8.3 Bot 全数据接入（2026-05-28 第三批）

**本次改动目标：** Bot 可查询系统内一切已有信息；LLM 不自行编造答案，引用内容注明来源，AI 推断内容标注 `【AI补充】`。

**数据接入现状：**

| 数据类型 | Bot 能查到？ | 接入方式 |
|---------|-----------|---------|
| 同传文本记录 | ✅ | 向量 RAG（`interpretation_result`） |
| 会议总结（AI 纪要）| ✅ | `appendMeetingSummary` 追加到上下文 |
| 发言摘要 | ✅ | `appendSpeakerSummaries` 追加到上下文 |
| 会前文件摘要（持久化）| ✅ | `appendFileSummaries` 追加到上下文 |
| 行动项 | ✅（新增）| `buildActionItemsContext` 关键词触发 |
| 成本数据（月度/当月）| ✅（新增）| `buildCostContext` 关键词触发 |

**新增代码：**
- `MeetingActionItemMapper.findRecentByUserId()` — 查询用户最近 30 条行动项
- `TeamsBotQueryService.buildActionItemsContext()` — 问题含"行动项/待办/任务/跟进"时追加行动项清单
- `TeamsBotQueryService.buildCostContext()` — 问题含"成本/费用/预算/花费"时追加月度成本摘要
- `LlmIntegration.CROSS_MEETING_SYSTEM_PROMPT` 强化：
  - 引用内容标注来源格式：`（来源：会议名称·日期）`
  - 资料中没有的推断/解释内容前加 `【AI补充】` 标注
  - 资料中确实没有的信息直接说明【资料中未记录】，不用自己知识填充

**需人工测试：**

| # | 测试项 | 操作步骤 | 预期结果 |
|---|--------|---------|---------|
| N-19 | Bot 查行动项 | 向 Bot 提问"有什么待办任务？" | 返回行动项清单，每条含状态标记和内容 |
| N-20 | Bot 查成本 | 向 Bot 提问"本月花了多少钱？" | 返回当月会话数和估计费用（$X.XXXX） |
| N-21 | 来源标注 | 向 Bot 提问与多场会议相关的内容 | 回答中出现 `（来源：会议名称·日期）` 标注 |
| N-22 | AI 推断标注 | 问一个需要背景解释但资料中没有的问题 | LLM 背景解释前出现 `【AI补充】` 标注 |
| N-23 | 无记录情况 | 问一个完全不在历史中的问题 | 返回"【资料中未记录】"，不编造答案 |

### 8.4 永久分享链接（2026-05-29）

| # | 测试项 | 操作步骤 | 预期结果 |
|---|--------|---------|---------|
| N-24 | 永久 URL 格式 | 点击"复制分享链接"按钮 | 剪贴板内容为 `…#/share/user/{userId}`（固定格式，不含 sessionId） |
| N-25 | 等待状态 | 用无痕窗口打开永久 URL，此时无正在进行的同传 | 页面显示"等待同传开始..."，指示灯为灰色 |
| N-26 | 自动接入 | 保持上一步页面不动，开始一次新同传 | 5 秒内页面自动切换为绿色指示灯，开始显示实时同传文本，无需刷新页面 |
| N-27 | 新会话自动清屏 | 同传结束后等待 10 秒，再开始一次新同传 | 页面自动清空旧内容并接入新会话文本 |
| N-28 | 无同传时等待 | 同传结束后等待超过 10 秒（不开启新会话） | 页面保留旧内容显示（不清空），等待下次同传接入 |

### 8.5 存量功能回归（2026-05-25，本次未改动）

（原文第四节 M-1 至 M-23 的清单，状态不变，此处不重复）

---

## 九、2026-05-29 新增功能（本次迭代）

### 9.1 Teams Bot 自然语言问答升级（RAG + 来源引用）

将 Bot 默认的自由提问从"关键词搜索"升级为完整的检索增强问答：优先识别结构化意图（查文件、按日期查会议），其余走向量 RAG 并随答案附带可追溯的引用来源。

**意图路由变化**（`TeamsBotQueryService`）：
- 默认指令从 `search` 改为新的 `ask`（自然语言问答）
- 新增结构化意图识别：
  - `isFileListIntent` — "某会议有哪些文件/资料"→ 直接列出该会议持久化文件清单
  - `isMeetingListByDateIntent` — "X月X日有哪些会议"→ 按日期列出会议及文件数
- 非结构化问题：向量 RAG 检索上下文；命中为空时回退到 `buildMeetingKnowledgeContext`（按标题/日期为问题打分排序会议，取会议总结 + 文件总结/内容作为上下文）
- 问题日期解析 `extractQuestionDate` 支持 `2026年05月26日` / `2026-05-26` / `5月26日` 三种格式；`rankMeetingsByQuestion` 按日期(+12) 和标题 token 命中(+2/+4) 打分

**来源引用**（新增 `vo/TeamsBotQuerySourceVo`）：
- 响应体 `TeamsBotQueryResponse` 新增 `responseType`（`text`/`rag`/`file_list`/`meeting_list`）与 `sources` 列表
- 同步/流式两条路径均在答案末尾追加"引用来源：…"区块（`formatSourcesBlock` / `formatSourcesSuffix`），含来源名称、所属会议、日期、片段摘要

### 9.2 向量检索跨源扩展

`VectorSearchService.SearchResult` 新增 `sourceType`、`sourceId`、`refId`、`meetingId` 字段，使检索结果可区分同传片段、会议总结、发言摘要、文件总结、文件内容等多种来源，并据此分组/标题/构造引用（`PreMeetingService.buildUnifiedContextResult` 返回 `UnifiedContextResult(context, sources)`）。

### 9.3 嵌入查询支持会议直挂数据 + 排序键

`InterpretationEmbeddingMapper.xml` 的 `findByUserId`：
- 由 `JOIN interpretation_session` 改为 **LEFT JOIN session + LEFT JOIN meeting**，按 `s.user_id OR m.user_id` 过滤，使未绑定会话、仅挂在 `meeting` 上的嵌入（文件/会议总结）也能被检索
- 删除条件改为兼容两侧（session 或 meeting 任一未删）
- 加 `COLLATE utf8mb4_unicode_ci` 解决 join/like 字符集不一致报错
- 时间过滤改用 `COALESCE(s.start_time, m.scheduled_time, m.create_time)`

### 9.4 删除会议同步清理向量

`MeetingService.deleteMeeting` 软删后调用 `contentEmbeddingService.deleteByMeetingId(meetingId)`，避免已删会议内容仍被 RAG 检索召回。

### 9.5 其他

- **LLM 错误透传**（`LlmIntegration`）：`chat/completions` 非 200 时解析 provider 返回体中的 `error.message` 并拼入异常信息，便于定位（密钥失效、额度等）。
- **前端结果守卫**（`si-frontend/src/api/index.ts`）：新增 `ensureResultData`，`summarizePreMeetingFile` 在 `code != 200` 或 `data` 为空时抛出可读错误，不再静默返回空数据。
- **start-all.bat 关停修复**：先 `docker rm -f si-backend` 通过 Docker 停后端，端口兜底清理移除 8080，避免误杀 Docker Desktop 的端口代理（`com.docker.backend`）。

#### 变更文件

| 文件 | 变更 |
|------|------|
| `vo/TeamsBotQuerySourceVo.java` | 新增 — Bot 答案引用来源 VO |
| `vo/TeamsBotQueryResponse.java` | 新增 `responseType`、`sources` |
| `service/TeamsBotQueryService.java` | `ask` 路由、结构化意图、会议打分、来源区块（+437 行） |
| `service/PreMeetingService.java` | `buildUnifiedContextResult` 返回上下文 + 来源；跨源分组/标题 |
| `service/VectorSearchService.java` | `SearchResult` 增加 sourceType/sourceId/refId/meetingId |
| `service/MeetingService.java` | 删除会议时同步删除嵌入 |
| `mapper/InterpretationEmbeddingMapper.xml` | session+meeting LEFT JOIN、collation、时间排序键 |
| `integration/LlmIntegration.java` | LLM 失败信息透传 provider error message |
| `si-frontend/src/api/index.ts` | `ensureResultData` 结果守卫 |
| `start-all.bat` | Docker 优先停后端，端口兜底移除 8080 |

#### 需人工测试

| # | 测试项 | 操作步骤 | 预期结果 |
|---|--------|---------|---------|
| N-29 | Bot 自然语言问答 | 向 Bot 提一个跨会议的开放问题 | 返回 RAG 答案，末尾出现"引用来源："清单 |
| N-30 | 按会议查文件 | 提问"XX 会议有哪些文件" | 列出该会议持久化文件，标注是否已有 AI 总结 |
| N-31 | 按日期查会议 | 提问"2026年05月26日有哪些会议" | 列出当天会议及各自文件数 |
| N-32 | 文件/总结召回 | 对仅挂在 meeting 上的文件内容提问 | 能检索到（LEFT JOIN meeting 生效） |
| N-33 | 删除会议后检索 | 删除一场会议后再问其内容 | 不再召回该会议内容 |
| N-34 | LLM 错误提示 | 临时使用无效 OPENAI_API_KEY 提问 | 错误信息含 provider 返回的 message |

---

## 十、2026-06-02 LLM 供应商迁移 + 发言人抗干扰 + RAG 增强（P0）

### 10.1 发言人识别抗干扰（说话中途偶发认错）
- **姓名路径触发修复 + 去抖**（[InterpretationView.tsx](../si-frontend/src/views/InterpretationView.tsx)）：原"基于姓名"的发言摘要触发是死代码（被 `!messageSpeakerId` 拦截）；改为当 id 缺失或为 `Unknown` 时启用，并加 `MIN_NAME_CHANGE_CHARS=30` 候选去抖——换人需持续到阈值才确认，短促误判折回当前发言人；会话起止清空缓冲，避免跨会话污染。
- **声纹迟滞 hysteresis**（[SpeakerIdentityService](../si-backend/src/main/java/com/si/backend/service/SpeakerIdentityService.java)）：每条流维护"当前已确认说话人+分数"，候选与当前不同时需分数 ≥ `switchScore`（默认 0.45，`SPEAKER_SERVICE_SWITCH_SCORE`）才换人，低置信差异判为误识别、保持当前。
- **Unknown 段继承上一位**：Azure 实时分轨约 40% 句返回 `speakerId=Unknown`（日志实测 Guest-1×14 / Unknown×9），导致这些句无人名。改为：Unknown 段先跑声纹（用最近音频），声纹也认不出时**继承本会话上一位已确认说话人**（`source=INHERITED`），不再留空。

### 10.2 会议参会人 / 场地自动入 ASR 热词
- 上传会议安排（[PreMeetingController.upload](../si-backend/src/main/java/com/si/backend/controller/PreMeetingController.java) 异步块）→ 解析参会人姓名 + 会议地点写入热词（`MEETING_AGENDA`）。
- Teams Bot 拉取参会人（[TeamsBotView.persistKnownParticipants](../si-frontend/src/views/TeamsBotView.tsx)）→ `POST /api/asr-hotwords/from-meeting`（`TEAMS_MEETING`）。
- 服务端去重（`AsrHotwordService.saveMeetingEntities`，`language=null` 全语种生效、weight=2.0）；新增 DTO `MeetingHotwordsRequest`。

### 10.3 LLM 供应商迁移：DashScope/Qwen → OpenRouter（删除 DashScope）
- **聊天**全部走 OpenRouter：压缩 `anthropic/claude-haiku-4.5`（按段调用、便宜快），纪要/文件总结/跨会议问答 `anthropic/claude-sonnet-4.5`。
- **Embedding** 也走 OpenRouter：`openai/text-embedding-3-small`（**1536 维**）。代码新增独立 embedding 端点支持（[OpenAiProperties](../si-backend/src/main/java/com/si/backend/config/OpenAiProperties.java) 的 `embeddingBaseUrl`/`embeddingApiKey` + `effective*` 回退；[LlmIntegration.embed](../si-backend/src/main/java/com/si/backend/integration/LlmIntegration.java) 使用），留空即复用聊天端点。
- 项目自身代码已无 DashScope 残留；密钥仅存于 gitignored `si-backend/.env`。
- **Bug 修复**：`anthropic/claude-3.5-sonnet` 在 OpenRouter 已下线（404 No endpoints），会导致**所有聊天**500；改用经 `/models` 接口核实可用的 `claude-sonnet-4.5`。

### 10.4 向量库重建（embedding 维度 1024→1536）
- 换 embedding 模型后维度变化，旧向量不兼容 → 清空 `interpretation_embedding`（原 992 条 DashScope 1024 维）后用 `POST /api/admin/embeddings/rebuild-all` 全量重嵌。

### 10.5 AI 问答检索增强（P0，config-gated 默认关）
- 新增 [RagEnhancementService](../si-backend/src/main/java/com/si/backend/service/RagEnhancementService.java)：
  - **多查询改写**：将问题扩成若干互补检索 query，分别 embed 后合并去重召回。
  - **LLM 重排序**：召回后由 LLM 选出最相关 top-K（`rag-rerank-top-k`，默认 12）再喂答案。
  - 两者 **fail-open**：LLM/解析失败回退原查询/原顺序，检索不中断。
- 集成在 [buildUnifiedContextResult](../si-backend/src/main/java/com/si/backend/service/PreMeetingService.java)：`expandQueries → multiQueryRecall(合并去重) → rerank`。
- 开关：`OPENAI_RAG_QUERY_EXPANSION_ENABLED`、`OPENAI_RAG_RERANK_ENABLED`、`OPENAI_RAG_RERANK_TOP_K`、`OPENAI_RAG_HELPER_MODEL`。

### 10.6 测试结果（2026-06-02）

**编译/单测**
- 后端 `mvn -o test`：**BUILD SUCCESS**，`Tests run: 12, Failures: 0, Errors: 0`（VectorSearchServiceTest）。
- 前端 `tsc --noEmit`：**exit 0**。

**模型可用性（实测 OpenRouter）**
| 模型 | 用途 | 结果 |
|------|------|------|
| `anthropic/claude-sonnet-4.5` | 纪要/总结/问答 | HTTP 200（Amazon Bedrock） |
| `anthropic/claude-haiku-4.5` | 实时压缩 | HTTP 200（~$0.00003/次） |
| `openai/text-embedding-3-small` | 向量 | HTTP 200，dims=1536 |

**向量库重建校验（DB）**
- `rows=1456`，`dims=1536`，来源分布：file_content 1017 / result 300 / speaker_summary 57 / meeting_summary 38 / file_summary 44。

**P0 线上验证**（`POST /api/teams-bot/query`，问"卫星需要具备哪些关键能力"）
- 日志：`expandQueries done, original=1, total=3`；`rerank done, in=9, out=9`；`buildUnifiedContext done, sessions=5`。
- 返回：结构化、**带 8 条引用来源**的答案（引用格式 `（来源：会议名·日期）`），responseType=`rag`。

**需人工测试**
| # | 测试项 | 操作 | 预期 |
|---|--------|------|------|
| N-35 | 发言人抗干扰 | 两人对话、音色接近 | 中途偶发误判被去抖/迟滞挡掉，不乱切发言人 |
| N-36 | Unknown 不丢名 | 连续同一人发言出现 Unknown 段 | 该段继承上一位说话人，不再空名 |
| N-37 | 参会人入热词 | 上传会议安排 / Teams 拉取参会人 | 术语-热词页出现"参会人员/会议场地"词条 |
| N-38 | P0 召回/重排 | 开 RAG 开关后跨会议提问 | 日志见 expandQueries/rerank，答案更准、带引用 |
| N-39 | 压缩成本 | 实时同传压缩 | 日志压缩调用模型为 `claude-haiku-4.5` |

---

## 十一、2026-06-03 模型配置 + 实时调优 + Teams Bot 会前体验

### 11.1 LLM 模型
- 聊天默认写进代码（[OpenAiProperties](../si-backend/src/main/java/com/si/backend/config/OpenAiProperties.java) + application.yml）：压缩 `anthropic/claude-haiku-4.5`、纪要/总结/问答/RAG `deepseek/deepseek-v4-pro`、embedding `openai/text-embedding-3-small`。实际模型仍可被 `.env` 覆盖（运行时配置不入 git）。
- 修复历史坑：`anthropic/claude-3.5-sonnet` 已从 OpenRouter 下线（404）；改用经 `/models` 核实可用的 `claude-sonnet-4.5` / `deepseek-v4-*`。

### 11.2 说话人识别准确率（接 10.x）
- **MIN_SCORE 0.25 → 0.4**（Python 默认 + start.bat/sh + Java application.yml/Properties/.env.example 全部一致）：挡住"对谁都 ~0.3"的垃圾匹配，宁可显示 Guest-N 也不认错。
- **Top-2 间隔拒判**：speaker-service `/identify` 返回次高分 + margin；后端在迟滞里加"最高分和第二名差距 < `SPEAKER_SERVICE_MARGIN_THRESHOLD`(0.06) 就不换人"（专治音色相近）。
- **Azure 中间结果分轨**：开 `SpeechServiceResponse_DiarizeIntermediateResults`（`AZURE_ASR_DIARIZE_INTERMEDIATE_RESULTS`，默认 true），让强制分段也带 speakerId，从源头减少 ~40% 的 Unknown。
- **识别窗口 4s→8s、自动克隆样本 8s→20s、Cartesia clone `enhance` 可配**（`CARTESIA_CLONE_ENHANCE`）。
- 诊断结论（日志实证）：翻译"不准"根因是 **ASR 语种误判**（中文被判成 en/id → 原文即乱码），Google 翻译只是忠实翻译乱码；压缩/翻译本身没问题。

### 11.3 实时延迟调优
- `AZURE_ASR_END_SILENCE_TIMEOUT_MS` 1200 → **600**（停顿更早定稿，译文/TTS 更早出）。
- `OPENAI_COMPRESSION_MIN_TEXT_LENGTH` 80 → **50**（更短的中文句也压缩）。
- 压缩触发条件确认：**仅 源语言=zh-CN 且 目标=id/en 且 中文原文 ≥ 50 字** 才触发。

### 11.4 Teams Bot 会前体验
- **AI 总结导出 Word**（[buildExportDocx](../si-backend/src/main/java/com/si/backend/service/PreMeetingService.java)）：总结追加在原文件之后；.docx 原件**完整保留原格式**，追加的总结正文改为**继承原文档默认格式**（去掉强制字号），更贴近"和原文一致"。（.doc/.pdf 因 POI 限制只能用提取文本重建。）
- **AI 总结可手动编辑后再导出**（[TeamsBotView](../si-frontend/src/views/TeamsBotView.tsx)）：只读 `<pre>` 改为文本框，导出 Word/PDF 用改后的版本。
- **参会模块重构**：合并原"刷新"和"刷新并生成实际参加情况"为**一个「刷新」**（拉取 Teams 实到 + 有会议安排时直接核对应到/实到/未到）；**去掉参会人员列表**；加入会议后一键出统计 + 明细表；后台自动拉取失败静默不弹错。

### 11.5 测试结果（2026-06-03）
- 后端 `mvn -o test`：**BUILD SUCCESS**，`Tests run: 12, Failures: 0, Errors: 0`。
- 前端 `tsc --noEmit`：**0 错误**。
- 后端 `/actuator/health`：**200**（已部署 Word 格式改动 + 各 .env 调优值，容器内核对一致）。
- 模型可用性实测（OpenRouter `/models` + 实调）：`deepseek/deepseek-v4-pro`、`deepseek/deepseek-v4-flash`、`claude-sonnet-4.5`、`claude-haiku-4.5`、`openai/text-embedding-3-small` 均 HTTP 200。

**需人工测试**
| # | 测试项 | 操作 | 预期 |
|---|--------|------|------|
| N-40 | AI 总结导出 Word | 上传 .docx → 生成总结 → 导出 Word | 原文格式不变、总结追加在后、字体与原文一致 |
| N-41 | 总结可编辑 | 改总结文本框 → 导出 | 导出的是改后的版本 |
| N-42 | 参会一键核对 | 机器人入会 → 点「刷新」 | 直接出应到/实到/未到统计 + 明细，无独立参会人列表 |
| N-43 | 延迟 | 实时同传一段中文 | 停顿 0.6s 即出译文，整体更跟手 |
| N-44 | 声纹抗误判 | 音色相近两人 | 分数接近时不乱切；低于 0.4 显示 Guest-N 不认错 |

---

## 五、已知限制与后续优化方向

| 项目 | 当前状态 | 建议后续 |
|------|---------|---------|
| **向量检索规模** | 每次搜索加载用户最近 2000 条嵌入到内存做余弦计算，适合中小规模（≤10万条可用） | 超大规模可引入 pgvector 或 Milvus 原生 ANN 索引 |
| **嵌入延迟** | 异步后台生成，平均 300-800ms/条（受 OpenAI API 延迟影响） | 可批量预处理减少调用次数 |
| **历史数据回填** | 需手动调用 `/api/admin/embeddings/rebuild`，批量限 200 条/次以防超时 | 可改为定时任务自动补建 |
| **会议文件未纳入向量检索** | 同传记录已向量化，但持久化文件内容（`pre_meeting_file_persistent`）暂未嵌入 | 上传文件时同步 embed 文件摘要/分块 |
| **流式推送节奏** | C# 端每 2 秒更新一次，首次推送延迟略高 | 可调整为字符数阈值触发（如每 50 字更新） |
| **历史消息不传** | 流式接口目前 `history=[]`，无多轮对话上下文 | 在 C# Bot 端维护每用户对话历史，随请求传入 |
