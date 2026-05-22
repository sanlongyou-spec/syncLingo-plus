package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Current meeting speaker identity mapping response.
 */
@Data
@Builder
public class SessionSpeakerIdentityVo {
    private String sessionId;
    private String speakerId;
    private String personName;
    private String speakerProfileId;
    private String cartesiaVoiceId;
    private String status;
    private String source;
}
