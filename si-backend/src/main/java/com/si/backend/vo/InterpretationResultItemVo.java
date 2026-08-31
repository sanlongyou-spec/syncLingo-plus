package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InterpretationResultItemVo {

    private Long id;
    private String sessionId;
    private String meetingTitle;
    private String sourceText;
    private String translatedText;
    private String sourceLang;
    private String targetLang;
    private String speakerId;
    private String speakerName;
    private Long speechStartAtMs;
    private String createTime;
}
