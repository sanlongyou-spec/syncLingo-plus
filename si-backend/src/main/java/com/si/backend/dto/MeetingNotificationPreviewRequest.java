package com.si.backend.dto;

import lombok.Data;

/**
 * Request to save a Teams meeting link and generate a notification preview.
 */
@Data
public class MeetingNotificationPreviewRequest {
    private String meetingUrl;
    private String fileId;
}
