package com.si.backend.dto;

import lombok.Data;

/**
 * Request for creating or updating a user glossary configuration.
 */
@Data
public class SaveUserGlossaryConfigRequest {
    private String sourceLang;
    private String targetLang;
    private String glossaryId;
    private Boolean enabled;
}
