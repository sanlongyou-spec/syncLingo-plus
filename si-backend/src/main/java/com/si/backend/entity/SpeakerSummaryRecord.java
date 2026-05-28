package com.si.backend.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SpeakerSummaryRecord {
    private Long id;
    private String sessionId;
    private String speakerId;
    private String speakerName;
    private String title;
    private String textSnippet;
    private String summary;
    private LocalDateTime createTime;
}
