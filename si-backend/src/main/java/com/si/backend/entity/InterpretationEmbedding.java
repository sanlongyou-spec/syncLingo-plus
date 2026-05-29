package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class InterpretationEmbedding {
    private Long id;
    /** 'result' | 'meeting_summary' | 'speaker_summary' | 'file_summary' | 'file_content' | 'action_item' */
    private String sourceType;
    /** Unique chunk identifier (for file_content: fileId*1000+chunkIdx) */
    private Long sourceId;
    /** Deletion key — original entity PK (for file_content all chunks share the same refId=fileId) */
    private Long refId;
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
