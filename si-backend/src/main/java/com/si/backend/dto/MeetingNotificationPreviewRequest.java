package com.si.backend.dto;

import lombok.Data;

/**
 * Request to generate a notification preview; meetingUrl is optional when the notice contains a Teams link.
 */
@Data
public class MeetingNotificationPreviewRequest {
    private String meetingUrl;
    private String fileId;
}
