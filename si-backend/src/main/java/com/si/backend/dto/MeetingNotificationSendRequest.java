package com.si.backend.dto;

import lombok.Data;

import java.util.List;

/**
 * User-confirmed meeting notification content and selected Teams recipients.
 */
@Data
public class MeetingNotificationSendRequest {
    private String content;
    private List<String> recipients;
}
