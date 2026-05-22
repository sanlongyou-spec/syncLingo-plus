package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterpretationSessionVo {

    private String sessionId;
    private String sourceLang;
    private String targetLang;
    private String title;
    private String status;
    private String voiceId;
    private String startTime;
    private String endTime;
    private Integer resultCount;
    private Long asrAudioMs;
    private Long translateChars;
    private Long ttsChars;
    private Long llmInputTokens;
    private Long llmOutputTokens;
    private String meetingSummary;
}
