package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * User default interpretation language preference response.
 */
@Data
@Builder
public class UserLanguagePreferenceVo {
    private Long userId;
    private String defaultSourceLang;
    private List<String> enabledLanguages;
}
