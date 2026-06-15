package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Text translation request DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranslateTextRequest {

    private Long userId;

    @NotBlank(message = "text cannot be blank")
    private String text;

    @NotBlank(message = "sourceLang cannot be blank")
    private String sourceLang;

    @NotBlank(message = "targetLang cannot be blank")
    private String targetLang;
}
