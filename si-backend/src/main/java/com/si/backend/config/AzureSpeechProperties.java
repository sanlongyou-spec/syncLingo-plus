package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "azure.speech")
public class AzureSpeechProperties {

    private String key;
    private String region;
    private AsrProperties asr = new AsrProperties();
    private TtsProperties tts = new TtsProperties();

    @Data
    public static class AsrProperties {
        private String model = "default";
        private String language = "zh-CN,id-ID";
        private int sampleRate = 16000;
        private String format = "audio/pcm";
        private int channels = 1;
        private long endSilenceTimeoutMs = 6000L;
    }

    @Data
    public static class TtsProperties {
        private String voice = "zh-CN-XiaoxiaoMultilingualNeural";
        private int sampleRate = 24000;
    }
}
