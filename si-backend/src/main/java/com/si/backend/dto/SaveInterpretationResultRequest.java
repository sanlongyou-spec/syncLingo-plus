package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SaveInterpretationResultRequest {

    @NotBlank
    private String sessionId;

    @NotBlank
    private String sourceText;

    @NotBlank
    private String translatedText;

    private String sourceLang;
    private String targetLang;
    private String speakerId;
    private String speakerName;

    @Positive
    private Long speechStartAtMs;
}
