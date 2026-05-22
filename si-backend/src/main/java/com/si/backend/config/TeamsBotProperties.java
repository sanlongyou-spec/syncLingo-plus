package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Teams Bot query configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "teams.bot")
public class TeamsBotProperties {

    private Long defaultUserId = 0L;

    private int maxSessions = 5;

    private int summaryPreviewChars = 1200;

    private String apiSecret;
}
