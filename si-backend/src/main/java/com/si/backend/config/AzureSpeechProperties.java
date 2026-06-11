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
        private long segmentationSilenceTimeoutMs = 2000L;
        private String segmentationStrategy;
        /** Speech_SegmentationMaximumTimeMs：单段最大时长（ms），配合 Time/Semantic 策略作为时间兜底（0 = 不设置） */
        private long segmentationMaximumTimeMs = 0L;
        private boolean sentenceSegmentationEnabled = true;
        private int maxSegmentZhChars = 50;
        private int maxSegmentWords = 50;
        /** 单段最大字符数；超过时在应用层强制切段（0 = 不限制） */
        private int maxSegmentChars = 80;
        /**
         * 应用层"按时间"强制切段阈值（ms）：一段累计说话超过此时长仍未遇到句末标点时，
         * 在安全边界(逗号/词边界, 并留尾余量防句首丢字)强制切一刀。0 = 关闭。
         * 用于 Azure ConversationTranscriber 不认 SegmentationMaximumTimeMs 时, 兜住 run-on 长句。
         */
        private long forceSegmentMs = 0L;
        /** 让中间结果也带说话人分轨（减少 Unknown，强制分段也能拿到 speakerId） */
        private boolean diarizeIntermediateResults = true;
    }

    @Data
    public static class TtsProperties {
        private String voice = "zh-CN-XiaoxiaoMultilingualNeural";
        private int sampleRate = 24000;
    }
}
