package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cartesia")
public class CartesiaProperties {

    private String apiKey;
    private String apiUrl = "wss://api.cartesia.ai";
    private TtsProperties tts = new TtsProperties();
    private PoolProperties pool = new PoolProperties();

    @Data
    public static class TtsProperties {
        private String modelId = "sonic-3";
        private int sampleRate = 24000;
        private String container = "raw";
    }

    @Data
    public static class PoolProperties {
        private int maxTotalPerVoice = 10;
        private int minIdlePerVoice = 2;
        private long maxWaitMillis = 5000L;
    }
}
