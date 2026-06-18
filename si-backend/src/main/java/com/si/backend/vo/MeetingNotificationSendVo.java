package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of sending a user-confirmed meeting notification.
 */
@Data
@Builder
public class MeetingNotificationSendVo {
    private int selectedRecipientCount;
    private int deliveryRecipientCount;
    private int botStatusCode;
    private int sentCount;
    private int failedCount;
    private List<String> successfulRecipients;
    private List<String> failedRecipients;
    private String error;
}
