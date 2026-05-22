package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for saving a persistent speaker identity.
 */
@Data
public class SaveSpeakerIdentityRequest {

    @NotBlank
    private String personName;

    private String speakerProfileId;
    private String cartesiaVoiceId;
    private String language;
    private String note;
}
