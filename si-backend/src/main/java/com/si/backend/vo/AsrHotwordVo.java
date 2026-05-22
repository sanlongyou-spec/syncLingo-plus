package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ASR hotword response.
 */
@Data
@Builder
public class AsrHotwordVo {
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
}
