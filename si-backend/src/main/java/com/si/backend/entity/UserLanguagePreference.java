package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-owned default interpretation language preference.
 */
@Data
public class UserLanguagePreference {
    private Long id;
    private Long userId;
    private String defaultSourceLang;
    private String enabledLanguages;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
