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
}
