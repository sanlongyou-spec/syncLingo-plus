package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Interpretation record response.
 */
@Data
@Builder
public class InterpretationRecordVo {
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
