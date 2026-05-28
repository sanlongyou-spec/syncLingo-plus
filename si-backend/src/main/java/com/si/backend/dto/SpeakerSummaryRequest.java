package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpeakerSummaryRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    private String speakerId;

    private String speakerName;

    private String requirements;

    @NotBlank(message = "text 不能为空")
    private String text;
}
