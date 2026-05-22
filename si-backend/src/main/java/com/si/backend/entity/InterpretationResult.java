package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InterpretationResult {

    private Long id;
    private String sessionId;
    private String sourceText;
    private String translatedText;
    private String sourceLang;
    private String targetLang;
    private LocalDateTime createTime;
}
