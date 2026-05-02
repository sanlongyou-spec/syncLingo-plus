package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本翻译请求 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranslateTextRequest {

    @NotBlank(message = "text 不能为空")
    private String text;

    @NotBlank(message = "sourceLang 不能为空")
    private String sourceLang;

    @NotBlank(message = "targetLang 不能为空")
    private String targetLang;
}
