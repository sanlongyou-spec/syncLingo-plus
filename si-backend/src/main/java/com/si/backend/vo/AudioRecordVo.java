package com.si.backend.vo;

import com.si.backend.entity.SessionAudioRecord;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AudioRecordVo {
    private Long id;
    private String sessionId;
    private Long userId;
    private Long meetingId;
    private String name;
    private Long fileSizeBytes;
    private Long durationMs;
    private Integer sampleRate;
    private LocalDateTime createTime;

    public static AudioRecordVo from(SessionAudioRecord record) {
        if (record == null) return null;
        return AudioRecordVo.builder()
                .id(record.getId())
                .sessionId(record.getSessionId())
                .userId(record.getUserId())
                .meetingId(record.getMeetingId())
                .name(record.getName())
                .fileSizeBytes(record.getFileSizeBytes())
                .durationMs(record.getDurationMs())
                .sampleRate(record.getSampleRate())
                .createTime(record.getCreateTime())
                .build();
    }
}
