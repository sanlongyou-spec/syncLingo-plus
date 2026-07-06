package com.si.backend.config;

import com.si.backend.common.Constants;
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
    private VoiceGenderTtsProperties voiceGender = new VoiceGenderTtsProperties();

    /** 默认中文音色 ID，可通过 CARTESIA_DEFAULT_VOICE_ID_ZH 环境变量覆盖 */
    private String defaultVoiceIdChinese = "6eb8965c-e295-47bd-a9e4-3eeebb3abcff";

    /** 默认印尼语音色 ID，可通过 CARTESIA_DEFAULT_VOICE_ID_ID 环境变量覆盖 */
    private String defaultVoiceIdIndonesian = "a053f6bc-7df4-40de-96d4-de026bc47ce8";

    /** 默认英语音色 ID，可通过 CARTESIA_DEFAULT_VOICE_ID_EN 环境变量覆盖 */
    private String defaultVoiceIdEnglish = "default";

    @Data
    public static class TtsProperties {
        private String model;
        private String modelId;
        private int sampleRate = 24000;
        private String container = "raw";
        /**
         * Cartesia max_buffer_delay_ms：模型在合成前最多缓冲多少毫秒文本以攒够上下文。
         * 0 = 收到即合成(最低延迟,但短输入易产生 choppy/怪音)。官方建议给小正值减少怪音。
         * 折中默认 150ms：明显降低短句怪音,首音延迟仅略增。
         */
        private int maxBufferDelayMs = 150;
        private long synthesizedIndonesianSkipWaitMs = 80_000L;

        public String getModelId() {
            return firstNonBlank(modelId, model, Constants.CARTESIA_TTS_MODEL);
        }

        private static String firstNonBlank(String first, String second, String fallback) {
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
            if (second != null && !second.isBlank()) {
                return second.trim();
            }
            return fallback;
        }
    }

    @Data
    public static class PoolProperties {
        private int maxTotalPerVoice = 10;
        private int minIdlePerVoice = 2;
        private long maxWaitMillis = 5000L;
    }

    @Data
    public static class VoiceGenderTtsProperties {
        /** 全局兜底男声：未配置语种专属男声时使用。 */
        private String maleVoiceId;
        /**
         * 语种专属男声（Cartesia 语言码 zh/en/id）。Sonic 为多语种模型，但单个音色在
         * 非母语语种上发音不自然，因此各语种应配置母语男声；留空则回退到全局男声。
         */
        private String zhMaleVoiceId;
        private String enMaleVoiceId;
        private String idMaleVoiceId;

        /** 按语种取男声音色，缺省回退全局男声。lang 为 Cartesia 语言码 zh/en/id。 */
        public String maleVoiceIdForLanguage(String lang) {
            return firstNonBlank(languageSpecificMale(lang), maleVoiceId);
        }

        private String languageSpecificMale(String lang) {
            if (lang == null) {
                return null;
            }
            return switch (lang) {
                case "zh" -> zhMaleVoiceId;
                case "en" -> enMaleVoiceId;
                case "id" -> idMaleVoiceId;
                default -> null;
            };
        }

        private static String firstNonBlank(String primary, String fallback) {
            return (primary != null && !primary.isBlank()) ? primary : fallback;
        }
    }
}
