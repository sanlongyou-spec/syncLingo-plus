package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent speaker identity registry.
 */
@Data
public class SpeakerIdentity {

    private Long id;
    private String personName;
    private String speakerProfileId;
    private String cartesiaVoiceId;
    private String language;
    private String note;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
