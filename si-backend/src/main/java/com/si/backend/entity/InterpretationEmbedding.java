package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class InterpretationEmbedding {
    private Long id;
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
