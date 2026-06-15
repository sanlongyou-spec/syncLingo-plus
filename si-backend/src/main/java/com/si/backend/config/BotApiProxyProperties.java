package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Java-side protection for the temporary Teams Bot reverse proxy.
 */
@Data
@Component
@ConfigurationProperties(prefix = "bot.api")
public class BotApiProxyProperties {

    private String url = "http://localhost:3978";
    private List<Long> allowedUserIds = List.of();
}
