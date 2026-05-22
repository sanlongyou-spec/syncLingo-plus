package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Google Translate API 配置属性，绑定 google.translate 前缀。
 */
@Data
@Component
@ConfigurationProperties(prefix = "google.translate")
public class GoogleTranslateProperties {

    private String apiKey;
    private Boolean glossaryEnabled = false;
    private String projectId;
    private String location = "us-central1";
    private String glossaryId;
    private String glossaryZhToIdId;
    private String glossaryIdToZhId;
    private String glossaryZhToEnId;
    private String glossaryEnToZhId;
    private String glossaryIdToEnId;
    private String glossaryEnToIdId;
    private Boolean glossaryIgnoreCase = true;
    private Boolean glossaryFallbackEnabled = true;
}
