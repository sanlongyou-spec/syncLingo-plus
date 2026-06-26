package com.si.backend.dto;

import lombok.Data;

/**
 * 会后挖错词请求：会议转写 + 参考文件文本。
 */
@Data
public class MineAsrCorrectionRequest {
    /** 会议 ASR 转写全文 */
    private String transcript;
    /** 会议参考文件文本(标准答案) */
    private String document;
}
