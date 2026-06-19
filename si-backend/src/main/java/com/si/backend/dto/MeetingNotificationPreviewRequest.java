package com.si.backend.dto;

import lombok.Data;

/**
 * Request to generate a notification preview from an uploaded meeting notice.
 */
@Data
public class MeetingNotificationPreviewRequest {
    private String fileId;
}
