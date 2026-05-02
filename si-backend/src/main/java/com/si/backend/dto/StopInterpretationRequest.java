package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StopInterpretationRequest {

    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;
}
