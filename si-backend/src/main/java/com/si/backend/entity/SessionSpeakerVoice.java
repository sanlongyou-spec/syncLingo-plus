package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话内说话人与 Cartesia 音色映射实体，对应 session_speaker_voice 表。
 */
@Data
public class SessionSpeakerVoice {

    private Long id;
    private String sessionId;
    private String speakerId;
    private String cartesiaVoiceId;
    private String cloneStatus;
    private Integer audioSeconds;
    private String language;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
