package com.si.backend.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionAudioRecord {
    private Long id;
    private String sessionId;
    private Long userId;
    private Long meetingId;
    private String name;
    private String filePath;
    private Long fileSizeBytes;
    private Long durationMs;
    private Integer sampleRate;
    private LocalDateTime createTime;
}
