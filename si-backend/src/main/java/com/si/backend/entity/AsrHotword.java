package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-owned ASR hotword used to improve recognition accuracy.
 */
@Data
public class AsrHotword {

    private Long id;
    private Long userId;
    private String phrase;
    private String language;
    private String category;
    private Double weight;
    private String sourceType;
    private Long sourceTerminologyId;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
