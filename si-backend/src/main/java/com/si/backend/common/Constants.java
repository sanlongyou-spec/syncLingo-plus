package com.si.backend.common;

/**
 * 全局常量类，禁止在业务代码中使用魔法值。
 *
 * <p>分类：
 * <ul>
 *   <li>语言代码（Language）</li>
 *   <li>音频参数（Audio）</li>
 *   <li>WebSocket 协议（WS）</li>
 *   <li>会话状态（Session）</li>
 *   <li>连接池配置（Pool）</li>
 *   <li>Cartesia TTS</li>
 *   <li>Google Translate</li>
 *   <li>Azure ASR</li>
 *   <li>WebSocket 消息类型（WS Message Type）</li>
 *   <li>HTTP 状态码（HTTP）</li>
 * </ul>
 */
public final class Constants {

    private Constants() {}

    // ═══════════════════════════════════════════════════════════
    // 语言代码
    // ═══════════════════════════════════════════════════════════

    /** 简体中文 */
    public static final String LANG_ZH_CN = "zh-CN";
    /** 印尼语（BCP-47） */
    public static final String LANG_ID = "id-ID";
    /** 印尼语（短码） */
    public static final String LANG_ID_SHORT = "id";
    /** 印尼语（ISO-639-1 旧码） */
    public static final String LANG_ID_ISO6391 = "in";
    /** 未检测到语种 */
    public static final String LANG_UNDEFINED = "und";
    /** 自动检测语种 */
    public static final String LANG_AUTO = "auto";
    /** 印尼语语音克隆默认语言代码（Cartesia） */
    public static final String LANG_CLONE_ID = "id";
    /** 中文语音克隆默认语言代码（Cartesia） */
    public static final String LANG_CLONE_ZH = "zh";

    // ═══════════════════════════════════════════════════════════
    // 音频参数
    // ═══════════════════════════════════════════════════════════

    /** ASR 默认采样率 */
    public static final int DEFAULT_SAMPLE_RATE_ASR = 16000;
    /** TTS 默认采样率 */
    public static final int DEFAULT_SAMPLE_RATE_TTS = 24000;
    /** 单声道 */
    public static final int AUDIO_CHANNELS_MONO = 1;
    /** 采样位数 */
    public static final int BITS_PER_SAMPLE = 16;
    /** PCM MIME 类型 */
    public static final String AUDIO_FORMAT_PCM = "audio/pcm";
    /** 最小音频样本字节数（0.5s @ 16kHz, 16-bit, mono） */
    public static final int MIN_AUDIO_SAMPLE_BYTES = 8000;

    // ═══════════════════════════════════════════════════════════
    // WebSocket
    // ═══════════════════════════════════════════════════════════

    /** WebSocket ASR 路径 */
    public static final String WS_PATH_ASR = "/ws/asr";
    /** WebSocket 最大文本消息大小（64KB） */
    public static final int WS_MAX_TEXT_MESSAGE_SIZE = 1024 * 64;
    /** WebSocket 最大二进制消息大小（1MB） */
    public static final int WS_MAX_BINARY_MESSAGE_SIZE = 1024 * 1024;
    /** URL Query 参数中的 Token key */
    public static final String WS_QUERY_PARAM_TOKEN = "token";

    // ═══════════════════════════════════════════════════════════
    // WebSocket 消息类型（前后端共用）
    // ═══════════════════════════════════════════════════════════

    public static final String WS_MSG_TYPE_START = "start";
    public static final String WS_MSG_TYPE_STOP = "stop";
    public static final String WS_MSG_TYPE_AUDIO = "audio";
    public static final String WS_MSG_TYPE_RECOGNIZING = "recognizing";
    public static final String WS_MSG_TYPE_RECOGNIZED = "recognized";
    public static final String WS_MSG_TYPE_TRANSLATED = "translated";
    public static final String WS_MSG_TYPE_STARTED = "started";
    public static final String WS_MSG_TYPE_STOPPED = "stopped";
    public static final String WS_MSG_TYPE_ERROR = "error";
    public static final String WS_MSG_TYPE_TRANSLATE_TEXT = "translate_text";
    public static final String WS_MSG_TYPE_TTS_AUDIO = "tts_audio";

    // ═══════════════════════════════════════════════════════════
    // WebSocket 错误码
    // ═══════════════════════════════════════════════════════════

    public static final String WS_ERROR_UNKNOWN_MESSAGE_TYPE = "UNKNOWN_MESSAGE_TYPE";
    public static final String WS_ERROR_PARSE_ERROR = "PARSE_ERROR";
    public static final String WS_ERROR_ASR_ERROR = "ASR_ERROR";

    // ═══════════════════════════════════════════════════════════
    // Cartesia TTS WebSocket
    // ═══════════════════════════════════════════════════════════

    /** Cartesia TTS WebSocket JSON 字段：type（消息类型字段名） */
    public static final String CARTESIA_FIELD_TYPE = "type";
    /** Cartesia TTS WebSocket 消息类型：TTS 请求 */
    public static final String CARTESIA_MSG_TYPE_TTS_REQUEST = "tts_request";
    /** Cartesia TTS WebSocket 消息类型：chunk（音频分块） */
    public static final String CARTESIA_MSG_TYPE_CHUNK = "chunk";
    /** Cartesia TTS WebSocket 消息类型：error（错误） */
    public static final String CARTESIA_MSG_TYPE_ERROR = "error";
    /** Cartesia TTS WebSocket 消息类型：done（合成完成，独立消息而非 chunk.done=true） */
    public static final String CARTESIA_MSG_TYPE_DONE = "done";
    /** Cartesia TTS WebSocket JSON 字段：model_id */
    public static final String CARTESIA_FIELD_MODEL_ID = "model_id";
    /** Cartesia TTS WebSocket JSON 字段：transcript */
    public static final String CARTESIA_FIELD_TRANSCRIPT = "transcript";
    /** Cartesia TTS WebSocket JSON 字段：voice */
    public static final String CARTESIA_FIELD_VOICE = "voice";
    /** Cartesia TTS WebSocket JSON 字段：output_format */
    public static final String CARTESIA_FIELD_OUTPUT_FORMAT = "output_format";
    /** Cartesia TTS WebSocket JSON 字段：container */
    public static final String CARTESIA_FIELD_CONTAINER = "container";
    /** Cartesia TTS WebSocket JSON 字段：encoding */
    public static final String CARTESIA_FIELD_ENCODING = "encoding";
    /** Cartesia TTS WebSocket JSON 字段：sample_rate */
    public static final String CARTESIA_FIELD_SAMPLE_RATE = "sample_rate";
    /** Cartesia TTS WebSocket JSON 字段：context_id */
    public static final String CARTESIA_FIELD_CONTEXT_ID = "context_id";
    /** Cartesia TTS WebSocket JSON 字段：data（base64 音频，实际字段名为 data 而非 audio） */
    public static final String CARTESIA_FIELD_AUDIO = "data";
    /** Cartesia TTS WebSocket JSON 字段：done */
    public static final String CARTESIA_FIELD_DONE = "done";
    /** Cartesia TTS WebSocket JSON 字段：message */
    public static final String CARTESIA_FIELD_MESSAGE = "message";
    /** Cartesia TTS WebSocket close code：正常关闭 */
    public static final int CARTESIA_CLOSE_NORMAL = 1000;
    /** Cartesia TTS WebSocket close code：服务端错误 */
    public static final int CARTESIA_CLOSE_SERVER_ERROR = 1011;
    /** Cartesia TTS WebSocket close reason：复用连接 */
    public static final String CARTESIA_CLOSE_REASON_REUSE = "reuse connection";
    /** Cartesia TTS WebSocket close reason：完成 */
    public static final String CARTESIA_CLOSE_REASON_DONE = "done";
    /** Cartesia TTS WebSocket close reason：客户端关闭 */
    public static final String CARTESIA_CLOSE_REASON_CLIENT_CLOSED = "client closed";
    /** TTS 内部错误消息：未知错误 */
    public static final String TTS_ERROR_UNKNOWN = "unknown error";
    /** 默认音色 ID（当用户未克隆音色时使用） */
    public static final String VOICE_ID_DEFAULT = "default";
    /** PCM 编码格式（Cartesia 协议） */
    public static final String CARTESIA_ENCODING_PCM_S16LE = "pcm_s16le";

    // ═══════════════════════════════════════════════════════════
    // HTTP 状态码
    // ═══════════════════════════════════════════════════════════

    public static final int HTTP_OK = 200;
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_UNAUTHORIZED = 401;
    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_SERVER_ERROR = 500;

    // ═══════════════════════════════════════════════════════════
    // Session
    // ═══════════════════════════════════════════════════════════

    public static final int SESSION_EXPIRE_SECONDS = 3600 * 8;
    public static final String SESSION_STATUS_RUNNING = "RUNNING";
    public static final String SESSION_STATUS_ENDED = "ENDED";
    public static final String SESSION_STATUS_STOPPED = "STOPPED";

    // ═══════════════════════════════════════════════════════════
    // 连接池
    // ═══════════════════════════════════════════════════════════

    public static final int TTS_POOL_MAX_TOTAL = 10;
    public static final int TTS_POOL_MIN_IDLE = 2;
    public static final long TTS_POOL_MAX_WAIT_MS = 5000L;

    // ═══════════════════════════════════════════════════════════
    // Cartesia API
    // ═══════════════════════════════════════════════════════════

    public static final String CARTESIA_TTS_MODEL = "sonic-3";
    public static final String CARTESIA_CONTAINER = "raw";
    public static final String CARTESIA_API_ENDPOINT = "https://api.cartesia.ai";
    public static final String CARTESIA_VERSION_HEADER = "2026-03-01";

    // ═══════════════════════════════════════════════════════════
    // Google Translate
    // ═══════════════════════════════════════════════════════════

    public static final String TRANSLATION_API_ENDPOINT =
            "https://translation.googleapis.com/language/translate/v2";
    public static final String TRANSLATION_DETECT_ENDPOINT =
            "https://translation.googleapis.com/language/translate/v2/detect";
    public static final String TRANSLATION_SOURCE_AUTO = "auto";

    // ═══════════════════════════════════════════════════════════
    // Azure ASR
    // ═══════════════════════════════════════════════════════════

    /** ASR 默认语种列表（逗号分隔） */
    public static final String ASR_DEFAULT_LANGUAGES = "zh-CN,id-ID";
    /** ASR canceled 事件描述 */
    public static final String ASR_CANCELED_REASON = "ASR canceled";
    /** ASR 识别超时错误信息 */
    public static final String ASR_TIMEOUT_ERROR = "ASR recognition timeout";

    // ═══════════════════════════════════════════════════════════
    // VoiceMeeter
    // ═══════════════════════════════════════════════════════════

    /** VoiceMeeter Potato 共享内存映射名称 */
    public static final String VOICEMEETER_MAP_NAME_POTATO = "VoiceMeeterPotato";
    /** VoiceMeeter Basic/Standard 共享内存映射名称 */
    public static final String VOICEMEETER_MAP_NAME_BASIC = "VoiceMeeter";
    /** 共享内存全局前缀（Windows 命名共享内存格式） */
    public static final String VOICEMEETER_SHARED_MEMORY_PREFIX = "Global\\";
    /** 共享内存名称后缀 */
    public static final String VOICEMEETER_SHARED_MEMORY_SUFFIX = "_Now";
    /** 共享内存控制头大小（字节） */
    public static final int VOICEMEETER_SHARED_MEMORY_HEADER_SIZE = 256;
    /** 每个 Strip 在共享内存中的 stride（字节，4KB 对齐） */
    public static final int VOICEMEETER_STRIP_STRIDE = 4096;
    /** 获取字符串参数时缓冲区大小（字节） */
    public static final int VOICEMEETER_STRING_BUFFER_SIZE = 256;

    // ═══════════════════════════════════════════════════════════
    // Default voice IDs（从 Cartesia GET /voices 获取）
    // ═══════════════════════════════════════════════════════════

    /** 默认印尼语音色 ID：Andi - Dynamic Presenter */
    public static final String DEFAULT_VOICE_ID_INDONESIAN = "a053f6bc-7df4-40de-96d4-de026bc47ce8";
    /** 默认中文音色 ID：Jing - Clear Coordinator */
    public static final String DEFAULT_VOICE_ID_CHINESE = "6eb8965c-e295-47bd-a9e4-3eeebb3abcff";
}
