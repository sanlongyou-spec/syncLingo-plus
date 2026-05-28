package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateMeetingRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotBlank(message = "title 不能为空")
    private String title;

    private String scheduledTime;

    private String note;
}
