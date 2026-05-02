package com.si.backend.dto;

import lombok.Data;

@Data
public class WsMessage {

    private String type;
    private String sessionId;
    private String sourceLang;
    private String targetLang;
    private String voiceId;
    private String audioBase64;
    private String text;
    private String language;
    private String translatedText;
    private String targetLanguage;
    private String code;
    private String message;
}
