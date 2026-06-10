package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Result of sending a user-confirmed meeting notification.
 */
@Data
@Builder
public class MeetingNotificationSendVo {
    private int selectedRecipientCount;
    private int deliveryRecipientCount;
    private int botStatusCode;
}
