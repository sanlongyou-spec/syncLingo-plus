package com.si.backend.dto;

import lombok.Data;

import java.util.List;

@Data
public class PreMeetingAttendanceRequest {
    private String fileId;
    private List<PreMeetingParticipantRequest> actualParticipants;
}
