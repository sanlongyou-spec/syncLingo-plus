# SI Backend - 同声传译系统后端

## 技术栈

- Java 21 + Spring Boot 3.2.5
- Azure AI Speech SDK（ASR 语音识别 + Azure TTS）
- Google Cloud Translation（主力翻译）+ Azure AI Translator（备用）
- Cartesia WebSocket TTS（sonic 模型，声色克隆）
- OpenAI（LLM 压缩/会议纪要/文件总结 + 向量嵌入）
- Apache POI / PDFBox（会前文件解析与 Word/PDF 导出）
- VoiceMeeter（虚拟音频设备直通）
- MyBatis + MySQL 8
- WebSocket（实时同传）
- speaker-service（独立 Python 声纹识别服务，可选）

## 项目结构

```
si-backend/
├── pom.xml
├── sql/
│   └── schema.sql               # 数据库建表脚本
├── src/main/
│   ├── java/com/si/backend/
│   │   ├── SiBackendApplication.java
│   │   ├── common/              # 统一响应、异常、常量
│   │   ├── config/               # 配置类（Azure/Cartesia/JWT/WebSocket）
│   │   ├── entity/              # 数据库实体
│   │   ├── dto/                 # 入参 DTO
│   │   ├── vo/                  # 出参 VO
│   │   ├── mapper/              # MyBatis Mapper
│   │   ├── integration/         # 外部服务集成（ASR/翻译/TTS/VoiceMeeter）
│   │   ├── service/             # 业务逻辑
│   │   ├── facade/              # 门面层聚合
│   │   ├── controller/          # REST 接口
│   │   └── ws/                  # WebSocket 处理
│   └── resources/
│       ├── application.yml      # 主配置
│       ├── application-dev.yml  # 开发环境
│       └── application-prod.yml # 生产环境
└── .env.example                 # 环境变量示例
```

## 快速开始

### 1. 环境准备

```bash
# 安装 Java 21
# 安装 Maven 3.9+
# 安装 MySQL 8.0+
```

### 2. 数据库初始化

```bash
mysql -u root -p < sql/schema.sql
```

### 3. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入各服务密钥
```

### 4. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/si-backend-1.0.0.jar --spring.profiles.active=dev
```

服务端口：`http://localhost:8080`

## API 接口

### 同声传译

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/interpretation/start` | 开始同传 |
| POST | `/api/interpretation/stop` | 停止同传 |
| GET | `/api/interpretation/status/{sessionId}` | 查询状态 |

### 音色克隆

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/voice/clone` | 克隆音色 |
| GET | `/api/voice/{userId}` | 获取用户音色 |
| DELETE | `/api/voice/{userId}` | 删除音色 |

### 翻译

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/translate` | 文本翻译 |

> 以上为同传内核接口。平台另含会议（`/api/meeting`、`/api/meeting-material`、`/api/meeting-summary`）、会前准备（`/api/pre-meeting`）、成本分析（`/api/cost`）、术语表（`/api/terminology`）、ASR 热词（`/api/asr-hotword`）、用户语言偏好、Teams Bot 查询（`/api/teams-bot/query`、`/query/stream`）、Bot 代理（`/api/bot`）等接口，完整列表以各 `*Controller` 为准。

### WebSocket

**路径：** `/ws/asr?token=<jwt>`

**客户端 → 服务端：**

```json
{ "type": "start", "sessionId": "uuid", "sourceLang": "auto", "targetLang": "id" }
{ "type": "audio", "sessionId": "uuid", "audioBase64": "base64_pcm" }
{ "type": "stop", "sessionId": "uuid" }
```

**服务端 → 客户端：**

```json
{ "type": "recognizing", "sessionId": "uuid", "text": "正在识别..." }
{ "type": "recognized", "sessionId": "uuid", "text": "..." }
{ "type": "translated", "sessionId": "uuid", "text": "...", "translatedText": "...", "targetLanguage": "id" }
{ "type": "tts_audio", "sessionId": "uuid", "audioBase64": "base64_pcm", "targetLanguage": "id" }
{ "type": "error", "code": "ASR_ERROR", "message": "..." }
```

### 管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/embeddings/rebuild?batchLimit=200` | 批量补建历史同传记录的语义嵌入（首次部署后执行一次） |

## 核心配置说明

| 配置项 | 说明 |
|--------|------|
| `azure.speech.key` | Azure 语音服务密钥（ASR+TTS） |
| `azure.translator.key` | Azure 翻译服务密钥 |
| `cartesia.api-key` | Cartesia API 密钥（声色克隆 TTS） |
| `voicemeeter.enabled` | 启用 VoiceMeeter 音频直通 |
| `openai.api-key` | OpenAI API 密钥（LLM 摘要 + 向量嵌入） |
| `openai.embedding-model` | 嵌入模型，默认 `text-embedding-3-small` |
| `openai.embedding-top-k` | 向量检索返回条数上限，默认 20 |
| `openai.embedding-min-score` | 余弦相似度最低阈值，默认 0.3 |
