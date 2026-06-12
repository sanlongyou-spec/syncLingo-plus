package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persisted speaker summary returned to clients.
 */
@Data
@Builder
public class SpeakerSummaryRecordVo {
    private Long id;
    private String sessionId;
    private String speakerId;
    private String speakerName;
    private String title;
    private String textSnippet;
    private String summary;
    private LocalDateTime createTime;
}
