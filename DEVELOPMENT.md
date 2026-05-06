# SyncLingo 开发文档

> 实时同声传译系统 — 开发者参考手册

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [整体架构](#3-整体架构)
4. [目录结构](#4-目录结构)
5. [本地开发环境搭建](#5-本地开发环境搭建)
6. [环境变量参考](#6-环境变量参考)
7. [数据库设计](#7-数据库设计)
8. [后端 REST API](#8-后端-rest-api)
9. [WebSocket 协议](#9-websocket-协议)
10. [音频处理全链路](#10-音频处理全链路)
11. [后端核心模块说明](#11-后端核心模块说明)
12. [前端核心模块说明](#12-前端核心模块说明)
13. [配置项详解](#13-配置项详解)
14. [构建与部署](#14-构建与部署)
15. [日志与监控](#15-日志与监控)
16. [常见问题排查](#16-常见问题排查)

---

## 1. 项目概述

SyncLingo 是一套**实时同声传译**系统，面向中文 ↔ 印尼语的双向同传场景。系统将语音识别（ASR）、机器翻译（MT）、文本压缩（LLM）、语音合成（TTS）串联成一条低延迟流水线，支持用户克隆个人音色，并通过 VoiceMeeter 将合成音频路由到指定输出设备。

**核心功能：**

| 功能 | 描述 |
|------|------|
| 实时 ASR | Azure Speech SDK 连续识别，自动检测语种（zh-CN / id-ID） |
| 双向翻译 | Google Translate + LLM 二次压缩（仅 zh→id 方向） |
| 流式 TTS | Cartesia WebSocket 流式合成，连接池复用 |
| 音色克隆 | 上传 WAV 样本 → Cartesia 克隆 → 自定义音色 |
| 音频路由 | 前端通过 Web Audio API + setSinkId() 路由到 VoiceMeeter |

---

## 2. 技术栈

### 后端

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.2.5 | Web 框架、DI、WebSocket |
| MyBatis | 3.0.3 | ORM / SQL 映射 |
| MySQL | 8.0 | 持久化存储 |
| Azure Speech SDK | 1.48.2 | ASR 识别 |
| OkHttp | 4.12.0 | HTTP / WebSocket 客户端 |
| Commons Pool2 | 2.12.0 | Cartesia WS 连接池 |
| Lombok | 1.18.32 | 代码生成 |

### 前端

| 组件 | 版本 | 用途 |
|------|------|------|
| React | 18.3 | UI 框架 |
| TypeScript | 5.4 | 类型系统 |
| Vite | 5.3 | 构建工具 |
| Axios | 1.7 | HTTP 请求 |
| Zustand | 4.5 | 状态管理 |
| React Router | 6.24 | 前端路由 |
| Web Audio API | 浏览器原生 | 音频采集与播放 |

### 外部服务

| 服务 | 用途 |
|------|------|
| Azure Cognitive Services Speech | ASR 语音识别 |
| Azure Translator（可选） | 文本翻译（备用） |
| Google Cloud Translation | 主力翻译引擎 |
| Cartesia | TTS 语音合成 & 音色克隆 |
| DashScope（阿里云） | LLM 压缩模型（qwen3-max/qwen-turbo） |

---

## 3. 整体架构

### 3.1 分层架构（后端）

```
┌──────────────────────────────────────────────────────────┐
│  前端 (React / TypeScript)                                │
│  AudioCapture → WebSocket → AudioOutput (VoiceMeeter)    │
└────────────────────────┬─────────────────────────────────┘
                         │ WebSocket /ws/asr
                         │ REST /api/**
┌────────────────────────▼─────────────────────────────────┐
│  Controller Layer                                         │
│  InterpretationController | VoiceController | ...        │
├──────────────────────────────────────────────────────────┤
│  Facade Layer (编排层)                                    │
│  RealtimeInterpretationFacade | InterpretationFacade     │
│  VoiceCloneFacade | TranslateFacade                      │
├──────────────────────────────────────────────────────────┤
│  Service Layer (业务逻辑)                                 │
│  AsrService | TtsService | TranslationService            │
│  InterpretationSessionService | VoiceCloneService        │
├──────────────────────────────────────────────────────────┤
│  Integration Layer (外部 API 封装)                        │
│  AzureAsrIntegration | GoogleTranslateIntegration        │
│  CartesiaTtsIntegration | LlmIntegration                 │
├──────────────────────────────────────────────────────────┤
│  Data Layer                                              │
│  MyBatis Mapper → MySQL                                  │
└──────────────────────────────────────────────────────────┘
```

**原则：**
- Controller 只做参数校验和结果包装，不含业务逻辑。
- Facade 协调多个 Service，禁止直接调用 Integration 层。
- Integration 层只封装单一外部 API，不含业务判断。

### 3.2 实时同传流水线

```
麦克风 PCM
    │
    ▼ WebSocket "audio"
AsrWebSocketHandler
    │
    ▼ pushAudio()
AzureAsrIntegration (Azure Speech SDK)
    │ onRecognized (final)
    ▼ TRANSLATION_EXECUTOR
RealtimeInterpretationFacade.processFinalRecognition()
    │
    ├─► TranslationService.translate()
    │       │ 若 zh→id 且长度>40
    │       └─► LlmIntegration.compress()
    │
    ├─► callback: onTranslated → WebSocket "translated"
    │
    └─► TTS_EXECUTOR (串行链 CompletableFuture)
            │
            ▼ TtsService.synthesizeStream()
            Cartesia WebSocket (连接池)
                │ PCM chunk
                ▼ callback: onTtsAudio
            WebSocket "tts_audio" → 前端
                │
                ▼ Web Audio API
            VoiceMeeter 输入设备
```

### 3.3 TTS 串行化机制

**问题：** 多段语音识别结果同时触发 TTS，会导致音频重叠。

**方案：** 每个 session 维护一个 `CompletableFuture` 链（`sessionTtsChain`），新任务链接在前一个任务之后，保证串行播放：

```
认识段1 ──► TTS任务1
认识段2 ──► TTS任务2（等待任务1完成后才开始）
认识段3 ──► TTS任务3（等待任务2完成后才开始）
```

**语言切换：** 检测到语种变化时，递增 `sessionTtsVersion` 版本号，重置 TTS 链，旧任务检测到版本不匹配后自动跳过。

---

## 4. 目录结构

```
syncLingo/
├── si-backend/                          # Spring Boot 后端
│   ├── pom.xml
│   ├── Dockerfile                       # 运行时镜像（Runtime Only）
│   ├── sql/
│   │   └── schema.sql                   # 数据库建表脚本
│   └── src/main/
│       ├── java/com/si/backend/
│       │   ├── SiBackendApplication.java
│       │   ├── common/
│       │   │   ├── Constants.java       # 全局常量（禁止在业务代码中使用魔法值）
│       │   │   ├── ErrorCode.java       # 错误码枚举
│       │   │   ├── BizException.java    # 业务异常
│       │   │   ├── Result.java          # 统一响应包装
│       │   │   └── GlobalExceptionHandler.java
│       │   ├── config/
│       │   │   ├── AzureSpeechProperties.java
│       │   │   ├── CartesiaProperties.java
│       │   │   ├── GoogleTranslateProperties.java
│       │   │   ├── JwtProperties.java
│       │   │   ├── CorsProperties.java
│       │   │   ├── HttpClientConfig.java  # OkHttp 全局 Bean（含重试拦截器）
│       │   │   ├── WebSocketConfig.java
│       │   │   └── WebMvcConfig.java
│       │   ├── controller/
│       │   │   ├── InterpretationController.java
│       │   │   ├── VoiceController.java
│       │   │   ├── TranslateController.java
│       │   │   └── HealthController.java
│       │   ├── dto/                     # 请求 DTO
│       │   ├── vo/                      # 响应 VO
│       │   ├── entity/                  # 数据库实体
│       │   ├── mapper/                  # MyBatis Mapper 接口
│       │   ├── service/
│       │   │   ├── AsrService.java
│       │   │   ├── TtsService.java      # 含 CartesiaWsClient 内部类
│       │   │   ├── TranslationService.java
│       │   │   ├── InterpretationSessionService.java
│       │   │   └── VoiceCloneService.java
│       │   ├── facade/
│       │   │   ├── RealtimeInterpretationFacade.java  # 核心：ASR→翻译→TTS 编排
│       │   │   ├── InterpretationFacade.java
│       │   │   ├── VoiceCloneFacade.java
│       │   │   └── TranslateFacade.java
│       │   ├── integration/
│       │   │   ├── AzureAsrIntegration.java
│       │   │   ├── AzureTranslatorIntegration.java
│       │   │   ├── GoogleTranslateIntegration.java
│       │   │   ├── CartesiaTtsIntegration.java        # 音色克隆 REST API
│       │   │   └── LlmIntegration.java                # DashScope 文本压缩
│       │   └── ws/
│       │       ├── AsrWebSocketHandler.java
│       │       └── JwtHandshakeInterceptor.java
│       └── resources/
│           ├── application.yml
│           ├── application-dev.yml
│           ├── application-prod.yml
│           └── mapper/                  # MyBatis XML 映射文件
│
├── si-frontend/                         # React / TypeScript 前端
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── src/
│       ├── api/
│       │   ├── constants.ts             # 接口路径、WS 配置、音频默认值
│       │   ├── client.ts                # Axios 实例（30s 超时）
│       │   └── index.ts                 # 7 个 API 调用函数
│       ├── lib/
│       │   ├── websocket.ts             # AsrWebSocket 客户端类
│       │   ├── audioCapture.ts          # 麦克风采集 & PCM 编码
│       │   └── audioOutput.ts           # TTS 音频播放 & VoiceMeeter 路由
│       ├── components/                  # 通用 UI 组件
│       ├── views/
│       │   ├── LoginView.tsx
│       │   ├── InterpretationView.tsx   # 主界面
│       │   └── VoiceCloneView.tsx
│       ├── types/index.ts               # TypeScript 接口定义
│       └── constants.ts                 # 应用级常量
│
├── Dockerfile                           # 多阶段构建（Maven + JRE）
├── start-backend.bat                    # 一键构建 & 运行 Docker 容器
├── start-frontend.bat                   # 启动前端开发服务器
├── start-all.bat / stop-all.bat
└── test-api.bat                         # curl 接口测试脚本
```

---

## 5. 本地开发环境搭建

### 5.1 前置条件

| 工具 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 21+ | 推荐 Eclipse Temurin 21 |
| Maven | 3.9+ | 或使用项目内置 mvnw |
| Node.js | 18+ | 含 npm |
| MySQL | 8.0+ | 本地或 Docker |
| Docker | 20+ | 可选，用于容器化部署 |

### 5.2 数据库初始化

```bash
# 1. 创建数据库
mysql -u root -p -e "CREATE DATABASE si_backend CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. 执行建表脚本
mysql -u root -p si_backend < si-backend/sql/schema.sql
```

### 5.3 后端启动

```bash
cd si-backend

# 配置环境变量（参见第 6 节）
export AZURE_SPEECH_KEY=your_key
export AZURE_SPEECH_REGION=southeastasia
export GOOGLE_TRANSLATE_API_KEY=your_key
export CARTESIA_API_KEY=sk_car_...
export DASHSCOPE_API_KEY=sk-...
export JWT_SECRET=your_32_char_secret

# 启动（dev profile，自动使用 application-dev.yml）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 或直接打包后运行
mvn clean package -DskipTests
java -jar target/si-backend-1.0.0.jar --spring.profiles.active=dev
```

后端启动后访问：`http://localhost:8080/health`

### 5.4 前端启动

```bash
cd si-frontend

npm install

# 启动开发服务器（默认端口 5173）
npm run dev
```

访问：`http://localhost:5173`

> **注意：** 前端代理配置在 `vite.config.ts`，开发时 `/api` 和 `/ws` 请求会转发到 `http://localhost:8080`。

---

## 6. 环境变量参考

所有密钥通过环境变量注入，**不得硬编码在代码中**。

### 必填变量

| 变量名 | 示例 | 说明 |
|--------|------|------|
| `AZURE_SPEECH_KEY` | `9M7a...` | Azure 语音服务 API Key |
| `AZURE_SPEECH_REGION` | `southeastasia` | Azure 语音服务区域 |
| `GOOGLE_TRANSLATE_API_KEY` | `AIzaSyC...` | Google 翻译 API Key |
| `CARTESIA_API_KEY` | `sk_car_...` | Cartesia API Key |
| `DASHSCOPE_API_KEY` | `sk-f225...` | 阿里云百炼 API Key |
| `JWT_SECRET` | 随机 32+ 字符串 | JWT 签名密钥 |
| `DB_PASSWORD` | `ysl666` | MySQL 密码 |

### 可选变量（有默认值）

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `APP_PORT` | `8080` | 服务端口 |
| `DB_HOST` | `localhost` | MySQL 主机 |
| `DB_PORT` | `3306` | MySQL 端口 |
| `DB_NAME` | `si_backend` | 数据库名 |
| `DB_USERNAME` | `root` | MySQL 用户名 |
| `AZURE_SPEECH_REGION` | `eastus` | Azure 区域 |
| `AZURE_ASR_LANGUAGES` | `zh-CN,id-ID` | ASR 语种列表 |
| `CARTESIA_API_URL` | `wss://api.cartesia.ai` | Cartesia WebSocket URL |
| `CARTESIA_DEFAULT_VOICE_ID_ZH` | `6eb8965c-...` | 默认中文音色 UUID |
| `CARTESIA_DEFAULT_VOICE_ID_ID` | `a053f6bc-...` | 默认印尼语音色 UUID |
| `COMPRESSION_MODEL` | `qwen3-max` | LLM 压缩模型 |
| `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | DashScope API 地址 |
| `JWT_EXPIRATION_MS` | `86400000` | JWT 有效期（24h） |
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring profile |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | CORS 允许来源 |

---

## 7. 数据库设计

### 7.1 si_user — 用户表

```sql
CREATE TABLE si_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,            -- BCrypt 加密
    nickname     VARCHAR(64),
    email        VARCHAR(128),
    role         VARCHAR(32)  DEFAULT 'user',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 7.2 user_voice — 用户克隆音色表

```sql
CREATE TABLE user_voice (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,          -- FK → si_user.id
    voice_id         VARCHAR(128) NOT NULL,           -- Cartesia 返回的音色 UUID
    voice_name       VARCHAR(128),
    duration_seconds INT,
    sample_url       VARCHAR(512),
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES si_user(id)
);
```

### 7.3 interpretation_session — 同传会话表

```sql
CREATE TABLE interpretation_session (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL UNIQUE,        -- WebSocket session ID
    user_id     BIGINT       NOT NULL,
    source_lang VARCHAR(16),                         -- 源语言，如 zh-CN
    target_lang VARCHAR(16),                         -- 目标语言，如 id
    voice_id    VARCHAR(128),                        -- 使用的 Cartesia 音色
    status      VARCHAR(32)  DEFAULT 'RUNNING',      -- RUNNING / STOPPED / ENDED
    start_time  DATETIME,
    end_time    DATETIME,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES si_user(id)
);
```

---

## 8. 后端 REST API

**统一响应格式：**

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

所有接口返回 HTTP 200，业务错误通过 `code` 字段区分。

### 8.1 同传会话（/api/interpretation）

#### POST /api/interpretation/start

启动同传会话，创建数据库记录。

**Request Body：**
```json
{
  "userId": 1,
  "sourceLang": "zh-CN",
  "targetLang": "id",
  "voiceId": "optional-cartesia-voice-uuid"
}
```

**Response：**
```json
{ "code": 200, "data": "session-uuid-xxx" }
```

---

#### POST /api/interpretation/stop

停止同传会话，更新 status → STOPPED。

**Request Body：**
```json
{ "sessionId": "session-uuid-xxx" }
```

---

#### GET /api/interpretation/status/{sessionId}

查询会话状态。

**Response：**
```json
{
  "code": 200,
  "data": {
    "sessionId": "session-uuid-xxx",
    "sourceLang": "zh-CN",
    "targetLang": "id",
    "voiceId": null,
    "status": "RUNNING",
    "startTime": "2026-01-01T10:00:00",
    "endTime": null
  }
}
```

---

#### GET /api/interpretation/history/{sessionId}

查询历史会话详情（同 status 接口）。

---

### 8.2 语音克隆（/api/voice）

#### POST /api/voice/clone

上传音频样本，克隆音色。

**Request Body：**
```json
{
  "userId": 1,
  "voiceName": "My Voice",
  "audioSample": "base64-encoded-wav-bytes",
  "language": "zh"
}
```

**Response：**
```json
{
  "code": 200,
  "data": {
    "voiceId": "cartesia-voice-uuid",
    "voiceName": "My Voice",
    "durationSeconds": 8,
    "createTime": "2026-01-01T10:00:00"
  }
}
```

---

#### GET /api/voice/{userId}

获取用户已克隆的音色。

#### DELETE /api/voice/{userId}

删除用户音色记录。

---

### 8.3 文本翻译（/api/translate）

#### POST /api/translate

独立文本翻译接口（不触发 TTS）。

**Request Body：**
```json
{
  "text": "今天天气很好",
  "sourceLang": "zh-CN",
  "targetLang": "id"
}
```

**Response：**
```json
{ "code": 200, "data": "Cuaca hari ini sangat bagus" }
```

---

### 8.4 健康检查

#### GET /health

```json
{ "status": "UP" }
```

---

## 9. WebSocket 协议

### 9.1 连接

```
ws://host:8080/ws/asr?token=<JWT>
```

- Token 通过 URL query 参数传递。
- 握手失败返回 HTTP 401。

### 9.2 消息格式

所有消息均为 **JSON 文本帧**，包含 `type` 字段作为消息类型标识。

#### 消息字段说明（WsMessage）

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 消息类型（必填） |
| `sessionId` | String | 会话 ID |
| `sourceLang` | String | 源语言代码 |
| `targetLang` | String | 目标语言代码 |
| `voiceId` | String | Cartesia 音色 UUID |
| `audioBase64` | String | Base64 编码 PCM 音频 |
| `text` | String | 识别或原始文本 |
| `language` | String | 检测到的语种 |
| `translatedText` | String | 翻译结果 |
| `targetLanguage` | String | 目标语言（响应中） |
| `code` | String | 错误码 |
| `message` | String | 错误描述 |

---

### 9.3 客户端 → 服务端消息

#### `start` — 启动识别

```json
{
  "type": "start",
  "sessionId": "uuid",
  "sourceLang": "auto",
  "targetLang": "id",
  "voiceId": "optional-voice-id"
}
```

- `sourceLang` 传 `"auto"` 或 `null` 启用自动语种检测（Azure Continuous LID）。
- 服务端响应 `started` 消息。

---

#### `audio` — 推送音频帧

```json
{
  "type": "audio",
  "sessionId": "uuid",
  "audioBase64": "<base64-pcm>"
}
```

- PCM 格式：16kHz 采样率、16-bit、单声道。
- 每帧约 4096 个采样点（8192 字节）。
- 高频发送（约 100ms 间隔）。

---

#### `stop` — 停止识别

```json
{
  "type": "stop",
  "sessionId": "uuid"
}
```

---

#### `translate_text` — 文本翻译（不触发 TTS）

```json
{
  "type": "translate_text",
  "text": "你好世界",
  "targetLanguage": "id"
}
```

---

### 9.4 服务端 → 客户端消息

#### `started` — 确认已启动

```json
{
  "type": "started",
  "sessionId": "uuid"
}
```

---

#### `recognizing` — 实时识别（中间结果）

```json
{
  "type": "recognizing",
  "sessionId": "uuid",
  "text": "正在识别中...",
  "language": "zh-CN"
}
```

---

#### `recognized` — 最终识别结果

```json
{
  "type": "recognized",
  "sessionId": "uuid",
  "text": "今天天气很好",
  "language": "zh-CN"
}
```

触发后端翻译 + TTS 流水线。

---

#### `translated` — 翻译结果

```json
{
  "type": "translated",
  "sessionId": "uuid",
  "text": "今天天气很好",
  "translatedText": "Cuaca hari ini sangat bagus",
  "targetLanguage": "id"
}
```

---

#### `tts_audio` — TTS 音频块

```json
{
  "type": "tts_audio",
  "sessionId": "uuid",
  "audioBase64": "<base64-pcm>",
  "targetLanguage": "id"
}
```

- PCM 格式：24kHz、16-bit、单声道（与 ASR 输入不同）。
- 多个 chunk 按顺序推送，前端依次播放。

---

#### `stopped` — 确认已停止

```json
{
  "type": "stopped",
  "sessionId": "uuid"
}
```

---

#### `error` — 错误

```json
{
  "type": "error",
  "sessionId": "uuid",
  "code": "ASR_ERROR",
  "message": "Azure Speech: Connection timeout"
}
```

**错误码：**

| code | 场景 |
|------|------|
| `ASR_ERROR` | ASR 识别错误 |
| `PARSE_ERROR` | 消息解析失败 |
| `UNKNOWN_MESSAGE_TYPE` | 未知 type |

---

### 9.5 完整交互时序

```
Client                          Server
  │── connect /ws/asr?token ──►  │
  │◄──────── 101 Upgrade ──────  │
  │── { type: "start" } ───────► │ 创建 ASR session
  │◄─── { type: "started" } ───  │
  │                              │
  │── { type: "audio", ... } ──► │ 推送 PCM
  │── { type: "audio", ... } ──► │
  │◄── { type: "recognizing" }── │ 实时识别
  │◄── { type: "recognizing" }── │
  │◄── { type: "recognized" } ── │ 最终识别
  │◄── { type: "translated" } ── │ 翻译结果
  │◄── { type: "tts_audio" } ─── │ TTS 音频块
  │◄── { type: "tts_audio" } ─── │ ...
  │                              │
  │── { type: "stop" } ────────► │ 停止识别
  │◄──── { type: "stopped" } ──  │
```

---

## 10. 音频处理全链路

### 10.1 音频规格

| 阶段 | 采样率 | 位深 | 声道 | 格式 |
|------|--------|------|------|------|
| 麦克风采集（ASR 输入） | 16,000 Hz | 16-bit signed | 单声道 | PCM |
| TTS 输出（Cartesia） | 24,000 Hz | 16-bit signed | 单声道 | PCM raw |

### 10.2 前端采集流程

```
navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16000 } })
    │
    ▼
AudioContext + ScriptProcessorNode（bufferSize: 4096）
    │ Float32 samples
    ▼ 转换为 Int16（PCM 16-bit）
    │ × 32767
    ▼
Base64 编码
    │
    ▼ WebSocket "audio" 消息
```

### 10.3 后端 ASR 处理

```
handleAudio() 解 Base64 → byte[]
    │
    ▼ pushAudio()
PushAudioInputStream（Azure Speech SDK 内部缓冲）
    │
    ▼ 自动语种检测（AutoDetectSourceLanguageConfig）
SpeechRecognizer（连续识别）
    │
    ├── onRecognizing → "recognizing" WS 消息
    └── onRecognized  → processFinalRecognition()（异步）
```

### 10.4 翻译与压缩

```
GoogleTranslateIntegration.translate()
    │
    │ 仅当：源语言 = zh-CN，目标语言 = id，文本长度 > 40
    ▼
LlmIntegration.compress()（DashScope qwen3-max）
    目标压缩到 50%~60% 原始长度
    删除填充词、重复、弱修饰词
    保留所有事实和关键动作
    │
    ▼ 压缩后文本 → TTS
```

### 10.5 Cartesia TTS 流程

```
TtsService.synthesizeStream(voiceId, text, sampleRate=24000, speed)
    │
    ▼ 从连接池借出 CartesiaWsClient
    │
    ▼ OkHttp WebSocket → wss://api.cartesia.ai/tts/websocket
    │   发送请求：{ type: "tts_request", transcript, voice, output_format, speed }
    │
    ├── onMessage (chunk) → Base64解码 → PCM bytes → onChunk callback
    ├── onMessage (done)  → onComplete callback → 归还连接池
    └── onFailure         → onError callback → 归还连接池
```

**语速设置：**
- 印尼语：`speed = 1.25`（加速 25%）
- 中文：`speed = 1.0`（正常速度）

### 10.6 前端音频播放与路由

```
"tts_audio" 消息（Base64 PCM）
    │
    ▼ Base64解码 → ArrayBuffer
    │
    ▼ AudioContext.decodeAudioData()
    │
    ▼ AudioBufferSourceNode.start()
    │
    ├── 中文 TTS → AudioContext 1 → setSinkId("VoiceMeeter Input")
    └── 印尼语 TTS → AudioContext 2 → setSinkId("VoiceMeeter Aux Input")
```

---

## 11. 后端核心模块说明

### 11.1 Constants.java

**路径：** `common/Constants.java`

所有魔法值必须在此定义，业务代码禁止直接使用字符串或数字字面量。

**关键常量分组：**

| 分组 | 示例常量 |
|------|----------|
| 语言代码 | `LANG_ZH_CN = "zh-CN"`, `LANG_ID_SHORT = "id"`, `LANG_AUTO = "auto"` |
| 音频参数 | `DEFAULT_SAMPLE_RATE_ASR = 16000`, `DEFAULT_SAMPLE_RATE_TTS = 24000` |
| WS 消息类型 | `WS_MSG_TYPE_START`, `WS_MSG_TYPE_TTS_AUDIO`, `WS_MSG_TYPE_ERROR` |
| Cartesia 协议 | `CARTESIA_FIELD_SPEED`, `TTS_SPEED_INDONESIAN = 1.25`, `CARTESIA_VERSION_HEADER` |
| HTTP 状态码 | `HTTP_OK = 200`, `HTTP_BAD_REQUEST = 400` |

---

### 11.2 RealtimeInterpretationFacade.java

**路径：** `facade/RealtimeInterpretationFacade.java`

核心编排类，持有以下会话状态 Map（均为 `ConcurrentHashMap`）：

| Map | Key | Value | 用途 |
|-----|-----|-------|------|
| `sessionTranslatedCallbackMap` | sessionId | `TranslationResultCallback` | 推送译文到前端 |
| `sessionTtsAudioCallbackMap` | sessionId | `TtsAudioCallback` | 推送 PCM 到前端 |
| `sessionErrorCallbackMap` | sessionId | `AsrErrorCallback` | 推送异步错误到前端 |
| `sessionTtsChain` | sessionId | `CompletableFuture<Void>` | TTS 串行化链 |
| `sessionTtsVersion` | sessionId | `AtomicLong` | 语言切换版本号 |
| `sessionLastSourceLang` | sessionId | String | 上次检测到的源语种 |

**线程池：**

```java
// 翻译专用（有界队列 + 背压）
TRANSLATION_EXECUTOR = ThreadPoolExecutor(CPU×2, LinkedBlockingQueue(1000), CallerRunsPolicy)

// TTS 专用（与翻译隔离）
TTS_EXECUTOR = ThreadPoolExecutor(CPU×2, LinkedBlockingQueue(1000), CallerRunsPolicy)
```

---

### 11.3 TtsService.java

**路径：** `service/TtsService.java`

**连接池结构：**

```
voicePools: Map<voiceId, GenericObjectPool<CartesiaWsClient>>
    每个音色 ID 一个独立连接池
    maxTotal = 20，minIdle = 2，maxWait = 5s
```

**CartesiaWsClient 关键说明：**

- 所有实例共享一个静态 `WS_HTTP_CLIENT`（OkHttp 连接池复用）。
- `streamSynthesize()` 是**异步非阻塞**的：`newWebSocket()` 立即返回，音频数据通过回调传递。
- 连接必须在 `onComplete` / `onError` 回调中归还池，**绝不能**在 `finally` 中归还（否则会在异步操作结束前提前释放）。

---

### 11.4 TranslationService.java

**路径：** `service/TranslationService.java`

**翻译压缩逻辑：**

```java
// 满足以下所有条件时触发 LLM 压缩：
sourceLang == "zh-CN"
targetLang == "id"
text.length() > 40   // 过短文本跳过，节省延迟
```

压缩目标：50%~60% 原始长度，仅删除填充词/重复/广告语，保留所有事实。

---

### 11.5 HttpClientConfig.java

**路径：** `config/HttpClientConfig.java`

提供全局共享的 `OkHttpClient` Bean，所有 HTTP 调用（Google Translate、DashScope、Cartesia 克隆 API）共用。

**重试策略：**

- 最多重试 3 次（`HTTP_RETRY_MAX = 3`）
- 指数退避：200ms → 400ms → 800ms
- 仅对 5xx 错误重试
- **仅幂等方法重试**（GET/HEAD/PUT/DELETE/OPTIONS），POST 不重试（防止重复提交）
- IOException 达到最大重试次数时，先关闭 response 再抛出

---

### 11.6 AsrWebSocketHandler.java

**路径：** `ws/AsrWebSocketHandler.java`

**线程安全 sendMessage：**

Spring WebSocket Session 非线程安全，`sendMessage()` 必须持有 `session` 锁，且检查 `isOpen()` 与实际发送在**同一个** `synchronized(session)` 块中（避免 TOCTOU 竞态）：

```java
synchronized (session) {
    if (!session.isOpen()) { return; }
    session.sendMessage(new TextMessage(json));
}
```

---

### 11.7 AzureAsrIntegration.java

**路径：** `integration/AzureAsrIntegration.java`

**recognizeOnce() AtomicBoolean 保护：**

`recognized` 和 `canceled` 事件可能同时触发，通过 `AtomicBoolean done` 确保只处理一次：

```java
AtomicBoolean done = new AtomicBoolean(false);
recognizer.recognized.addEventListener((s, e) -> {
    if (!done.compareAndSet(false, true)) return;
    // 处理结果
    latch.countDown();
});
recognizer.canceled.addEventListener((s, e) -> {
    if (!done.compareAndSet(false, true)) return;
    latch.countDown();
});
```

---

## 12. 前端核心模块说明

### 12.1 AsrWebSocket（lib/websocket.ts）

封装 WebSocket 连接与消息处理。

**关键配置（api/constants.ts）：**

```typescript
MAX_RECONNECT = 10      // 最大重连次数
RECONNECT_DELAY = 2000  // 重连间隔（ms）
```

**使用方式：**

```typescript
const ws = new AsrWebSocket(token, onMessage, onError, onClose);
ws.start(sessionId, sourceLang, targetLang, voiceId);
ws.sendAudio(base64Pcm);
ws.stop(sessionId);
ws.close();
```

---

### 12.2 AudioCapture（lib/audioCapture.ts）

**采集流程：**

1. `getUserMedia({ audio: { sampleRate: 16000, channelCount: 1 } })`
2. `AudioContext.createScriptProcessor(4096, 1, 1)`
3. `onaudioprocess`：Float32 → Int16 → Base64
4. 通过回调传递给 AsrWebSocket

---

### 12.3 InterpretationView.tsx（views/InterpretationView.tsx）

主界面，包含以下核心状态：

| Ref/State | 说明 |
|-----------|------|
| `detectedLangRef` | 当前检测到的语种（ref，避免闭包过期） |
| `pendingSourcesZhRef` | 正在播放的中文 TTS 节点集合（Set） |
| `pendingSourcesIdRef` | 正在播放的印尼语 TTS 节点集合（Set） |
| `zhAudioCtxRef` | 中文音频上下文 |
| `idAudioCtxRef` | 印尼语音频上下文 |

**语言切换时**，立即停止所有 pending 节点并清空集合，防止旧语言音频继续播放。

---

## 13. 配置项详解

### 13.1 application.yml — WebSocket

```yaml
server:
  tomcat:
    websocket:
      max-text-message-size: 10485760   # 10MB，应对大量 Base64 TTS 音频
      max-binary-message-size: 10485760 # 10MB
```

> **重要：** 此值必须与 `WebSocketConfig.java` 中的 `setMaxTextMessageBufferSize` 保持一致。`Constants.WS_MAX_BINARY_MESSAGE_SIZE` 也应同步更新。

---

### 13.2 application.yml — Cartesia 连接池

```yaml
cartesia:
  pool:
    max-total-per-voice: 20    # 每个音色最大连接数
    min-idle-per-voice: 2      # 最小空闲连接（预热）
    max-wait-millis: 5000      # 等待连接超时（ms）
```

**调优建议：**
- 并发会话多时，适当增大 `max-total-per-voice`
- `min-idle-per-voice` 增大可减少首次合成延迟（连接已预热）

---

### 13.3 application.yml — ASR

```yaml
azure:
  speech:
    asr:
      end-silence-timeout-ms: 2500   # 停顿超过 2.5s 触发 final 识别
      language: zh-CN,id-ID          # 自动语种检测候选列表
```

**调优建议：**
- `end-silence-timeout-ms` 降低可提高实时性，但可能导致句子被截断
- 增加 `language` 列表会略微增加 Continuous LID 识别延迟

---

### 13.4 Hikari 连接池

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 300000      # 5 分钟
      connection-timeout: 20000 # 20 秒
      max-lifetime: 1200000     # 20 分钟
```

---

## 14. 构建与部署

### 14.1 本地 Maven 构建

```bash
cd si-backend
mvn clean package -DskipTests
# 产物：target/si-backend-1.0.0.jar
```

### 14.2 Docker 构建（多阶段）

```bash
# 使用根目录 Dockerfile（Maven Build + JRE Runtime）
docker build -t si-backend:latest .

# 或使用 si-backend/Dockerfile（需先 mvn package）
cd si-backend && mvn clean package -DskipTests
docker build -t si-backend:latest -f si-backend/Dockerfile .
```

### 14.3 Docker 运行

```bash
docker run -d \
  --name si-backend \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_HOST=host.docker.internal \
  -e DB_PASSWORD=your_db_password \
  -e AZURE_SPEECH_KEY=your_key \
  -e AZURE_SPEECH_REGION=southeastasia \
  -e GOOGLE_TRANSLATE_API_KEY=your_key \
  -e CARTESIA_API_KEY=sk_car_... \
  -e DASHSCOPE_API_KEY=sk-... \
  -e JWT_SECRET=your_32_char_secret \
  si-backend:latest
```

### 14.4 前端构建

```bash
cd si-frontend
npm run build
# 产物：dist/ 目录，部署到任意静态文件服务器（Nginx、Vite Preview）
```

### 14.5 一键脚本（Windows）

```batch
# 构建并启动后端 Docker 容器
start-backend.bat

# 启动前端开发服务器
start-frontend.bat

# 停止所有服务
stop-all.bat
```

---

## 15. 日志与监控

### 15.1 日志级别

| Profile | root | com.si.backend |
|---------|------|----------------|
| dev | INFO | DEBUG |
| prod | WARN | INFO |

### 15.2 日志文件

```yaml
logging:
  file:
    name: logs/si-backend.log
    max-size: 10MB
    max-history: 7              # 保留 7 天滚动日志
```

### 15.3 关键日志标识

所有日志以类名前缀标识，方便过滤：

```
[RealtimeInterpretationFacade] translateAndStreamTts ...
[TtsService] synthesizeStream start ...
[CartesiaWsClient] TTS synthesis done
[AsrWebSocketHandler] sent tts_audio ...
[TranslationService] compress end, ratio=54.2% ...
```

### 15.4 健康检查

```bash
curl http://localhost:8080/health
# 返回：{ "status": "UP" }
```

---

## 16. 常见问题排查

### Q1: 后端启动时 TTS 连接池预热失败

**现象：** 启动日志出现 `[TtsService] prewarmPool failed`

**原因：** Cartesia API Key 无效或网络不通

**排查：**
1. 检查 `CARTESIA_API_KEY` 环境变量
2. 检查网络是否能访问 `wss://api.cartesia.ai`
3. 预热失败**不影响启动**（异步执行），首次 TTS 会稍慢

---

### Q2: ASR 识别语言错误（中文识别为印尼语）

**原因：** Azure Continuous LID 在语音特征相似时可能误判

**排查：**
1. 检查 `AZURE_ASR_LANGUAGES` 是否包含所需语种
2. 检查音频质量（噪音、采样率是否为 16kHz）
3. 适当降低 `end-silence-timeout-ms`（过长的停顿可能触发误判）

---

### Q3: TTS 音频播放混乱或顺序错误

**原因：** TTS 串行链状态异常（通常发生在 WS 重连后）

**排查：**
1. 检查 `sessionTtsChain` 是否在 `startInterpretation` 时正确重置
2. 检查 `sessionTtsVersion` 是否清零
3. 前端 pending 节点集合是否在 stop 时清空

---

### Q4: WebSocket 消息发送失败（IllegalStateException）

**原因：** 多线程并发发送 WebSocket 消息，Session 非线程安全

**排查：**
1. 确认 `sendMessage()` 方法中所有发送操作都在 `synchronized(session)` 块内
2. 检查 `session.isOpen()` 与 `session.sendMessage()` 是否在同一 synchronized 块

---

### Q5: 翻译延迟过高（> 3s）

**原因：** LLM 压缩（qwen3-max）耗时较长

**排查：**
1. 检查 `COMPRESSION_MODEL` 是否设置为更快的模型（如 `qwen-turbo`）
2. 增大文本压缩阈值（`text.length() > 40`），跳过短文本压缩
3. 检查 DashScope API 响应时间（日志中 `[LlmIntegration] doCall done, costMs=...`）

---

### Q6: 连接池耗尽（TTS 请求等待超时）

**现象：** `[TtsService] synthesizeStream borrow error`，`NoSuchElementException: Timeout waiting for idle object`

**排查：**
1. 增大 `cartesia.pool.max-total-per-voice`（当前 20）
2. 增大 `cartesia.pool.max-wait-millis`（当前 5000ms）
3. 检查 Cartesia WS 是否正常关闭（`onComplete`/`onError` 是否都调用了 `returnClient`）

---

*文档版本：2026-05-06 | 对应代码分支：master*
