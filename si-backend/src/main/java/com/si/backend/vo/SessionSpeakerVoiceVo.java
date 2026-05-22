package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Session speaker voice response.
 */
@Data
@Builder
public class SessionSpeakerVoiceVo {
    private Long id;
    private String sessionId;
    private String speakerId;
    private String cartesiaVoiceId;
    private String cloneStatus;
    private Integer audioSeconds;
    private String language;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
