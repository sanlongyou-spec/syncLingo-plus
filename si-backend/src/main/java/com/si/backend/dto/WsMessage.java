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
    private String speakerId;
    private String speakerName;
    private Long speechStartAtMs;
    private String translatedText;
    private String targetLanguage;
    private String ttsTaskId;
    private Long ttsSequence;
    private Integer chunkIndex;
    private String event;
    private String reason;
    private String playbackLang;
    private Long durationMs;
    private Long scheduledAheadMs;
    private Integer pendingCount;
    private String contextState;
    private Boolean audioPaused;
    private Boolean sinkReady;
    private Integer sampleRate;
    private String detail;
    private String code;
    private String message;
}
