package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Parsed meeting-notice information, recipient matching result, and editable notification draft.
 */
@Data
@Builder
public class MeetingNotificationPreviewVo {
    private String meetingName;
    private String dateText;
    private List<String> timeLines;
    private String venue;
    private String meetingCode;
    private String passcode;
    private String meetingUrl;
    private String notificationContent;
    private List<String> participantNames;
    private List<MeetingNotificationRecipientVo> teamsRecipients;
    private List<String> nonTeamsSkipped;
    private List<String> unmatched;
}
