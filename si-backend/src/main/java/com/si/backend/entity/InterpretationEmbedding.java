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
    /** Start offset of this chunk within the original source text (for Small-to-Big window expansion). */
    private Integer chunkStart;
    private byte[] embedding;
    private String embeddingModel;
    private Integer embeddingDim;
    private String embeddingProfile;
    private String contentHash;
    private String indexStatus;
    private LocalDateTime lastEmbeddedAt;
    private LocalDateTime createTime;
}
