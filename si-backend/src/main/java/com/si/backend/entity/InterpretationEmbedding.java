package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class InterpretationEmbedding {
    private Long id;
    /** 'result' | 'meeting_summary' | 'speaker_summary' | 'file_summary' | 'file_content' */
    private String sourceType;
    /** PK from the source table (null for legacy result rows that predate this column) */
    private Long sourceId;
    private Long resultId;
    private String sessionId;
    private Long meetingId;
    private String sessionTitle;
    private LocalDate sessionDate;
    private String speakerName;
    private String chunkText;
    private String translatedText;
    private byte[] embedding;
    private LocalDateTime createTime;
}
