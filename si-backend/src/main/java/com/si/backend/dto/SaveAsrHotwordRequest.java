package com.si.backend.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Request for creating or updating an ASR hotword.
 */
@Data
public class SaveAsrHotwordRequest {
    private String phrase;
    private String language;
    private String category;
    private Double weight;
    private Boolean enabled;
    private LocalDateTime expiresAt;
}
