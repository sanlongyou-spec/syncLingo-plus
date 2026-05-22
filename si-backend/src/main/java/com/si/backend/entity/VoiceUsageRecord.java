package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 音色使用记录实体，对应 voice_usage_record 表。
 */
@Data
public class VoiceUsageRecord {

    private Long id;
    private String sessionId;
    private Long userId;
    private String voiceId;
    private String targetLang;
    private Integer textLen;
    private LocalDateTime usedAt;
}
