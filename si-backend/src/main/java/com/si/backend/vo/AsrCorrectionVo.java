package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * ASR 错词库展示 VO。
 */
@Data
@Builder
public class AsrCorrectionVo {
    private Long id;
    private String variant;
    private String canonical;
    private String srcLang;
    private Double confidence;
    private Integer hitCount;
    private String status;
    private String source;
    private String updateTime;
}
