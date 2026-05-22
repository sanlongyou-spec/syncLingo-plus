package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAI API configuration for LLM compression and meeting summary generation.
 */
@Data
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;

    private String baseUrl = "https://api.openai.com/v1";

    private String referer;

    private String title;

    private boolean compressionEnabled = true;

    private int compressionMinTextLength = 80;

    private String compressionModel = "gpt-5-nano";

    private double compressionZhToIdTargetRatio = 0.75;

    private boolean compressionZhToEnEnabled = true;

    private double compressionZhToEnTargetRatio = 0.85;

    private String summaryModel = "gpt-5-mini";

    private long compressionMaxOutputTokens = 512L;

    private long summaryMaxOutputTokens = 1200L;
}
