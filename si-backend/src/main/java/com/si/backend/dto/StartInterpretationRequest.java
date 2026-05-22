package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class StartInterpretationRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotBlank(message = "sourceLang 不能为空")
    private String sourceLang;

    @NotBlank(message = "targetLang 不能为空")
    private String targetLang;

    private String voiceId;

    private List<Long> hotwordIds;

    private List<String> enabledLanguages;
}
