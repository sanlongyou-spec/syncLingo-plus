package com.si.backend.dto;

import lombok.Data;

import java.util.List;

/**
 * Request for saving a user's default interpretation language preference.
 */
@Data
public class SaveUserLanguagePreferenceRequest {
    private String defaultSourceLang;
    private List<String> enabledLanguages;
}
