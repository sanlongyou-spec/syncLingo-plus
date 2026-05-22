package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent speaker identity response.
 */
@Data
@Builder
public class SpeakerIdentityVo {
    private Long id;
    private String personName;
    private String speakerProfileId;
    private String cartesiaVoiceId;
    private String language;
    private String note;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
