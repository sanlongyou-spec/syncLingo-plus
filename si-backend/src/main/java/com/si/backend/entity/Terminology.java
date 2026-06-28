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
    /** 来源会议：NULL = 手动/Excel 导入(全局，跨会议复用)；非空 = 从该会议材料自动抽取(仅该会议生效)。 */
    private Long meetingId;
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
