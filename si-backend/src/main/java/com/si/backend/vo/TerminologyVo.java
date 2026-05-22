package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Terminology response.
 */
@Data
@Builder
public class TerminologyVo {
    private Long id;
    private String termZh;
    private String termId;
    private String termEn;
    private String pinyin;
    private String category;
    private String note;
    private String sourceSheet;
    private Integer sourceRow;
    private String reviewStatus;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
