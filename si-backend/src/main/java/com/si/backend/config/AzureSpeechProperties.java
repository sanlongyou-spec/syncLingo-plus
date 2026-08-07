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
        /**
         * sentence-punct 触发前中文段的最小字符数。
         * 短于此值的句子不在句末切断，等待下一个句末标点累积到足够长度再切。
         * 0 = 不限制（旧行为）。
         */
        private int minSentenceEmitZhChars = 45;
        /**
         * 印尼语 wtpsplit 句边界的最小字符数：分句模型在该长度之前检测到的句边界不切，
         * 等累积到足够长度(或走逗号/字数兜底)再切，避免把 "satu"/"nine" 这种短语切成碎片。
         * 0 = 不限制。
         */
        private int minSentenceEmitIdChars = 48;
        /**
         * 印尼语调用 wtpsplit/SaT 分句前，working 需累积的最小字符数。
         * 短于此值不送 SaT（SaT 是句子分割模型，上下文不足会把短缓冲过度切碎，
         * 如 "starship"/"ke depan"/"8 tahun"）。短文先累积，等够长再交给 SaT，边界更准。
         * 0 = 不限制（回退到仅受 PUNCT_MIN_CHARS=10 约束的旧行为）。
         */
        private int idSegMinInputChars = 40;
        /** English guard switch. It keeps forced interim segments from cutting incomplete English clauses. */
        private boolean enSegmentGuardEnabled = true;
        /** Minimum words before querying the semantic boundary service for English interim text. */
        private int enSegMinInputWords = 18;
        /** Minimum words for an English forced final emitted from interim text. Final Azure results are not floored. */
        private int enMinEmitWords = 18;
        /** Soft word count where English starts searching more aggressively for reliable semantic boundaries. */
        private int enSoftMaxWords = 50;
        /** Overlong marker for logging and stronger heuristic search. This is not a hard cutoff. */
        private int enOverlongEscalationWords = 80;
        /**
         * 印尼语完整性 Guard 开关（id-ID 专用）：
         * 半词尾/连接词尾/可疑词头拦截、固定短语保护、编号/金额消歧，
         * 弱边界(逗号/词边界/超长/超时 backstop)强切降级为 partial（不进翻译/入库/TTS）。
         * true = 开启（准确率优先）；false = 回退到旧的强切行为。
         */
        private boolean idSegmentGuardEnabled = true;
    }

    @Data
    public static class TtsProperties {
        private String voice = "zh-CN-XiaoxiaoMultilingualNeural";
        private int sampleRate = 24000;
    }
}
