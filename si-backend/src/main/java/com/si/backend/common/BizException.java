package com.si.backend.common;

import lombok.Getter;

/**
 * 统一异常类，所有业务异常均抛出此类型。
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static BizException of(Integer code, String message) {
        return new BizException(code, message);
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode.getCode(), errorCode.getMessage());
    }

    public static BizException of(ErrorCode errorCode, String customMessage) {
        return new BizException(errorCode.getCode(), customMessage);
    }
}
