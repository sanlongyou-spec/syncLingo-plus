package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to persist a freshly parsed pre-meeting upload under a meeting.
 */
@Data
public class PersistPreMeetingFileRequest {

    @NotBlank(message = "fileId 不能为空")
    private String fileId;
}
