package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Voice usage response.
 */
@Data
@Builder
public class VoiceUsageRecordVo {
    private Long id;
    private String sessionId;
    private String meetingTitle;
    private Long userId;
    private String voiceId;
    private String targetLang;
    private Integer textLen;
    private LocalDateTime usedAt;
}
