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

    /** DashScope API Key（用于印尼语翻译压缩） */
    private String dashscopeApiKey;

    /** 压缩模型（默认 qwen3-max） */
    private String compressionModel = "qwen3-max";

    /** DashScope Base URL */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
}
