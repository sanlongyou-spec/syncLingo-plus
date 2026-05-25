package com.si.backend.dto;

import lombok.Data;

@Data
public class PreMeetingParticipantRequest {
    private String aadId;
    private String displayName;
    private String email;
}
