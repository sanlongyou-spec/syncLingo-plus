package com.si.backend.dto;

import lombok.Data;

@Data
public class PreMeetingSummarizeRequest {
    private String fileId;
    private String requirements;
    private Long userId;
}
