package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户音色实体，对应数据库 user_voice 表。
 */
@Data
public class UserVoice {
    private Long id;
    private Long userId;
    private String voiceId;
    private String voiceName;
    private Integer durationSeconds;
    private String sampleUrl;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
