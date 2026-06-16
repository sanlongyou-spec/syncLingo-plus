package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateMeetingRequest {

    @NotBlank(message = "title 不能为空")
    private String title;

    private String scheduledTime;

    private String note;
}
