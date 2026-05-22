package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "speaker.service")
public class SpeakerServiceProperties {

    private Boolean enabled = false;
    private String url = "http://localhost:7000";
    private Double minScore = 0.25D;
    private Integer timeoutSeconds = 15;
}
