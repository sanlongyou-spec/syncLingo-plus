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
    /** Minimum cosine score to accept an identification / keep the current speaker. */
    private Double minScore = 0.25D;
    /**
     * Higher cosine score required to SWITCH away from the already-resolved speaker
     * of a stream (hysteresis). A low-confidence reading that merely differs from the
     * current speaker is treated as a brief misidentification and ignored.
     */
    private Double switchScore = 0.45D;
    private Integer timeoutSeconds = 15;
}
