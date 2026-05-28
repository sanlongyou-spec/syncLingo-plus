package com.si.backend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CrossMeetingSnippet {
    private String sessionId;
    private String sourceText;
    private String translatedText;
    private String sessionTitle;
    private LocalDateTime sessionStartTime;
}
