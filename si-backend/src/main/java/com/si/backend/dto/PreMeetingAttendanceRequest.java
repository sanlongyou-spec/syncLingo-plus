package com.si.backend.dto;

import lombok.Data;

import java.util.List;

@Data
public class PreMeetingAttendanceRequest {
    private String fileId;
    /** When set (and no fileId), compare against the 应到 list saved on this meeting. */
    private Long meetingId;
    private List<PreMeetingParticipantRequest> actualParticipants;
}
