package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同传对话记录实体，对应 interpretation_record 表。
 */
@Data
public class InterpretationRecord {

    private Long id;
    private String sessionId;
    private Integer seq;
    private String sourceLang;
    private String targetLang;
    private String sourceText;
    private String targetText;
    private LocalDateTime spokenAt;
    private LocalDateTime createTime;
}
