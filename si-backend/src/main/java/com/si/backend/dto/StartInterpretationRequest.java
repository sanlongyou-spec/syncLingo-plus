package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class StartInterpretationRequest {

    @NotBlank(message = "sourceLang 不能为空")
    private String sourceLang;

    @NotBlank(message = "targetLang 不能为空")
    private String targetLang;

    private String title;

    private String voiceId;

    private List<Long> hotwordIds;

    private List<String> enabledLanguages;

    private Long meetingId;
}
