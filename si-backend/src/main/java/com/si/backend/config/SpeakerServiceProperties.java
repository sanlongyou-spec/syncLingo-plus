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
    private Double minScore = 0.4D;
    /**
     * Higher cosine score required to SWITCH away from the already-resolved speaker
     * of a stream (hysteresis). A low-confidence reading that merely differs from the
     * current speaker is treated as a brief misidentification and ignored.
     */
    private Double switchScore = 0.45D;
    /**
     * Minimum gap between the best and runner-up candidate. When the top two are closer than this
     * (ambiguous — typically similar voices), the identification is treated as uncertain and the
     * current speaker is kept instead of switching.
     */
    private Double marginThreshold = 0.06D;
    /**
     * Azure "Unknown" 段是否继承"上一位已确认的说话人"。true=继承(连续性好但会把别人的段误标成上一位);
     * false=不继承,认不出就保持 Unknown(前端回退显示 speakerId/Guest-N)。默认 false,避免误标。
     */
    private Boolean inheritUnknown = false;
    private Integer timeoutSeconds = 15;
}
