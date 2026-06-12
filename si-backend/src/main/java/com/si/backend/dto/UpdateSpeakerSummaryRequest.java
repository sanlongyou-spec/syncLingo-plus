package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request for manually correcting a persisted speaker summary.
 */
@Data
public class UpdateSpeakerSummaryRequest {

    @NotBlank(message = "speakerName 不能为空")
    @Size(max = 255, message = "speakerName 长度不能超过 255")
    private String speakerName;

    @NotBlank(message = "summary 不能为空")
    private String summary;
}
