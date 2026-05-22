package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-owned Google glossary configuration for one translation direction.
 */
@Data
public class UserGlossaryConfig {

    private Long id;
    private Long userId;
    private String sourceLang;
    private String targetLang;
    private String glossaryId;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
