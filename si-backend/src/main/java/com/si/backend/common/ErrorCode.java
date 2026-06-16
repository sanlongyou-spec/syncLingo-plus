package com.si.backend.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举，统一管理所有业务错误码。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // Global
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    GONE(410, "资源已下线"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SYSTEM_ERROR(500, "系统错误"),

    // ASR
    ASR_INIT_ERROR(1001, "ASR 服务初始化失败"),
    ASR_RECOGNIZE_ERROR(1002, "语音识别失败"),
    ASR_SESSION_NOT_FOUND(1003, "ASR 会话不存在"),
    ASR_CONNECTION_ERROR(1004, "ASR 连接失败"),

    // Translation
    TRANSLATE_ERROR(2001, "翻译服务异常"),
    TRANSLATE_TIMEOUT(2002, "翻译请求超时"),
    UNSUPPORTED_LANGUAGE(2003, "不支持的语种"),

    // TTS
    TTS_ERROR(3001, "TTS 服务异常"),
    TTS_POOL_EXHAUSTED(3002, "TTS 连接池耗尽"),
    TTS_VOICE_NOT_FOUND(3003, "音色不存在"),
    TTS_VOICE_CLONE_ERROR(3004, "音色克隆失败"),
    CARTESIA_CONNECTION_ERROR(3005, "Cartesia 连接失败"),

    // Session
    SESSION_NOT_FOUND(4001, "同传会话不存在"),
    SESSION_ALREADY_STARTED(4002, "同传会话已启动"),
    SESSION_ALREADY_STOPPED(4003, "同传会话已停止"),

    // Auth
    AUTH_FAILED(5001, "认证失败"),
    TOKEN_INVALID(5002, "Token 无效"),
    TOKEN_EXPIRED(5003, "Token 已过期"),
    CAPTCHA_REQUIRED(428, "需要验证码"),

    // Voice
    VOICE_SAMPLE_TOO_SHORT(6001, "音色样本时长不足"),
    VOICE_SAMPLE_INVALID(6002, "音色样本无效");

    private final Integer code;
    private final String message;
}
