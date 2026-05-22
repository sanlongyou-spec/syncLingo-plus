package com.si.backend.dto;

import lombok.Data;

/**
 * Request for creating or updating terminology.
 */
@Data
public class SaveTerminologyRequest {
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
}
