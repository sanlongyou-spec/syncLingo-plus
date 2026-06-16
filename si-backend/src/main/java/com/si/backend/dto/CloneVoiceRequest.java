package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CloneVoiceRequest {

    @NotBlank(message = "voiceName 不能为空")
    private String voiceName;

    @NotBlank(message = "audioSample 不能为空")
    private String audioSample;

    private String language = "zh";
}
