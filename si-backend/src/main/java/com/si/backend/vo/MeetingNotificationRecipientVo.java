package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * A meeting-notice participant matched to an existing Teams-capable user account.
 */
@Data
@Builder
public class MeetingNotificationRecipientVo {
    private String scheduleName;
    private String accountName;
    private String email;
    private String teamsAccount;
}
