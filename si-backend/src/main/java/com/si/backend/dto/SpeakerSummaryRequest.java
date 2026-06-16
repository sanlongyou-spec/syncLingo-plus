package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SpeakerSummaryRequest {

    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    private String speakerId;

    private String speakerName;

    private String requirements;

    @NotBlank(message = "text 不能为空")
    private String text;
}
