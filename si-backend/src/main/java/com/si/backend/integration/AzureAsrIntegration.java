package com.si.backend.integration;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber;
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriptionResult;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.AzureSpeechProperties;
import com.si.backend.service.EnglishIncompleteGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Azure ASR 集成层，封装 Azure 语音识别 API 调用。
 * 支持自动语种检测（AutoDetectSourceLanguageConfig）与指定语种两种模式。
 */
@Slf4j
@Component
public class AzureAsrIntegration {

    private static final int FINAL_REALIGN_WINDOW_CHARS = 80;
    private static final int MIN_FINAL_REMAINDER_OVERLAP_CHARS = 2;

    private final AzureSpeechProperties asrProperties;
    private final PunctuationServiceIntegration punctuationService;
    private final SegmentationServiceIntegration segmentationService;
    private final com.si.backend.service.IndonesianBoundarySegmenter idSegmenter;
    private final com.si.backend.service.IndonesianIncompleteGuard idGuard;
    private final EnglishIncompleteGuard enGuard;
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    public AzureAsrIntegration(AzureSpeechProperties asrProperties,
                                 PunctuationServiceIntegration punctuationService,
                                 SegmentationServiceIntegration segmentationService,
                                 com.si.backend.service.IndonesianBoundarySegmenter idSegmenter,
                                 com.si.backend.service.IndonesianIncompleteGuard idGuard,
                                 EnglishIncompleteGuard enGuard) {
        this.asrProperties = asrProperties;
        this.punctuationService = punctuationService;
        this.segmentationService = segmentationService;
        this.idSegmenter = idSegmenter;
        this.idGuard = idGuard;
        this.enGuard = enGuard;
    }

    /**
     * 创建 ASR 会话。
     *
     * @param sessionId  会话 ID
     * @param sourceLang 源语言，传 "auto" 或 null 启用自动检测
     * @return ASR 会话实例
     */
    public AsrSession createSession(String sessionId, String sourceLang, List<String> hotwords, String enabledLanguages) {
        if (sessions.containsKey(sessionId)) {
            log.warn("[AzureAsrIntegration] session already exists, sessionId={}", sessionId);
            return sessions.get(sessionId);
        }

        SpeechConfig config = SpeechConfig.fromSubscription(
                asrProperties.getKey(),
                asrProperties.getRegion()
        );

        config.setProperty(PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,
                String.valueOf(asrProperties.getAsr().getEndSilenceTimeoutMs()));
        config.setProperty(PropertyId.Speech_SegmentationSilenceTimeoutMs,
                String.valueOf(asrProperties.getAsr().getSegmentationSilenceTimeoutMs()));
        if (asrProperties.getAsr().getSegmentationStrategy() != null
                && !asrProperties.getAsr().getSegmentationStrategy().isBlank()) {
            config.setProperty(PropertyId.Speech_SegmentationStrategy,
                    asrProperties.getAsr().getSegmentationStrategy());
        }
        if (asrProperties.getAsr().getSegmentationMaximumTimeMs() > 0) {
            config.setProperty("Speech_SegmentationMaximumTimeMs",
                    String.valueOf(asrProperties.getAsr().getSegmentationMaximumTimeMs()));
        }
        // Diarize intermediate (transcribing) results too, so forced-segmented chunks emitted from
        // interim events carry a speakerId instead of "Unknown" — cuts the Unknown rate at the source.
        if (asrProperties.getAsr().isDiarizeIntermediateResults()) {
            config.setProperty(PropertyId.SpeechServiceResponse_DiarizeIntermediateResults, "true");
        }
        log.info("[AzureAsrIntegration] ASR silence config, endSilenceMs={}, segmentationSilenceMs={}, segmentationStrategy={}, segmentationMaxTimeMs={}, forceSegmentMs={}, sentenceSegmentation={}, maxSegmentZhChars={}, maxSegmentWords={}, maxSegmentChars={}, minSentenceEmitIdChars={}, idSegMinInputChars={}, enGuard={}, enSoftMaxWords={}, enOverlongWords={}",
                asrProperties.getAsr().getEndSilenceTimeoutMs(),
                asrProperties.getAsr().getSegmentationSilenceTimeoutMs(),
                asrProperties.getAsr().getSegmentationStrategy(),
                asrProperties.getAsr().getSegmentationMaximumTimeMs(),
                asrProperties.getAsr().getForceSegmentMs(),
                asrProperties.getAsr().isSentenceSegmentationEnabled(),
                asrProperties.getAsr().getMaxSegmentZhChars(),
                asrProperties.getAsr().getMaxSegmentWords(),
                asrProperties.getAsr().getMaxSegmentChars(),
                asrProperties.getAsr().getMinSentenceEmitIdChars(),
                asrProperties.getAsr().getIdSegMinInputChars(),
                asrProperties.getAsr().isEnSegmentGuardEnabled(),
                asrProperties.getAsr().getEnSoftMaxWords(),
                asrProperties.getAsr().getEnOverlongEscalationWords());

        AudioStreamFormat audioFormat = AudioStreamFormat.getWaveFormatPCM(
                (short) asrProperties.getAsr().getSampleRate(),
                (short) Constants.BITS_PER_SAMPLE,
                (short) Constants.AUDIO_CHANNELS_MONO
        );

        PushAudioInputStream pushStream = PushAudioInputStream.create(audioFormat);

        AzureSpeechProperties.AsrProperties asrConfig = asrProperties.getAsr();
        AsrSession session;
        if (Constants.LANG_AUTO.equalsIgnoreCase(sourceLang) || sourceLang == null || sourceLang.isBlank()) {
            log.info("[AzureAsrIntegration] creating session with ConversationTranscriber (Continuous LID + diarization), sessionId={}", sessionId);
            String[] languages = parseLanguages(
                    enabledLanguages != null && !enabledLanguages.isBlank()
                            ? enabledLanguages
                            : asrProperties.getAsr().getLanguage()
            );
            config.setProperty(PropertyId.SpeechServiceConnection_LanguageIdMode, "Continuous");
            AutoDetectSourceLanguageConfig autoConfig = AutoDetectSourceLanguageConfig.fromLanguages(List.of(languages));
            session = new AsrSession(config, autoConfig, pushStream, asrConfig, hotwords, punctuationService, segmentationService, idSegmenter, idGuard, enGuard);
        } else {
            log.info("[AzureAsrIntegration] creating session with ConversationTranscriber, specified lang={}, sessionId={}", sourceLang, sessionId);
            config.setSpeechRecognitionLanguage(sourceLang);
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            session = new AsrSession(config, audioConfig, pushStream, asrConfig, hotwords, punctuationService, segmentationService, idSegmenter, idGuard, enGuard);
        }

        sessions.put(sessionId, session);
        log.info("[AzureAsrIntegration] session created, sessionId={}", sessionId);
        return session;
    }

    /**
     * 兼容旧调用，使用配置默认值。
     */
    public AsrSession createSession(String sessionId) {
        return createSession(sessionId, null, List.of(), null);
    }

    /**
     * 推送 PCM 音频帧。
     *
     * @param sessionId 会话 ID
     * @param audioData PCM 数据
     */
    public void pushAudio(String sessionId, byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            return;
        }
        AsrSession session = sessions.get(sessionId);
        if (session != null && session.pushStream != null) {
            session.pushStream.write(audioData);
        }
    }

    /**
     * 关闭并清理会话。
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        AsrSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.info("[AzureAsrIntegration] session closed, sessionId={}", sessionId);
        }
    }

    private String[] parseLanguages(String languageConfig) {
        if (languageConfig == null || languageConfig.isBlank()) {
            return Constants.ASR_DEFAULT_LANGUAGES.split(",");
        }
        return languageConfig.split(",");
    }

    // ─────────────────────────────────────────────────────────
    // ASR 会话封装
    // ─────────────────────────────────────────────────────────

    /**
     * ASR 会话封装。
     */
    public static class AsrSession implements AutoCloseable {

        private final SpeechConfig config;
        private final PushAudioInputStream pushStream;
        private final SpeechRecognizer recognizer;
        private final ConversationTranscriber conversationTranscriber;
        private final boolean autoDetectEnabled;
        private final boolean diarizationEnabled;
        private final AtomicReference<String> finalText = new AtomicReference<>("");
        private final AtomicReference<String> detectedLang = new AtomicReference<>("");
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private RecognizerCallback callback;
        private static final int NO_SEGMENT = -1;
        private static final String ASCII_SENTENCE_END_PUNCTUATION = ".!?;";
        private static final String ASCII_CLOSING_PUNCTUATION = "\"')]}`";
        /** 逗号类（run-on 兜底切点）：英文逗号/分号、中文逗号，、顿号、中文分号； */
        private static final String SEGMENT_COMMA_PUNCTUATION = ",;，、；";
        /** 强切时留在句尾不提交的字符数（Azure 会改写最近词，留余量防句首丢字） */
        private static final int FORCE_TAIL_MARGIN_CHARS = 4;
        private final boolean sentenceSegmentationEnabled;
        private final int maxSegmentZhChars;
        private final int maxSegmentWords;
        /** 应用层强制切段阈值（字符数），0 = 不限制 */
        private final int maxSegmentChars;
        /** run-on 长句兜底：一段说话超过此 ms 仍无句末标点时，在最后一个逗号处强切，0 = 关闭 */
        private final long forceSegmentMs;
        /** sentence-punct 触发前中文段的最小字符数；0 = 不限制 */
        private final int minSentenceEmitZhChars;
        /** 印尼语 wtpsplit 句边界的最小字符数；短于此值不切，避免碎片；0 = 不限制 */
        private final int minSentenceEmitIdChars;
        /** 印尼语送 SaT 分句前 working 需累积的最小字符数；短于此不调 SaT，先累积再切（避免上下文不足切碎）。 */
        private final int idSegMinInputChars;
        /** 标点还原服务（null = 未启用），用于在 Transcribing 中间结果里推断句末标点位置 */
        private final PunctuationServiceIntegration punctuationSvc;
        /** 句边界检测服务（null = 未启用），用于印尼语等无标点还原模型的语言 */
        private final SegmentationServiceIntegration segmentationSvc;
        /** 方案2:LLM 实时分句器(null = 未启用)。开启后印尼语改由它在句子边界异步切句 */
        private final com.si.backend.service.IndonesianBoundarySegmenter idSegmenter;
        /** 印尼语完整性 Guard(null 或 disabled = 不启用):发段前一票否决 + 弱边界降级 partial。 */
        private final com.si.backend.service.IndonesianIncompleteGuard idGuard;
        private final EnglishIncompleteGuard enGuard;
        /** 方案2:是否有一次分句 LLM 调用在途(同一会话同一时刻最多一次,省成本/防乱序);仅在 segLock 内读写 */
        private boolean idSegInFlight = false;
        /** 方案2:上次触发分句调用时的 working 长度,用于节流(只在 segLock 内读写) */
        private int idSegLastFiredLen = 0;
        /** 调用标点服务的最短文本长度（避免在极短片段上浪费调用） */
        private static final int PUNCT_MIN_CHARS = 10;
        /** 当前未提交段落的开始时刻（ms）；emit 或 final 后重置，用于按时间强切 */
        private final java.util.concurrent.atomic.AtomicLong segmentStartMs = new java.util.concurrent.atomic.AtomicLong(0);
        /** 当前句已发出(最终)的字符数, 单调只增, 只在 segLock 内改 → 绝不重发已发文本(防失控刷段) */
        private int emittedLen = 0;
        /** emittedLen 边界前 EMIT_SUFFIX_LEN 个字符的指纹，用于检测 ASR 文本修正导致的指针漂移 */
        private String emittedSuffix = "";
        /** 已提前发出的完整文本，用于 final 结果到达后重新对齐真实 remainder 边界 */
        private final StringBuilder emittedTextBuffer = new StringBuilder();
        /** 印尼语 final remainder 短于输出地板时先暂存，等待下一段合并后再发 final。 */
        private String pendingIdFloorText = "";
        private String pendingIdFloorLang = "";
        private String pendingIdFloorSpeakerId = "";
        private String pendingEnText = "";
        private String pendingEnLang = "";
        private String pendingEnSpeakerId = "";
        /** 已发 final 文本的归一化滚动账本：跨段/跨终稿去重，防 emittedLen 回退导致的重发/前缀重叠。 */
        private final StringBuilder emitLedger = new StringBuilder();
        private long emitLedgerAtMs = 0;
        /** 账本滚动窗口(归一化字符数)与空闲清空 TTL。 */
        private static final int EMIT_LEDGER_MAX = 2000;
        private static final long EMIT_LEDGER_TTL_MS = 60_000L;
        private static final int EMIT_SUFFIX_LEN = 16;
        /** 上一次中间结果全文(算稳定前缀, 连续两次未变=Azure已确认) */
        private String prevText = "";
        /** 序列化 中间/最终 结果处理(Azure 事件可能在不同线程派发) */
        private final Object segLock = new Object();
        /** 上一次 Azure 返回的有效 speakerId，用于补全 interim 阶段 Unknown 的强制分段 */
        private final AtomicReference<String> lastValidSpeakerId = new AtomicReference<>("");
        /** 上一次 transcribing(中间结果) 时间戳，用于估算 ASR 句末延迟（最后中间结果→isFinal） */
        private final java.util.concurrent.atomic.AtomicLong lastInterimAtMs = new java.util.concurrent.atomic.AtomicLong(0);
        /** 节流 INFO 级 recognizing 日志：上次记录时的文本长度 */
        private int lastLoggedTranscribeLen = 0;
        /** 节流 INFO 级 recognizing 日志：上次记录时间戳 */
        private long lastLoggedTranscribeMs = 0;
        private static final int LOG_TRANSCRIBING_CHAR_STEP = 40;
        private static final long LOG_TRANSCRIBING_INTERVAL_MS = 5000;

        private final List<String> hotwords;

        public AsrSession(SpeechConfig config, AudioConfig audioConfig, PushAudioInputStream pushStream,
                          AzureSpeechProperties.AsrProperties asrConfig, List<String> hotwords,
                          PunctuationServiceIntegration punctuationService,
                          SegmentationServiceIntegration segmentationService,
                          com.si.backend.service.IndonesianBoundarySegmenter idSegmenter,
                          com.si.backend.service.IndonesianIncompleteGuard idGuard,
                          EnglishIncompleteGuard enGuard) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = false;
            this.diarizationEnabled = true;
            this.recognizer = null;
            this.sentenceSegmentationEnabled = asrConfig.isSentenceSegmentationEnabled();
            this.maxSegmentZhChars = asrConfig.getMaxSegmentZhChars();
            this.maxSegmentWords = asrConfig.getMaxSegmentWords();
            this.maxSegmentChars = asrConfig.getMaxSegmentChars();
            this.forceSegmentMs = asrConfig.getForceSegmentMs();
            this.minSentenceEmitZhChars = asrConfig.getMinSentenceEmitZhChars();
            this.minSentenceEmitIdChars = asrConfig.getMinSentenceEmitIdChars();
            this.idSegMinInputChars = asrConfig.getIdSegMinInputChars();
            this.hotwords = hotwords;
            this.punctuationSvc = punctuationService;
            this.segmentationSvc = segmentationService;
            this.idSegmenter = idSegmenter;
            this.idGuard = idGuard;
            this.enGuard = enGuard;
            this.conversationTranscriber = new ConversationTranscriber(config, audioConfig);
        }

        public AsrSession(SpeechConfig config, AutoDetectSourceLanguageConfig autoConfig, PushAudioInputStream pushStream,
                          AzureSpeechProperties.AsrProperties asrConfig, List<String> hotwords,
                          PunctuationServiceIntegration punctuationService,
                          SegmentationServiceIntegration segmentationService,
                          com.si.backend.service.IndonesianBoundarySegmenter idSegmenter,
                          com.si.backend.service.IndonesianIncompleteGuard idGuard,
                          EnglishIncompleteGuard enGuard) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = true;
            this.diarizationEnabled = true;
            this.sentenceSegmentationEnabled = asrConfig.isSentenceSegmentationEnabled();
            this.maxSegmentZhChars = asrConfig.getMaxSegmentZhChars();
            this.maxSegmentWords = asrConfig.getMaxSegmentWords();
            this.maxSegmentChars = asrConfig.getMaxSegmentChars();
            this.forceSegmentMs = asrConfig.getForceSegmentMs();
            this.minSentenceEmitZhChars = asrConfig.getMinSentenceEmitZhChars();
            this.minSentenceEmitIdChars = asrConfig.getMinSentenceEmitIdChars();
            this.idSegMinInputChars = asrConfig.getIdSegMinInputChars();
            this.hotwords = hotwords;
            this.punctuationSvc = punctuationService;
            this.segmentationSvc = segmentationService;
            this.idSegmenter = idSegmenter;
            this.idGuard = idGuard;
            this.enGuard = enGuard;
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            this.recognizer = null;
            this.conversationTranscriber = new ConversationTranscriber(config, autoConfig, audioConfig);
        }

        public void setCallback(RecognizerCallback callback) {
            this.callback = callback;
        }

        public void startContinuous() {
            if (diarizationEnabled) {
                startConversationTranscriber();
                return;
            }
            recognizer.recognizing.addEventListener((s, e) -> {
                if (callback != null) {
                    String lang = resolveDetectedLanguage(e.getResult());
                    callback.onRecognizing(e.getResult().getText(), lang, Constants.SPEAKER_ID_UNKNOWN, false);
                }
            });

            recognizer.recognized.addEventListener((s, e) -> {
                if (callback != null) {
                    boolean isFinal = e.getResult().getReason() == ResultReason.RecognizedSpeech;
                    if (isFinal) {
                        String lang = resolveDetectedLanguage(e.getResult());
                        callback.onRecognizing(e.getResult().getText(), lang, Constants.SPEAKER_ID_UNKNOWN, true);
                    }
                }
            });

            recognizer.canceled.addEventListener((s, e) -> {
                log.warn("[AsrSession] ASR canceled, reason={}, error={}", e.getReason(), e.getErrorDetails());
                if (callback != null) {
                    callback.onError(e.getErrorDetails());
                }
            });

            recognizer.sessionStopped.addEventListener((s, e) -> closedLatch.countDown());

            recognizer.startContinuousRecognitionAsync();
        }

        private void startConversationTranscriber() {
            applyHotwords();
            conversationTranscriber.transcribing.addEventListener((s, e) -> {
                if (callback == null) return;
                ConversationTranscriptionResult result = e.getResult();
                String text = result.getText();
                if (text == null || text.isBlank()) return;
                lastInterimAtMs.set(System.currentTimeMillis());
                segmentStartMs.compareAndSet(0, System.currentTimeMillis());
                String lang = resolveDetectedLanguage(result);
                String speakerId = resolveSpeakerId(result);
                // DEBUG: 每个 ASR 中间结果(流式)都打印,含完整文本,用于离线还原"逐字增长 + 切句"全过程
                log.debug("[AsrSession] asr-stream textLen={} emittedLen={} lang={} speakerId={} text='{}'",
                        text.length(), emittedLen, lang, speakerId,
                        text.length() <= 200 ? text : text.substring(0, 200) + "…");
                // 节流 INFO：文本每增长 40 字符、或距上次超过 5 秒，记录一次便于分析分段问题
                long nowMs = System.currentTimeMillis();
                if (text.length() - lastLoggedTranscribeLen >= LOG_TRANSCRIBING_CHAR_STEP
                        || (text.length() > lastLoggedTranscribeLen
                                && nowMs - lastLoggedTranscribeMs >= LOG_TRANSCRIBING_INTERVAL_MS)) {
                    log.info("[AsrSession] asr-recognizing textLen={} emittedLen={} lang={} speakerId={} text='{}'",
                            text.length(), emittedLen, lang, speakerId,
                            text.length() <= 80 ? text : text.substring(0, 77) + "...");
                    lastLoggedTranscribeLen = text.length();
                    lastLoggedTranscribeMs = nowMs;
                }
                // 强切 + 取未发出的中间结果, 全程加锁 + emittedLen 单调推进(防失控刷段/竞态)
                String interimText;
                synchronized (segLock) {
                    emitForcedSegments(text, lang, speakerId);
                    interimText = text.substring(Math.min(emittedLen, text.length())).trim();
                }
                if (!interimText.isBlank()) {
                    log.debug("[AsrSession] onRecognizing interim interimLen={} lang={} speakerId={}",
                            interimText.length(), lang, speakerId);
                    callback.onRecognizing(interimText, lang, speakerId, false);
                }
            });

            conversationTranscriber.transcribed.addEventListener((s, e) -> {
                if (callback == null) return;
                ConversationTranscriptionResult result = e.getResult();
                if (result.getReason() != ResultReason.RecognizedSpeech) return;
                String text = result.getText();
                String lang = resolveDetectedLanguage(result);
                String speakerId = resolveSpeakerId(result);
                // 最终结果: 先按已发段尾在 Azure 终稿里重新对齐，再取剩余部分。
                // 不能直接把 interim 的 emittedLen 当 final 下标: Azure 终稿会重写大小写/标点/词面，字符下标可能漂移。
                String full = text == null ? "" : text;
                if (!full.isBlank()) {
                    log.info("[AsrSession] asr-raw lang={} speakerId={} len={} text='{}'",
                            lang, speakerId, full.length(),
                            full.length() <= 120 ? full : full.substring(0, 117) + "...");
                }
                String remainder;
                boolean hadForced;
                FinalRemainderResult remainderResult;
                synchronized (segLock) {
                    hadForced = emittedLen > 0;
                    remainderResult = finalRemainderAfterForcedSegments(
                            full, emittedLen, emittedSuffix, emittedTextBuffer.toString());
                    remainder = remainderResult.text();
                    emittedLen = 0;
                    emittedSuffix = "";
                    emittedTextBuffer.setLength(0);
                    prevText = "";
                }
                if (hadForced && (remainderResult.aligned() || remainderResult.overlapChars() > 0)) {
                    log.debug("[AsrSession] final remainder aligned, cutIndex={}, overlapChars={}, aligned={}, finalLen={}",
                            remainderResult.cutIndex(), remainderResult.overlapChars(), remainderResult.aligned(),
                            full.length());
                }
                lastLoggedTranscribeLen = 0;
                lastLoggedTranscribeMs = 0;
                segmentStartMs.set(0);
                long lastInterim = lastInterimAtMs.getAndSet(0);
                long asrTailMs = lastInterim > 0 ? System.currentTimeMillis() - lastInterim : -1;
                if (!remainder.isBlank()) {
                    String finalReason = hadForced ? "final-remainder" : "final-full";
                    // 终稿外科式：只剥结尾半词/连接词，绝不整段丢、不看词头(终稿是 Azure 权威文本)。
                    if (isIdGuardActiveFor(lang)) {
                        synchronized (segLock) {
                            remainder = mergePendingIdFloorText(remainder, lang, speakerId, finalReason);
                        }
                        String beforeTrim = remainder;
                        String trimmed = idGuard.trimIncompleteTail(remainder);
                        if (trimmed.isBlank()) {
                            synchronized (segLock) {
                                storePendingIdFloorText(beforeTrim, lang, speakerId, finalReason + "-incomplete");
                            }
                            log.info("[IdGuard] final remainder fully incomplete, held for output-floor, len={} text='{}'",
                                    beforeTrim.length(), previewText(beforeTrim));
                            return;
                        }
                        if (trimmed.length() != remainder.length()) {
                            log.info("[IdGuard] final remainder tail trimmed, fromLen={} toLen={} text='{}'",
                                    remainder.length(), trimmed.length(), previewText(trimmed));
                            remainder = trimmed;
                        }
                        synchronized (segLock) {
                            if (holdPendingIdFloorText(remainder, lang, speakerId, finalReason)) {
                                return;
                            }
                        }
                    } else if (isEnglishGuardActiveFor(lang)) {
                        synchronized (segLock) {
                            remainder = mergePendingEnglishText(remainder, lang, speakerId, finalReason);
                        }
                        if (enGuard.shouldDropFinalRemainder(remainder, hotwords)) {
                            log.info("[EnglishGuard] final remainder dropped, reason=filler-or-empty, len={} text='{}'",
                                    remainder.length(), previewText(remainder));
                            return;
                        }
                        if (enGuard.shouldHoldFinalRemainder(remainder, hotwords)) {
                            synchronized (segLock) {
                                storePendingEnglishText(remainder, lang, speakerId, finalReason + "-incomplete");
                            }
                            log.info("[EnglishGuard] final remainder held, words={}, len={} text='{}'",
                                    enGuard.wordCount(remainder), remainder.length(), previewText(remainder));
                            return;
                        }
                    }
                    emitFinalDeduped(remainder, lang, speakerId);
                    log.info("[AsrSession] asr-segment final={} costMs={} len={} speakerId={} text='{}'",
                            hadForced ? "remainder" : "full", asrTailMs, remainder.length(), speakerId,
                            previewText(remainder));
                }
            });

            conversationTranscriber.canceled.addEventListener((s, e) -> {
                log.warn("[AsrSession] conversation ASR canceled, reason={}, error={}", e.getReason(), e.getErrorDetails());
                if (callback != null) {
                    callback.onError(e.getErrorDetails());
                }
            });

            conversationTranscriber.sessionStopped.addEventListener((s, e) -> closedLatch.countDown());
            conversationTranscriber.startTranscribingAsync();
        }

        /**
         * 安全强切(单调下标 + 每事件最多切一次, 防失控刷段; 必须在 segLock 内调用):
         * emittedLen 单调只增 → 绝不重发已发文本; 只在 「稳定前缀(连续两次中间结果未变) ∩ 留尾余量」
         * 内, 按 句末标点>逗号>词/字边界 切一刀。Azure 改写已发区最多产生极小接缝, 不会雪崩。
         */
        private void emitForcedSegments(String text, String lang, String speakerId) {
            if (emittedLen > text.length()) {
                emittedLen = text.length();   // Azure 缩短/改写: 收缩 emittedLen, 不越界(不重发)
            }
            int stableAbs = commonPrefixLen(text, prevText);
            prevText = text;

            // ── emittedLen 对齐检查：ASR 修正可能改写 emittedLen 之前的文本，导致指针漂移 ──
            // 用指纹（边界前 N 字符）验证 emittedLen 在新文本中是否仍正确；不对则搜索修正。
            if (emittedLen > 0 && !emittedSuffix.isEmpty() && emittedLen <= text.length()) {
                int checkStart = Math.max(0, emittedLen - emittedSuffix.length());
                String boundary = text.substring(checkStart, emittedLen);
                if (!boundary.equals(emittedSuffix)) {
                    // 文本在 emittedLen 之前被修正；在新文本中搜索指纹定位正确边界
                    int found = text.lastIndexOf(emittedSuffix, emittedLen + emittedSuffix.length());
                    if (found >= 0) {
                        int corrected = found + emittedSuffix.length();
                        log.debug("[AsrSession] emittedLen corrected {} → {} (ASR revision)", emittedLen, corrected);
                        emittedLen = corrected;
                    } else {
                        BoundaryAlignment emittedTextAlignment = alignFinalBoundaryByEmittedText(
                                text, Math.min(emittedLen, text.length()), emittedTextBuffer.toString());
                        if (emittedTextAlignment.aligned()) {
                            int corrected = emittedTextAlignment.cutIndex();
                            log.debug("[AsrSession] emittedLen corrected by emittedText {} -> {} (suffix lost)",
                                    emittedLen, corrected);
                            emittedLen = corrected;
                            emittedSuffix = text.substring(Math.max(0, emittedLen - EMIT_SUFFIX_LEN), emittedLen);
                        } else {
                            log.warn("[AsrSession] emittedLen suffix lost, keep current boundary to avoid replay, emittedLen={}, stableAbs={}, textLen={}, emittedTextLen={}",
                                    emittedLen, stableAbs, text.length(), emittedTextBuffer.length());
                        }
                    }
                }
            }

            int start = emittedLen;
            if (start >= text.length()) {
                return;
            }
            String working = text.substring(start);
            boolean englishGuardActive = isEnglishGuardActiveFor(lang);

            // 为本段计时(供超时兜底 forceSegmentMs 使用):首段也要有起算点,
            // 否则 startedAt=0 时 shouldForce 的超时路径永不触发。每次 emit 后会重置为 emit 时刻。
            segmentStartMs.compareAndSet(0, System.currentTimeMillis());

            // ── 方案2:LLM 实时分句(印尼语)。开启后印尼语完全交给 LLM 在句子边界异步切句,
            //    本轮不做同步切(wtpsplit/逗号/词数都跳过);说话停顿时 transcribed 终稿兜底剩余。
            if (idSegmenter != null && idSegmenter.isEnabled() && startsWithIgnoreCase(lang, "id")) {
                maybeFireIdSegmenter(working, start, lang, speakerId);
                return;
            }

            int stableInWorking = Math.max(0, stableAbs - start);
            int safe = Math.min(stableInWorking, working.length() - FORCE_TAIL_MARGIN_CHARS);
            log.debug("[AsrSession] emitForcedSegments workingLen={} stable={} safe={} lang={} speakerId={}",
                    working.length(), stableInWorking, safe, lang, speakerId);
            if (safe <= 0) {
                // 兜底:稳定前缀长期不前进(印尼语长串枚举/无标点时 Azure 反复改写尾部),
                // 会使 safe<=0、下方正常切句路径全程早退,缓冲无限增长(曾观测单段 500~660 字 / 25~40s 延迟,
                // 直到静音终稿才一次性吐出)。一旦超长(词数/字数)或超时(forceSegmentMs),不再等待稳定前缀:
                // 用全文 working、仅留尾部 FORCE_TAIL_MARGIN_CHARS 余量,按词/字边界强切一刀。
                if (shouldForce(working, lang)) {
                    int b = hardBackstopBoundary(working, lang);
                    if (b > 0) {
                        String segment = working.substring(0, b).trim();
                        if (idGuard != null && idGuard.isEnabled() && startsWithIgnoreCase(lang, "id")) {
                            com.si.backend.service.IndonesianIncompleteGuard.EmitAction action =
                                    idGuard.decideEmit(working, b, "force-backstop");
                            if (action == com.si.backend.service.IndonesianIncompleteGuard.EmitAction.EMIT_FINAL) {
                                emittedLen = start + b;
                                emittedSuffix = text.substring(Math.max(0, emittedLen - EMIT_SUFFIX_LEN), emittedLen);
                                rememberEmittedSegment(segment);
                                segmentStartMs.set(System.currentTimeMillis());
                                String resolvedSpeakerId = resolveSegmentSpeakerId(speakerId);
                                String outputSegment = mergePendingIdFloorText(segment, lang, resolvedSpeakerId, "force-backstop");
                                log.info("[AsrSession] force-segment by=force-backstop len={} lang={} speakerId={} text='{}'",
                                        outputSegment.length(), lang, resolvedSpeakerId, previewText(outputSegment));
                                emitFinalDeduped(outputSegment, lang, resolvedSpeakerId);
                            } else {
                                log.info("[IdGuard] {} boundary reason=force-backstop len={} text='{}'", action, segment.length(),
                                        segment.length() <= 120 ? segment : segment.substring(0, 117) + "...");
                            }
                        } else if (!shouldDeferShortSegment("force-backstop", segment, lang) && segment.length() >= 8 && !segment.isBlank()) {
                            emittedLen = start + b;
                            emittedSuffix = text.substring(Math.max(0, emittedLen - EMIT_SUFFIX_LEN), emittedLen);
                            rememberEmittedSegment(segment);
                            segmentStartMs.set(System.currentTimeMillis());
                            String resolvedSpeakerId = resolveSegmentSpeakerId(speakerId);
                            log.info("[AsrSession] force-segment by=force-backstop len={} lang={} speakerId={} text='{}'",
                                    segment.length(), lang, resolvedSpeakerId,
                                    segment.length() <= 120 ? segment : segment.substring(0, 117) + "...");
                            emitFinalDeduped(segment, lang, resolvedSpeakerId);
                        }
                    }
                }
                return;
            }

            // ── 标点还原（中文/英文，长度 >= PUNCT_MIN_CHARS，服务可用时）──────────
            // CT-Transformer zh-en 模型同时支持中英文；用带标点的文本做切段判断，
            // 找到的标点位置映射回原始下标更新 emittedLen；向翻译 emit 带标点文本以提升翻译质量。
            String punctuated = null;
            if (punctuationSvc != null && punctuationSvc.isEnabled()
                    && working.length() >= PUNCT_MIN_CHARS
                    && (isChineseSegment(working, 0, lang) || startsWithIgnoreCase(lang, "en"))) {
                punctuated = punctuationSvc.punctuate(working);
                if (punctuated != null && punctuated.equals(working)) {
                    // 模型返回原文（model_available=false 场景），视为无效
                    punctuated = null;
                }
            }
            if (punctuated != null) {
                log.info("[AsrSession] punct lang={} inputLen={} outputLen={} input='{}' output='{}'",
                        lang, working.length(), punctuated.length(),
                        working.length() <= 120 ? working : working.substring(0, 117) + "...",
                        punctuated.length() <= 120 ? punctuated : punctuated.substring(0, 117) + "...");
            }
            String detectionText = punctuated != null ? punctuated : working;

            // ── 句边界检测（印尼语：wtpsplit SaT，服务可用时）──────────────────────
            int wtpBoundary = NO_SEGMENT;
            if (segmentationSvc != null && segmentationSvc.isEnabled()
                    && shouldQuerySemanticBoundary(working, lang, englishGuardActive)) {
                int b = segmentationSvc.findBoundary(working, lang, safe);
                if (b > 0) wtpBoundary = b;
                // DEBUG: 每次 wtpsplit 查询的输入与返回的句边界,用于分析"该不该切/切在哪"
                log.debug("[AsrSession] wtpsplit query workingLen={} safe={} boundary={} working='{}'",
                        working.length(), safe, b,
                        working.length() <= 200 ? working : working.substring(0, 200) + "…");
            }

            int end = NO_SEGMENT;
            String reason = null;
            String emitText = null;   // null → 用 working.substring(0, end)

            // 1) 句末标点：在 detectionText 中找，映射回 working 下标；
            //    中文段跳过短于 minSentenceEmitZhChars 的句边界，继续向后找更长的句末位置。
            //    无标点时对印尼语用 wtpsplit 边界补位。
            if (sentenceSegmentationEnabled) {
                boolean isChinese = isChineseSegment(working, 0, lang);
                int searchFrom = 0;
                while (end == NO_SEGMENT) {
                    int ep = findSentenceSegmentEnd(detectionText, searchFrom);
                    if (ep == NO_SEGMENT) break;
                    int eo = punctuated != null ? mapPunctuatedToOriginal(working, punctuated, ep) : ep;
                    if (eo <= 0 || eo > safe) break;
                    if (isChinese && minSentenceEmitZhChars > 0 && eo < minSentenceEmitZhChars) {
                        log.debug("[AsrSession] sentence-punct skipped (too short), eo={} < minEmit={}", eo, minSentenceEmitZhChars);
                        searchFrom = ep;
                        continue;
                    }
                    end = eo;
                    reason = punctuated != null ? "sentence-punct" : "sentence";
                    if (punctuated != null) {
                        emitText = punctuated.substring(0, ep).trim();
                    }
                }
                // 无标点边界：用 wtpsplit 检测到的语义句边界（印尼语）。
                // 加最小句长闸门：太短的边界(如 "satu"/"nine")不切，等累积更长或走逗号/字数兜底，避免碎片。
                boolean idGuardActive = idGuard != null && idGuard.isEnabled() && startsWithIgnoreCase(lang, "id");
                if (end == NO_SEGMENT && wtpBoundary != NO_SEGMENT
                        && (idGuardActive || englishGuardActive || minSentenceEmitIdChars <= 0 || wtpBoundary >= minSentenceEmitIdChars)) {
                    // Guard 开启时不再用 minId 死卡 wtpsplit 强边界：短边界交给 decideEmit(强边界+完整性检查)放行/扣回。
                    end = wtpBoundary;
                    reason = "sentence-wtpsplit";
                } else if (wtpBoundary != NO_SEGMENT && wtpBoundary < minSentenceEmitIdChars) {
                    log.debug("[AsrSession] wtpsplit boundary skipped (too short), boundary={} < minId={}",
                            wtpBoundary, minSentenceEmitIdChars);
                }
                // 编号标题强边界：在 "13 pemikiran" 这类列表编号前切（编号+标题进入下一段）。
                if (end == NO_SEGMENT && idGuardActive) {
                    int nt = idGuard.findNumberedTitleBoundary(working, safe);
                    if (nt > 0) {
                        end = nt;
                        reason = "numbered-title";
                    }
                }
                if (end == NO_SEGMENT && englishGuardActive && enGuard.shouldEscalateBoundarySearch(working)) {
                    EnglishIncompleteGuard.BoundaryCandidate candidate =
                            enGuard.findHeuristicBoundary(working, safe, hotwords);
                    if (candidate.found()) {
                        end = candidate.index();
                        reason = candidate.reason();
                        if (enGuard.isOverlong(working)) {
                            log.info("[EnglishGuard] overlong boundary candidate, words={}, safe={}, boundary={}, reason={}, text='{}'",
                                    enGuard.wordCount(working), safe, end, reason, previewText(working));
                        }
                    } else if (enGuard.isOverlong(working)) {
                        log.info("[EnglishGuard] overlong hold, words={}, safe={}, text='{}'",
                                enGuard.wordCount(working), safe, previewText(working));
                    }
                }
            }
            // 2) 逗号/子句标点：同样优先用标点版本
            if (end == NO_SEGMENT && !englishGuardActive && shouldForce(working, lang)) {
                // 当使用标点版本时，额外限制搜索上界为 safe（原始坐标）。
                // CT-Transformer 只插入字符，故 punct_pos >= orig_pos；
                // 任何 punct_pos <= safe 的逗号必然映射到 orig_pos <= safe，
                // 避免"最后逗号"映射后刚好越界 safe 而错失更早的有效逗号。
                int cp = punctuated != null
                        ? findCommaSegmentEnd(detectionText, 0, safe)
                        : findCommaSegmentEnd(detectionText, 0);
                if (cp != NO_SEGMENT) {
                    int co = punctuated != null ? mapPunctuatedToOriginal(working, punctuated, cp) : cp;
                    if (co > 0 && co <= safe) {
                        end = co;
                        reason = punctuated != null ? "force-comma-punct" : "force-comma";
                        if (punctuated != null) {
                            emitText = punctuated.substring(0, cp).trim();
                        }
                    }
                }
                // 3) 字/词边界兜底（仅限原始文本，无标点概念）
                if (end == NO_SEGMENT && !isChineseSegment(working, 0, lang)) {
                    int b = wordOrCharBoundaryAt(working, safe, lang);
                    if (b > 0) {
                        end = b;
                        reason = "force-boundary";
                    }
                }
            }
            if (end == NO_SEGMENT || end <= 0) {
                return;
            }
            String segment = emitText != null ? emitText : working.substring(0, end).trim();
            if ("force-boundary".equals(reason) && segment.length() < 8) {
                // 不推进 emittedLen，等文本积累到下次再切，避免丢字
                log.debug("[AsrSession] force-boundary segment too short ({}), deferred: '{}'", segment.length(), segment);
                return;
            }
            if (idGuard != null && idGuard.isEnabled() && startsWithIgnoreCase(lang, "id")) {
                com.si.backend.service.IndonesianIncompleteGuard.EmitAction action = idGuard.decideEmit(working, end, reason);
                if (action != com.si.backend.service.IndonesianIncompleteGuard.EmitAction.EMIT_FINAL) {
                    log.info("[IdGuard] {} boundary reason={} len={} text='{}'", action, reason, segment.length(),
                            segment.length() <= 120 ? segment : segment.substring(0, 117) + "...");
                    return;
                }
            } else if (englishGuardActive) {
                EnglishIncompleteGuard.EmitAction action = enGuard.decideEmit(working, end, reason, hotwords);
                if (action != EnglishIncompleteGuard.EmitAction.EMIT_FINAL) {
                    log.info("[EnglishGuard] {} boundary reason={} words={} len={} text='{}'",
                            action, reason, enGuard.wordCount(segment), segment.length(), previewText(segment));
                    return;
                }
            } else if (shouldDeferShortSegment(reason, segment, lang)) {
                log.debug("[AsrSession] force-segment deferred by min length, reason={}, len={}, minId={}, lang={}, text='{}'",
                        reason, segment.length(), minSentenceEmitIdChars, lang,
                        segment.length() <= 120 ? segment : segment.substring(0, 117) + "...");
                return;
            }
            emittedLen = start + end;   // 单调推进(原始下标), 不会回头重切
            emittedSuffix = text.substring(Math.max(0, emittedLen - EMIT_SUFFIX_LEN), emittedLen);
            rememberEmittedSegment(segment);
            segmentStartMs.set(System.currentTimeMillis());
            if (!segment.isBlank()) {
                String resolvedSpeakerId = resolveSegmentSpeakerId(speakerId);
                String outputSegment = mergePendingIdFloorText(segment, lang, resolvedSpeakerId, reason);
                outputSegment = mergePendingEnglishText(outputSegment, lang, resolvedSpeakerId, reason);
                log.info("[AsrSession] force-segment by={} len={} lang={} speakerId={} punct={} text='{}'",
                        reason, outputSegment.length(), lang, resolvedSpeakerId, punctuated != null,
                        previewText(outputSegment));
                emitFinalDeduped(outputSegment, lang, resolvedSpeakerId);
            }
        }

        /**
         * 方案2:在 segLock 内,按节流条件向 LLM 异步发起一次"句子边界"分句。
         * 同一会话同一时刻最多一个在途调用;working 是当前未提交文本(= text.substring(start))。
         */
        private void maybeFireIdSegmenter(String working, int start, String lang, String speakerId) {
            if (idSegInFlight) {
                return;
            }
            if (working.length() < idSegmenter.getMinChars()) {
                return;
            }
            if (idSegLastFiredLen > 0 && working.length() - idSegLastFiredLen < idSegmenter.getRefireChars()) {
                return; // 文本相比上次触发增长不足,节流,先不调
            }
            idSegInFlight = true;
            idSegLastFiredLen = working.length();
            final int snapStart = start;
            final String snapshot = working;
            final String snapLang = lang;
            final String snapSpeaker = speakerId;
            idSegmenter.findAsync(snapshot, cut -> applyIdBoundary(snapStart, snapshot, cut, snapLang, snapSpeaker));
        }

        /**
         * 方案2:LLM 异步分句结果回调(可能在分句线程)。在 segLock 内推进 emittedLen 并发出完整印尼语句。
         * 陈旧校验:若 emittedLen 已不等于发起时的 start(Azure 改写/终稿已推进),丢弃该结果,绝不回改已播。
         */
        private void applyIdBoundary(int start, String snapshot, int cut, String lang, String speakerId) {
            synchronized (segLock) {
                idSegInFlight = false;
                idSegLastFiredLen = 0; // 允许下一轮再触发
                if (cut <= 0) {
                    return;
                }
                if (emittedLen != start) {
                    return; // 陈旧:文本在调用期间被推进/改写,丢弃本次切点
                }
                int end = Math.min(cut, snapshot.length());
                if (end <= 0) {
                    return;
                }
                String segment = snapshot.substring(0, end).trim();
                if (segment.isBlank()) {
                    return;
                }
                if (idGuard != null && idGuard.isEnabled() && startsWithIgnoreCase(lang, "id")) {
                    com.si.backend.service.IndonesianIncompleteGuard.EmitAction action =
                            idGuard.decideEmit(snapshot, end, "llm-boundary");
                    if (action != com.si.backend.service.IndonesianIncompleteGuard.EmitAction.EMIT_FINAL) {
                        log.info("[IdGuard] {} boundary reason=llm-boundary len={} text='{}'", action, segment.length(),
                                segment.length() <= 120 ? segment : segment.substring(0, 117) + "...");
                        return;
                    }
                } else if (shouldDeferShortSegment("llm-boundary", segment, lang)) {
                    return;
                }
                emittedLen = start + end;
                emittedSuffix = snapshot.substring(Math.max(0, end - EMIT_SUFFIX_LEN), end);
                rememberEmittedSegment(segment);
                segmentStartMs.set(System.currentTimeMillis());
                String resolvedSpeakerId = resolveSegmentSpeakerId(speakerId);
                String outputSegment = mergePendingIdFloorText(segment, lang, resolvedSpeakerId, "llm-boundary");
                log.info("[AsrSession] force-segment by=llm-boundary len={} lang={} speakerId={} text='{}'",
                        outputSegment.length(), lang, resolvedSpeakerId, previewText(outputSegment));
                emitFinalDeduped(outputSegment, lang, resolvedSpeakerId);
            }
        }

        private void rememberEmittedSegment(String segment) {
            if (segment == null || segment.isBlank()) {
                return;
            }
            if (emittedTextBuffer.length() > 0) {
                emittedTextBuffer.append(' ');
            }
            emittedTextBuffer.append(segment.trim());
        }

        private boolean isIdGuardActiveFor(String lang) {
            return idGuard != null && idGuard.isEnabled() && startsWithIgnoreCase(lang, "id");
        }

        private boolean isEnglishGuardActiveFor(String lang) {
            return enGuard != null && enGuard.isEnabled() && startsWithIgnoreCase(lang, "en");
        }

        private boolean shouldQuerySemanticBoundary(String working, String lang, boolean englishGuardActive) {
            if (startsWithIgnoreCase(lang, "id")) {
                return working.length() >= Math.max(PUNCT_MIN_CHARS, idSegMinInputChars);
            }
            return englishGuardActive && enGuard.shouldQuerySentenceBoundary(working);
        }

        /**
         * 统一 final 发送出口：发前对"已发账本"去重（整段重复→丢弃，前缀重叠→裁掉），再发出并更新账本。
         */
        private void emitFinalDeduped(String text, String lang, String speakerId) {
            String out = text;
            if (text != null && !text.isBlank()) {
                long now = System.currentTimeMillis();
                if (emitLedgerAtMs > 0 && now - emitLedgerAtMs > EMIT_LEDGER_TTL_MS) {
                    emitLedger.setLength(0);
                }
                out = dedupEmitAgainstLedger(emitLedger.toString(), text);
                if (out == null || out.isBlank()) {
                    log.info("[AsrLedger] duplicate suppressed, lang={}, len={} text='{}'",
                            lang, text.length(), previewText(text));
                    return;
                }
                if (out.length() != text.length()) {
                    log.info("[AsrLedger] overlap trimmed, lang={}, fromLen={}, toLen={} text='{}'",
                            lang, text.length(), out.length(), previewText(out));
                }
                emitLedger.append(comparableString(out));
                if (emitLedger.length() > EMIT_LEDGER_MAX) {
                    emitLedger.delete(0, emitLedger.length() - EMIT_LEDGER_MAX);
                }
                emitLedgerAtMs = now;
            }
            callback.onRecognizing(out, lang, speakerId, true);
        }

        private String mergePendingIdFloorText(String segment, String lang, String speakerId, String reason) {
            String cleanSegment = segment == null ? "" : segment.trim();
            if (!isIdGuardActiveFor(lang) || pendingIdFloorText.isBlank()) {
                return cleanSegment;
            }
            String pending = pendingIdFloorText.trim();
            String merged = cleanSegment.isBlank() ? pending : pending + " " + cleanSegment;
            log.info("[IdGuard] pending output-floor merged, reason={}, pendingLen={}, segmentLen={}, mergedLen={}, pendingLang={}, lang={}, pendingSpeakerId={}, speakerId={}, text='{}'",
                    reason, pending.length(), cleanSegment.length(), merged.length(),
                    pendingIdFloorLang, lang, pendingIdFloorSpeakerId, speakerId, previewText(merged));
            pendingIdFloorText = "";
            pendingIdFloorLang = "";
            pendingIdFloorSpeakerId = "";
            return merged.trim();
        }

        private boolean holdPendingIdFloorText(String segment, String lang, String speakerId, String reason) {
            if (!isIdGuardActiveFor(lang)) {
                return false;
            }
            String cleanSegment = segment == null ? "" : segment.trim();
            if (cleanSegment.isBlank()) {
                return false;
            }
            if (!pendingIdFloorText.isBlank()) {
                cleanSegment = pendingIdFloorText.trim() + " " + cleanSegment;
            }
            if (!idGuard.shouldHoldForOutputFloor(cleanSegment)) {
                return false;
            }
            storePendingIdFloorText(segment, lang, speakerId, reason);
            return true;
        }

        private void storePendingIdFloorText(String segment, String lang, String speakerId, String reason) {
            String cleanSegment = segment == null ? "" : segment.trim();
            if (cleanSegment.isBlank()) {
                return;
            }
            if (!pendingIdFloorText.isBlank()) {
                cleanSegment = pendingIdFloorText.trim() + " " + cleanSegment;
            }
            pendingIdFloorText = cleanSegment.trim();
            pendingIdFloorLang = lang == null ? "" : lang;
            pendingIdFloorSpeakerId = speakerId == null ? "" : speakerId;
            log.info("[IdGuard] HOLD output-floor reason={} len={} minId={} lang={} speakerId={} text='{}'",
                    reason, visibleCharCount(pendingIdFloorText), minSentenceEmitIdChars, lang, speakerId,
                    previewText(pendingIdFloorText));
        }

        private String mergePendingEnglishText(String segment, String lang, String speakerId, String reason) {
            String cleanSegment = segment == null ? "" : segment.trim();
            if (!isEnglishGuardActiveFor(lang) || pendingEnText.isBlank()) {
                return cleanSegment;
            }
            String pending = pendingEnText.trim();
            String merged = cleanSegment.isBlank() ? pending : pending + " " + cleanSegment;
            log.info("[EnglishGuard] pending merged, reason={}, pendingWords={}, segmentWords={}, mergedWords={}, pendingLang={}, lang={}, pendingSpeakerId={}, speakerId={}, text='{}'",
                    reason, enGuard.wordCount(pending), enGuard.wordCount(cleanSegment), enGuard.wordCount(merged),
                    pendingEnLang, lang, pendingEnSpeakerId, speakerId, previewText(merged));
            pendingEnText = "";
            pendingEnLang = "";
            pendingEnSpeakerId = "";
            return merged.trim();
        }

        private void storePendingEnglishText(String segment, String lang, String speakerId, String reason) {
            if (!isEnglishGuardActiveFor(lang)) {
                return;
            }
            String cleanSegment = segment == null ? "" : segment.trim();
            if (cleanSegment.isBlank()) {
                return;
            }
            if (!pendingEnText.isBlank()) {
                cleanSegment = pendingEnText.trim() + " " + cleanSegment;
            }
            pendingEnText = cleanSegment.trim();
            pendingEnLang = lang == null ? "" : lang;
            pendingEnSpeakerId = speakerId == null ? "" : speakerId;
            log.info("[EnglishGuard] HOLD final remainder reason={} words={} lang={} speakerId={} text='{}'",
                    reason, enGuard.wordCount(pendingEnText), lang, speakerId, previewText(pendingEnText));
        }

        /**
         * 将标点文本中的位置 punctuatedEnd 映射回原始文本的字符下标。
         * CT-Transformer 只在字符间插入标点，原始字符按原顺序保留。
         * 双指针扫描：两者相同的字符同步推进；punctuated 中多出的字符视为插入标点，只推进 punctuated 指针。
         */
        private int mapPunctuatedToOriginal(String original, String punctuated, int punctuatedEnd) {
            int origIdx = 0;
            int pIdx = 0;
            int pEnd = Math.min(punctuatedEnd, punctuated.length());
            while (pIdx < pEnd && origIdx < original.length()) {
                int pCp = punctuated.codePointAt(pIdx);
                int oCp = original.codePointAt(origIdx);
                if (pCp == oCp) {
                    pIdx += Character.charCount(pCp);
                    origIdx += Character.charCount(oCp);
                } else {
                    // 插入的标点：只推进 punctuated 指针
                    pIdx += Character.charCount(pCp);
                }
            }
            return origIdx;
        }

        private int commonPrefixLen(String a, String b) {
            int n = Math.min(a.length(), b.length());
            int i = 0;
            while (i < n && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            return i;
        }

        private boolean shouldDeferShortSegment(String reason, String segment, String lang) {
            if (segment == null || segment.isBlank()) {
                return false;
            }
            if (isChineseSegment(segment, 0, lang) && minSentenceEmitZhChars > 0
                    && visibleCharCount(segment) < minSentenceEmitZhChars) {
                return isForceEmitReason(reason);
            }
            if (minSentenceEmitIdChars > 0 && startsWithIgnoreCase(lang, "id")) {
                if (visibleCharCount(segment) >= minSentenceEmitIdChars) {
                    return false;
                }
                return isForceEmitReason(reason);
            }
            return false;
        }

        private boolean isForceEmitReason(String reason) {
            return switch (reason) {
                case "sentence", "sentence-punct", "sentence-wtpsplit", "numbered-title",
                     "force-boundary", "force-comma", "force-comma-punct", "force-backstop",
                     "llm-boundary" -> true;
                default -> false;
            };
        }

        private int hardBackstopBoundary(String working, String lang) {
            int hardSafe = working.length() - FORCE_TAIL_MARGIN_CHARS;
            if (hardSafe <= 0) {
                return NO_SEGMENT;
            }
            if (isChineseSegment(working, 0, lang)) {
                int sentenceEnd = findSentenceSegmentEnd(working.substring(0, hardSafe), 0);
                if (sentenceEnd != NO_SEGMENT) {
                    return sentenceEnd;
                }
                return findCommaSegmentEnd(working, 0, hardSafe);
            }
            int limit = hardSafe;
            int lengthLimit = findLengthLimitSegmentEnd(working, 0, lang);
            if (lengthLimit > 0) {
                limit = Math.min(limit, lengthLimit);
            }
            if (startsWithIgnoreCase(lang, "id") && minSentenceEmitIdChars > 0
                    && limit < minSentenceEmitIdChars && hardSafe >= minSentenceEmitIdChars) {
                limit = minSentenceEmitIdChars;
            }
            return wordOrCharBoundaryAt(working, limit, lang);
        }

        /** 是否触发长句强切: 中文超字数 / 拉丁超词数 / 任意超总字符, 或 超时。 */
        private boolean shouldForce(String w, String lang) {
            if (isEnglishGuardActiveFor(lang)) {
                return false;
            }
            boolean over = false;
            if (maxSegmentZhChars > 0 && isChineseSegment(w, 0, lang)) {
                over = visibleCharCount(w) >= maxSegmentZhChars;
            } else if (maxSegmentWords > 0 && isWordSegment(w, 0, lang)) {
                over = wordCount(w) >= maxSegmentWords;
            } else if (maxSegmentChars > 0) {
                over = visibleCharCount(w) >= maxSegmentChars;
            }
            if (over) {
                return true;
            }
            long startedAt = segmentStartMs.get();
            return forceSegmentMs > 0 && startedAt > 0
                    && System.currentTimeMillis() - startedAt >= forceSegmentMs;
        }

        /** 在 safe 处回退到安全边界: 拉丁文回退到上一个空格(无空格不切, 避免割裂单词); CJK 直接用 safe(字边界)。 */
        private int wordOrCharBoundaryAt(String w, int safe, String lang) {
            if (isWordSegment(w, 0, lang)) {
                for (int i = safe; i > 0; i--) {
                    if (Character.isWhitespace(w.charAt(i - 1))) {
                        return i;
                    }
                }
                return NO_SEGMENT;
            }
            return safe;
        }

        private int visibleCharCount(String text) {
            int count = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                if (!Character.isWhitespace(cp)) {
                    count++;
                }
                i += Character.charCount(cp);
            }
            return count;
        }

        private String previewText(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= 120 ? text : text.substring(0, 117) + "...";
        }

        private int wordCount(String text) {
            String t = text.trim();
            return t.isEmpty() ? 0 : t.split("\\s+").length;
        }

        /** run-on 兜底：返回 [startIndex, len-尾余量) 内"最后一个逗号"之后的位置；无逗号返回 NO_SEGMENT(不切)。 */
        private int findCommaSegmentEnd(String text, int startIndex) {
            return findCommaSegmentEnd(text, startIndex, Integer.MAX_VALUE);
        }

        private int findCommaSegmentEnd(String text, int startIndex, int maxDetEnd) {
            int safeEnd = Math.min(text.length() - FORCE_TAIL_MARGIN_CHARS, maxDetEnd);
            if (safeEnd <= startIndex) {
                return NO_SEGMENT;
            }
            int lastComma = NO_SEGMENT;
            for (int i = startIndex; i < safeEnd; ) {
                int codePoint = text.codePointAt(i);
                int nextIndex = i + Character.charCount(codePoint);
                if (SEGMENT_COMMA_PUNCTUATION.indexOf(codePoint) >= 0) {
                    lastComma = nextIndex;
                }
                i = nextIndex;
            }
            if (lastComma == NO_SEGMENT) {
                return NO_SEGMENT;
            }
            return consumeClosingPunctuationAndWhitespace(text, lastComma);
        }

        private int findSentenceSegmentEnd(String text, int startIndex) {
            for (int i = startIndex; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                int nextIndex = i + Character.charCount(codePoint);
                if (isSentenceEnd(codePoint) && !isInNumberContext(text, i)
                        && isLikelySentenceBoundary(text, nextIndex, codePoint)) {
                    return consumeClosingPunctuationAndWhitespace(text, nextIndex);
                }
                i = nextIndex;
            }
            return NO_SEGMENT;
        }

        private boolean isSentenceEnd(int codePoint) {
            return ASCII_SENTENCE_END_PUNCTUATION.indexOf(codePoint) >= 0
                    || codePoint == 0x3002
                    || codePoint == 0xFF01
                    || codePoint == 0xFF1F
                    || codePoint == 0xFF1B
                    || codePoint == 0x2026;
        }

        /**
         * 检测标点位置是否处于数字语境（CT-Transformer 会在数字中插入空格，如 `0 . 1`、`6 . 6`）。
         * 支持 ASCII 小数点（`.`）和 CT-Transformer 在数字末尾误插的中文句号（`。` 0x3002）。
         * 对 `.`：前后跳过空格后都是数字（或后跟 `%`），则视为小数点而非句末。
         * 对 `。`：紧前跳过空格后是数字或 `%`，则视为数字结尾处误插标点而非真实句末。
         */
        private boolean isInNumberContext(String text, int index) {
            int codePoint = text.codePointAt(index);
            if (codePoint == '.') {
                // 向前跳过空格，找前一个非空白字符
                int prev = index - 1;
                while (prev >= 0 && text.charAt(prev) == ' ') prev--;
                if (prev < 0 || !Character.isDigit(text.charAt(prev))) return false;
                // 向后跳过空格，找后一个非空白字符
                int next = index + 1;
                while (next < text.length() && text.charAt(next) == ' ') next++;
                if (next >= text.length()) return false;
                char nc = text.charAt(next);
                return Character.isDigit(nc) || nc == '%';
            }
            if (codePoint == 0x3002) { // 中文句号 `。`
                // 前一个非空白字符是数字或 %（CT-Transformer 在数字末尾误插的 `。`）
                int prev = index - 1;
                while (prev >= 0 && text.charAt(prev) == ' ') prev--;
                if (prev < 0) return false;
                char pc = text.charAt(prev);
                return Character.isDigit(pc) || pc == '%';
            }
            return false;
        }

        private boolean isLikelySentenceBoundary(String text, int nextIndex, int codePoint) {
            if (codePoint > 127 || nextIndex >= text.length()) {
                return true;
            }
            int nextCodePoint = text.codePointAt(nextIndex);
            return Character.isWhitespace(nextCodePoint) || isClosingPunctuation(nextCodePoint);
        }

        private int consumeClosingPunctuationAndWhitespace(String text, int startIndex) {
            int index = startIndex;
            while (index < text.length()) {
                int codePoint = text.codePointAt(index);
                if (!Character.isWhitespace(codePoint) && !isClosingPunctuation(codePoint)) {
                    break;
                }
                index += Character.charCount(codePoint);
            }
            return index;
        }

        private boolean isClosingPunctuation(int codePoint) {
            return ASCII_CLOSING_PUNCTUATION.indexOf(codePoint) >= 0
                    || codePoint == 0x201D
                    || codePoint == 0x2019
                    || codePoint == 0xFF09
                    || codePoint == 0x3011
                    || codePoint == 0x300B
                    || codePoint == 0x300D
                    || codePoint == 0x300F;
        }

        private int findLengthLimitSegmentEnd(String text, int startIndex, String lang) {
            if (maxSegmentZhChars > 0 && isChineseSegment(text, startIndex, lang)) {
                return findVisibleCharLimitEnd(text, startIndex, maxSegmentZhChars);
            }
            if (maxSegmentWords > 0 && isWordSegment(text, startIndex, lang)) {
                return findWordLimitEnd(text, startIndex, maxSegmentWords);
            }
            if (maxSegmentChars > 0) {
                return findVisibleCharLimitEnd(text, startIndex, maxSegmentChars);
            }
            return NO_SEGMENT;
        }

        private boolean isChineseSegment(String text, int startIndex, String lang) {
            boolean hasCjk = containsCjk(text, startIndex);
            return hasCjk || (startsWithIgnoreCase(lang, "zh") && !containsLatinLetter(text, startIndex));
        }

        private boolean isWordSegment(String text, int startIndex, String lang) {
            return startsWithIgnoreCase(lang, "en")
                    || startsWithIgnoreCase(lang, "id")
                    || startsWithIgnoreCase(lang, "in")
                    || containsLatinLetter(text, startIndex);
        }

        private boolean startsWithIgnoreCase(String text, String prefix) {
            return text != null && text.regionMatches(true, 0, prefix, 0, prefix.length());
        }

        private boolean containsCjk(String text, int startIndex) {
            for (int i = startIndex; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HANGUL) {
                    return true;
                }
                i += Character.charCount(codePoint);
            }
            return false;
        }

        private boolean containsLatinLetter(String text, int startIndex) {
            for (int i = startIndex; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                if (Character.isLetter(codePoint)
                        && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) {
                    return true;
                }
                i += Character.charCount(codePoint);
            }
            return false;
        }

        private int findVisibleCharLimitEnd(String text, int startIndex, int maxChars) {
            int count = 0;
            for (int i = startIndex; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                int nextIndex = i + Character.charCount(codePoint);
                if (!Character.isWhitespace(codePoint) && ++count >= maxChars) {
                    return nextIndex;
                }
                i = nextIndex;
            }
            return NO_SEGMENT;
        }

        private int findWordLimitEnd(String text, int startIndex, int maxWords) {
            int words = 0;
            boolean inWord = false;
            for (int i = startIndex; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                int nextIndex = i + Character.charCount(codePoint);
                if (isWordCodePoint(codePoint)) {
                    if (!inWord) {
                        words++;
                        inWord = true;
                    }
                } else {
                    if (words >= maxWords) {
                        return nextIndex;
                    }
                    inWord = false;
                }
                i = nextIndex;
            }
            return words >= maxWords ? text.length() : NO_SEGMENT;
        }

        private boolean isWordCodePoint(int codePoint) {
            return Character.isLetterOrDigit(codePoint)
                    || codePoint == '\''
                    || codePoint == '-'
                    || codePoint == 0x2019;
        }

        private void applyHotwords() {
            if (hotwords == null || hotwords.isEmpty()) {
                log.info("[AsrSession] loaded hotwords, count=0");
                return;
            }
            PhraseListGrammar phraseList = PhraseListGrammar.fromRecognizer(conversationTranscriber);
            for (String hotword : hotwords) {
                if (hotword != null && !hotword.isBlank()) {
                    phraseList.addPhrase(hotword);
                }
            }
            phraseList.setWeight(1.0);
            log.info("[AsrSession] loaded hotwords, count={}", hotwords.size());
        }

        private String resolveSpeakerId(ConversationTranscriptionResult result) {
            String speakerId = result.getSpeakerId();
            if (speakerId == null || speakerId.isBlank()) {
                return Constants.SPEAKER_ID_UNKNOWN;
            }
            // 更新有效 speakerId 缓存，供强制分段补全 Unknown 使用
            lastValidSpeakerId.set(speakerId);
            return speakerId;
        }

        /** 当强制分段来自 interim 事件（speakerId 可能为 Unknown）时，用上一次有效 ID 补全 */
        private String resolveSegmentSpeakerId(String speakerId) {
            if (!Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(speakerId)) {
                return speakerId;
            }
            String last = lastValidSpeakerId.get();
            return (last != null && !last.isBlank()) ? last : speakerId;
        }

        private String resolveDetectedLanguage(SpeechRecognitionResult result) {
            if (autoDetectEnabled) {
                AutoDetectSourceLanguageResult autoResult = AutoDetectSourceLanguageResult.fromResult(result);
                String lang = autoResult.getLanguage();
                if (lang != null && !lang.isBlank()) {
                    detectedLang.set(lang);
                    return lang;
                }
                String previousLang = detectedLang.get();
                if (previousLang != null && !previousLang.isBlank()) {
                    return previousLang;
                }
            }
            return config.getSpeechRecognitionLanguage() != null
                    ? config.getSpeechRecognitionLanguage()
                    : Constants.LANG_ZH_CN;
        }

        public String recognizeOnce() throws Exception {
            log.info("[AsrSession] recognizeOnce start");
            if (recognizer == null) {
                throw BizException.of(ErrorCode.ASR_RECOGNIZE_ERROR, "ConversationTranscriber 不支持 recognizeOnce");
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("");
            AtomicReference<String> lang = new AtomicReference<>("");
            AtomicBoolean done = new AtomicBoolean(false);

            recognizer.recognized.addEventListener((s, e) -> {
                if (!done.compareAndSet(false, true)) return;
                if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                    result.set(e.getResult().getText());
                    lang.set(resolveDetectedLanguage(e.getResult()));
                }
                latch.countDown();
            });
            recognizer.canceled.addEventListener((s, e) -> {
                if (!done.compareAndSet(false, true)) return;
                latch.countDown();
            });

            long start = System.currentTimeMillis();
            recognizer.recognizeOnceAsync().get(10, TimeUnit.SECONDS);
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            long cost = System.currentTimeMillis() - start;

            if (!completed) {
                throw BizException.of(ErrorCode.ASR_RECOGNIZE_ERROR, Constants.ASR_TIMEOUT_ERROR);
            }
            detectedLang.set(lang.get());
            finalText.set(result.get());

            log.info("[AsrSession] recognizeOnce end, textLen={}, lang={}, costMs={}",
                    result.get() != null ? result.get().length() : 0, lang.get(), cost);
            return result.get();
        }

        public String getFinalText() {
            return finalText.get();
        }

        public String getDetectedLang() {
            return detectedLang.get();
        }

        @Override
        public void close() {
            if (!pendingIdFloorText.isBlank()) {
                log.warn("[IdGuard] pending output-floor not emitted on close, len={} minId={} lang={} speakerId={} text='{}'",
                        visibleCharCount(pendingIdFloorText), minSentenceEmitIdChars,
                        pendingIdFloorLang, pendingIdFloorSpeakerId, previewText(pendingIdFloorText));
            }
            if (!pendingEnText.isBlank()) {
                log.warn("[EnglishGuard] pending text not emitted on close, words={} lang={} speakerId={} text='{}'",
                        enGuard != null ? enGuard.wordCount(pendingEnText) : wordCount(pendingEnText),
                        pendingEnLang, pendingEnSpeakerId, previewText(pendingEnText));
            }
            try {
                if (conversationTranscriber != null) {
                    conversationTranscriber.stopTranscribingAsync().get(5, TimeUnit.SECONDS);
                } else if (recognizer != null) {
                    recognizer.stopContinuousRecognitionAsync().get(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("[AsrSession] stop recognizer error", e);
            }
            if (conversationTranscriber != null) {
                conversationTranscriber.close();
            }
            if (recognizer != null) {
                recognizer.close();
            }
            pushStream.close();
            config.close();
        }
    }

    static FinalRemainderResult finalRemainderAfterForcedSegments(
            String full,
            int emittedLen,
            String emittedSuffix,
            String emittedText
    ) {
        if (full == null || full.isBlank()) {
            return new FinalRemainderResult("", 0, 0, false);
        }
        if (emittedLen <= 0) {
            return new FinalRemainderResult(full.trim(), 0, 0, false);
        }
        int fallbackCut = Math.min(emittedLen, full.length());
        BoundaryAlignment alignment = alignFinalBoundaryByEmittedText(full, fallbackCut, emittedText);
        if (!alignment.aligned()) {
            alignment = alignFinalBoundaryBySuffix(full, fallbackCut, emittedSuffix);
        }
        int cutIndex = alignment.aligned() ? alignment.cutIndex() : 0;
        String remaining = full.substring(Math.min(cutIndex, full.length())).trim();
        String overlapReference = emittedText != null && !emittedText.isBlank() ? emittedText : emittedSuffix;
        OverlapTrimResult trimmed = trimRepeatedLeadingOverlap(overlapReference, remaining);
        return new FinalRemainderResult(trimLeadingSeparators(trimmed.text()), cutIndex, trimmed.overlapChars(), alignment.aligned());
    }

    private static BoundaryAlignment alignFinalBoundaryByEmittedText(String full, int fallbackCut, String emittedText) {
        if (emittedText == null || emittedText.isBlank()) {
            return new BoundaryAlignment(fallbackCut, false);
        }
        int searchUntil = Math.min(full.length(), fallbackCut + FINAL_REALIGN_WINDOW_CHARS);
        BoundaryAlignment comparable = alignFinalBoundaryByComparableSuffix(full, searchUntil, emittedText);
        if (comparable.aligned()) {
            return comparable;
        }
        return new BoundaryAlignment(fallbackCut, false);
    }

    private static BoundaryAlignment alignFinalBoundaryBySuffix(String full, int fallbackCut, String emittedSuffix) {
        if (emittedSuffix == null || emittedSuffix.isBlank()) {
            return new BoundaryAlignment(fallbackCut, false);
        }
        int searchUntil = Math.min(full.length(), fallbackCut + FINAL_REALIGN_WINDOW_CHARS);
        int exact = full.lastIndexOf(emittedSuffix, searchUntil);
        if (exact >= 0) {
            return new BoundaryAlignment(exact + emittedSuffix.length(), true);
        }
        int caseInsensitive = full.toLowerCase(Locale.ROOT)
                .lastIndexOf(emittedSuffix.toLowerCase(Locale.ROOT), searchUntil);
        if (caseInsensitive >= 0) {
            return new BoundaryAlignment(caseInsensitive + emittedSuffix.length(), true);
        }
        BoundaryAlignment comparable = alignFinalBoundaryByComparableSuffix(full, searchUntil, emittedSuffix);
        if (comparable.aligned()) {
            return comparable;
        }
        return new BoundaryAlignment(fallbackCut, false);
    }

    private static BoundaryAlignment alignFinalBoundaryByComparableSuffix(String full, int searchUntil, String emittedSuffix) {
        ComparisonText fullComparison = comparableText(full.substring(0, searchUntil));
        int[] suffix = comparableCodePoints(emittedSuffix);
        if (suffix.length == 0 || suffix.length > fullComparison.codePoints().length) {
            return new BoundaryAlignment(0, false);
        }
        for (int start = fullComparison.codePoints().length - suffix.length; start >= 0; start--) {
            boolean matches = true;
            for (int i = 0; i < suffix.length; i++) {
                if (fullComparison.codePoints()[start + i] != suffix[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return new BoundaryAlignment(fullComparison.sourceEndOffsets()[start + suffix.length - 1], true);
            }
        }
        return new BoundaryAlignment(0, false);
    }

    private static OverlapTrimResult trimRepeatedLeadingOverlap(String emittedSuffix, String currentText) {
        if (currentText == null || currentText.isBlank()) {
            return new OverlapTrimResult("", 0);
        }
        if (emittedSuffix == null || emittedSuffix.isBlank()) {
            return new OverlapTrimResult(currentText.trim(), 0);
        }
        int[] previous = comparableCodePoints(emittedSuffix);
        String remaining = currentText.trim();
        int totalOverlap = 0;
        while (!remaining.isBlank()) {
            int[] current = comparableCodePoints(remaining);
            int overlap = longestSuffixPrefixOverlap(previous, current);
            if (overlap < requiredRemainderOverlap(previous, current, overlap)) {
                break;
            }
            totalOverlap += overlap;
            if (overlap == current.length) {
                remaining = "";
                break;
            }
            int sourceCutOffset = sourceOffsetAfterComparableCodePoints(remaining, overlap);
            remaining = trimLeadingSeparators(remaining.substring(sourceCutOffset));
        }
        return new OverlapTrimResult(remaining, totalOverlap);
    }

    private static int requiredRemainderOverlap(int[] previous, int[] current, int overlap) {
        if (overlap == 1 && previous.length > 0 && current.length > 0) {
            int lastPrevious = previous[previous.length - 1];
            if (lastPrevious == current[0] && Character.isDigit(lastPrevious)) {
                return 1;
            }
        }
        return MIN_FINAL_REMAINDER_OVERLAP_CHARS;
    }

    /** 账本去重的最小判定长度（归一化字符）：候选短于此不参与"整段重复"判定，避免误丢真·短重复句。 */
    private static final int LEDGER_MIN_DUP_CHARS = 20;
    /** 账本去重的最小重叠长度（归一化字符）：前缀与账本尾部重叠达到此值才裁剪，避免巧合短重叠误裁。 */
    private static final int LEDGER_MIN_OVERLAP_CHARS = 12;

    /** 把文本归一化成可比字符串（小写、去标点空白、数字词→数字），用于账本去重比对。 */
    static String comparableString(String text) {
        int[] cps = comparableCodePoints(text);
        StringBuilder sb = new StringBuilder(cps.length);
        for (int cp : cps) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /**
     * 账本去重（纯函数，可单测）：{@code ledgerNorm} 为已发文本的归一化账本，{@code segment} 为候选原文。
     * <ul>
     *   <li>候选归一化后整段已在账本中 → 返回 ""（完全重复，调用方丢弃）；</li>
     *   <li>候选前缀与账本尾部重叠 ≥ 阈值 → 裁掉重叠前缀，返回剩余原文；</li>
     *   <li>否则原样返回。</li>
     * </ul>
     */
    static String dedupEmitAgainstLedger(String ledgerNorm, String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        if (ledgerNorm == null || ledgerNorm.isEmpty()) {
            return segment;
        }
        String candNorm = comparableString(segment);
        if (candNorm.length() < LEDGER_MIN_DUP_CHARS) {
            return segment;   // 太短，不做去重，避免误伤合法短重复（如多次"谢谢"）
        }
        if (ledgerNorm.contains(candNorm)) {
            return "";        // 整段已发过
        }
        int overlap = longestLedgerOverlap(ledgerNorm, candNorm);
        if (overlap >= LEDGER_MIN_OVERLAP_CHARS) {
            int srcCut = sourceOffsetAfterComparableCodePoints(segment, overlap);
            return trimLeadingSeparators(segment.substring(Math.min(srcCut, segment.length())));
        }
        return segment;
    }

    /** 账本尾部与候选前缀的最长重叠（归一化字符数）；不足 {@link #LEDGER_MIN_OVERLAP_CHARS} 返回 0。 */
    private static int longestLedgerOverlap(String ledger, String cand) {
        int max = Math.min(ledger.length(), cand.length());
        for (int k = max; k >= LEDGER_MIN_OVERLAP_CHARS; k--) {
            if (ledger.regionMatches(ledger.length() - k, cand, 0, k)) {
                return k;
            }
        }
        return 0;
    }

    private static int[] comparableCodePoints(String text) {
        return comparableText(text).codePoints();
    }

    private static ComparisonText comparableText(String text) {
        List<Integer> codePoints = new ArrayList<>();
        List<Integer> sourceEndOffsets = new ArrayList<>();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) && isLatinOrDigit(codePoint)) {
                int tokenEnd = nextOffset;
                while (tokenEnd < text.length()) {
                    int nextCodePoint = text.codePointAt(tokenEnd);
                    if (!Character.isLetterOrDigit(nextCodePoint) || !isLatinOrDigit(nextCodePoint)) {
                        break;
                    }
                    tokenEnd += Character.charCount(nextCodePoint);
                }
                String normalizedToken = normalizeComparableToken(text.substring(offset, tokenEnd));
                for (int tokenOffset = 0; tokenOffset < normalizedToken.length(); ) {
                    int normalizedCodePoint = normalizedToken.codePointAt(tokenOffset);
                    codePoints.add(normalizedCodePoint);
                    sourceEndOffsets.add(tokenEnd);
                    tokenOffset += Character.charCount(normalizedCodePoint);
                }
                offset = tokenEnd;
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                codePoints.add(Character.toLowerCase(codePoint));
                sourceEndOffsets.add(nextOffset);
                offset = nextOffset;
                continue;
            }
            offset = nextOffset;
        }
        return new ComparisonText(
                codePoints.stream().mapToInt(Integer::intValue).toArray(),
                sourceEndOffsets.stream().mapToInt(Integer::intValue).toArray()
        );
    }

    private static boolean isLatinOrDigit(int codePoint) {
        return Character.isDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static String normalizeComparableToken(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "zero", "nol", "kosong" -> "0";
            case "one", "satu" -> "1";
            case "two", "dua" -> "2";
            case "three", "tiga" -> "3";
            case "four", "empat" -> "4";
            case "five", "lima" -> "5";
            case "six", "enam" -> "6";
            case "seven", "tujuh" -> "7";
            case "eight", "delapan" -> "8";
            case "nine", "sembilan" -> "9";
            case "ten", "sepuluh" -> "10";
            default -> normalized;
        };
    }

    private static int sourceOffsetAfterComparableCodePoints(String text, int count) {
        ComparisonText comparison = comparableText(text);
        if (count <= 0 || comparison.sourceEndOffsets().length == 0) {
            return 0;
        }
        int index = Math.min(count, comparison.sourceEndOffsets().length) - 1;
        return comparison.sourceEndOffsets()[index];
    }

    private static int longestSuffixPrefixOverlap(int[] previous, int[] current) {
        for (int length = Math.min(previous.length, current.length); length > 0; length--) {
            int previousStart = previous.length - length;
            boolean matches = true;
            for (int i = 0; i < length; i++) {
                if (previous[previousStart + i] != current[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return length;
            }
        }
        return 0;
    }

    private static String trimLeadingSeparators(String text) {
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        return text.substring(offset).trim();
    }

    record FinalRemainderResult(String text, int cutIndex, int overlapChars, boolean aligned) {
    }

    private record BoundaryAlignment(int cutIndex, boolean aligned) {
    }

    private record OverlapTrimResult(String text, int overlapChars) {
    }

    private record ComparisonText(int[] codePoints, int[] sourceEndOffsets) {
    }

    public interface RecognizerCallback {
        void onRecognizing(String text, String language, String speakerId, boolean isFinal);
        void onError(String errorMessage);
    }
}
