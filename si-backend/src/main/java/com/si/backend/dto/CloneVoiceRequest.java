package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CloneVoiceRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotBlank(message = "voiceName 不能为空")
    private String voiceName;

    @NotBlank(message = "audioSample 不能为空")
    private String audioSample;

    private String language = "zh";
}
