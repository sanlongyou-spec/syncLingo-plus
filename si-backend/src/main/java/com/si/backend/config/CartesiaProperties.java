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
    private VoiceGenderTtsProperties voiceGender = new VoiceGenderTtsProperties();

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

    @Data
    public static class VoiceGenderTtsProperties {
        private boolean enabled = true;
        /** 全局兜底男/女音色：未配置语种专属音色时使用。 */
        private String maleVoiceId;
        private String femaleVoiceId;
        /**
         * 语种专属男/女音色（Cartesia 语言码 zh/en/id）。
         * 官方 Sonic 为多语种模型，但单个音色在非母语语种上发音会不自然，
         * 因此中文等语种应配置母语音色；留空则回退到全局男/女音色。
         */
        private String zhMaleVoiceId;
        private String zhFemaleVoiceId;
        private String enMaleVoiceId;
        private String enFemaleVoiceId;
        private String idMaleVoiceId;
        private String idFemaleVoiceId;
        private String unknownFallback = "target-default";

        /** 按语种取男声音色，缺省回退全局男声。lang 为 Cartesia 语言码 zh/en/id。 */
        public String maleVoiceIdForLanguage(String lang) {
            return firstNonBlank(languageSpecificMale(lang), maleVoiceId);
        }

        /** 按语种取女声音色，缺省回退全局女声。 */
        public String femaleVoiceIdForLanguage(String lang) {
            return firstNonBlank(languageSpecificFemale(lang), femaleVoiceId);
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

        private String languageSpecificFemale(String lang) {
            if (lang == null) {
                return null;
            }
            return switch (lang) {
                case "zh" -> zhFemaleVoiceId;
                case "en" -> enFemaleVoiceId;
                case "id" -> idFemaleVoiceId;
                default -> null;
            };
        }

        private static String firstNonBlank(String primary, String fallback) {
            return (primary != null && !primary.isBlank()) ? primary : fallback;
        }
    }
}
