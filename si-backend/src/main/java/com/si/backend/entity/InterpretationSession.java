package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 同传会话实体，对应数据库 interpretation_session 表。
 */
@Data
public class InterpretationSession {
    private Long id;
    private String sessionId;
    private Long userId;
    private String sourceLang;
    private String targetLang;
    private String voiceId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}
