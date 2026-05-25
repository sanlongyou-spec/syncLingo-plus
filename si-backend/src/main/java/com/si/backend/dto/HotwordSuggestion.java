package com.si.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Suggested hotword extracted from session transcript by LLM.
 * The {@code exists} field is populated only during preview (not on confirm).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class HotwordSuggestion {
    private String phrase;
    private String category;
    private String language;
    private Boolean exists;
}
