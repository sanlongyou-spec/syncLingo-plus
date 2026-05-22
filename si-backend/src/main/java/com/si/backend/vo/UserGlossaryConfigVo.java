package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User glossary configuration response.
 */
@Data
@Builder
public class UserGlossaryConfigVo {
    private Long id;
    private Long userId;
    private String sourceLang;
    private String targetLang;
    private String glossaryId;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
