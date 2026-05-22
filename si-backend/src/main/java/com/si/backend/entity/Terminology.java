package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 术语实体，对应 terminology 表。
 */
@Data
public class Terminology {

    private Long id;
    private Long userId;
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
