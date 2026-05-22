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

    /** 默认中文音色 ID，可通过 CARTESIA_DEFAULT_VOICE_ID_ZH 环境变量覆盖 */
    private String defaultVoiceIdChinese = "6eb8965c-e295-47bd-a9e4-3eeebb3abcff";

    /** 默认印尼语音色 ID，可通过 CARTESIA_DEFAULT_VOICE_ID_ID 环境变量覆盖 */
    private String defaultVoiceIdIndonesian = "a053f6bc-7df4-40de-96d4-de026bc47ce8";

    /** 默认英语音色 ID，可通过 CARTESIA_DEFAULT_VOICE_ID_EN 环境变量覆盖 */
    private String defaultVoiceIdEnglish = "default";

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
