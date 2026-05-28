package com.si.backend.dto;

import lombok.Data;

@Data
public class EmbedCandidate {
    private Long resultId;
    private String sessionId;
    private String sourceText;
    private String translatedText;
    private String speakerName;
}
