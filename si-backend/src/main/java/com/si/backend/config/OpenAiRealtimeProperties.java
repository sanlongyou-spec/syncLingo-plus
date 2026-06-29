package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openai.realtime")
public class OpenAiRealtimeProperties {

    private boolean enabled = false;
    private String apiKey = "";
    private String url = "wss://api.openai.com/v1/realtime/translations";
    private String model = "gpt-realtime-translate";
    private String inputTranscriptionModel = "gpt-realtime-whisper";
    private int maxPendingFrames = 1000;
    private long flushDelayMs = 1500L;
    private long maxSegmentMs = 7000L;
    private long closeGraceMs = 1500L;
    private long reconnectWindowMs = 120000L;
    private int maxReconnectsPerWindow = 6;
    private long reconnectBaseDelayMs = 500L;
    private long reconnectMaxDelayMs = 5000L;

    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
