package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterpretationResultVo {

    private String text;
    private String translatedText;
    private String sourceLang;
    private String targetLang;
    private Long timestamp;
    private Boolean isFinal;
}
