package com.si.backend.dto;

import lombok.Data;

@Data
public class EmbedCandidate {
    private Long resultId;
    private Long sourceId;   // PK from the source table (for non-result types)
    private Long meetingId;
    private String sessionId;
    private String sessionTitle;
    private String sourceText;
    private String translatedText;
    private String speakerName;
}
