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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
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

    private final AzureSpeechProperties asrProperties;
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    public AzureAsrIntegration(AzureSpeechProperties asrProperties) {
        this.asrProperties = asrProperties;
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
        log.info("[AzureAsrIntegration] ASR silence config, endSilenceMs={}, segmentationSilenceMs={}, segmentationStrategy={}, segmentationMaxTimeMs={}, forceSegmentMs={}, sentenceSegmentation={}, maxSegmentZhChars={}, maxSegmentWords={}, maxSegmentChars={}",
                asrProperties.getAsr().getEndSilenceTimeoutMs(),
                asrProperties.getAsr().getSegmentationSilenceTimeoutMs(),
                asrProperties.getAsr().getSegmentationStrategy(),
                asrProperties.getAsr().getSegmentationMaximumTimeMs(),
                asrProperties.getAsr().getForceSegmentMs(),
                asrProperties.getAsr().isSentenceSegmentationEnabled(),
                asrProperties.getAsr().getMaxSegmentZhChars(),
                asrProperties.getAsr().getMaxSegmentWords(),
                asrProperties.getAsr().getMaxSegmentChars());

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
            session = new AsrSession(config, autoConfig, pushStream, asrConfig, hotwords);
        } else {
            log.info("[AzureAsrIntegration] creating session with ConversationTranscriber, specified lang={}, sessionId={}", sourceLang, sessionId);
            config.setSpeechRecognitionLanguage(sourceLang);
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            session = new AsrSession(config, audioConfig, pushStream, asrConfig, hotwords);
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
        /** 当前未提交段落的开始时刻（ms）；emit 或 final 后重置，用于按时间强切 */
        private final java.util.concurrent.atomic.AtomicLong segmentStartMs = new java.util.concurrent.atomic.AtomicLong(0);
        /** 当前句已发出(最终)的字符数, 单调只增, 只在 segLock 内改 → 绝不重发已发文本(防失控刷段) */
        private int emittedLen = 0;
        /** 上一次中间结果全文(算稳定前缀, 连续两次未变=Azure已确认) */
        private String prevText = "";
        /** 序列化 中间/最终 结果处理(Azure 事件可能在不同线程派发) */
        private final Object segLock = new Object();
        /** 上一次 Azure 返回的有效 speakerId，用于补全 interim 阶段 Unknown 的强制分段 */
        private final AtomicReference<String> lastValidSpeakerId = new AtomicReference<>("");
        /** 上一次 transcribing(中间结果) 时间戳，用于估算 ASR 句末延迟（最后中间结果→isFinal） */
        private final java.util.concurrent.atomic.AtomicLong lastInterimAtMs = new java.util.concurrent.atomic.AtomicLong(0);

        private final List<String> hotwords;

        public AsrSession(SpeechConfig config, AudioConfig audioConfig, PushAudioInputStream pushStream, AzureSpeechProperties.AsrProperties asrConfig, List<String> hotwords) {
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
            this.hotwords = hotwords;
            this.conversationTranscriber = new ConversationTranscriber(config, audioConfig);
        }

        public AsrSession(SpeechConfig config, AutoDetectSourceLanguageConfig autoConfig, PushAudioInputStream pushStream, AzureSpeechProperties.AsrProperties asrConfig, List<String> hotwords) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = true;
            this.diarizationEnabled = true;
            this.sentenceSegmentationEnabled = asrConfig.isSentenceSegmentationEnabled();
            this.maxSegmentZhChars = asrConfig.getMaxSegmentZhChars();
            this.maxSegmentWords = asrConfig.getMaxSegmentWords();
            this.maxSegmentChars = asrConfig.getMaxSegmentChars();
            this.forceSegmentMs = asrConfig.getForceSegmentMs();
            this.hotwords = hotwords;
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
                // 强切 + 取未发出的中间结果, 全程加锁 + emittedLen 单调推进(防失控刷段/竞态)
                String interimText;
                synchronized (segLock) {
                    emitForcedSegments(text, lang, speakerId);
                    interimText = text.substring(Math.min(emittedLen, text.length())).trim();
                }
                if (!interimText.isBlank()) {
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
                // 最终结果: 按 emittedLen 取已强切之后的剩余部分(单调下标, 不重发已发文本)
                String full = text == null ? "" : text;
                String remainder;
                boolean hadForced;
                synchronized (segLock) {
                    hadForced = emittedLen > 0;
                    remainder = full.substring(Math.min(emittedLen, full.length())).trim();
                    emittedLen = 0;
                    prevText = "";
                }
                segmentStartMs.set(0);
                long lastInterim = lastInterimAtMs.getAndSet(0);
                long asrTailMs = lastInterim > 0 ? System.currentTimeMillis() - lastInterim : -1;
                if (!remainder.isBlank()) {
                    callback.onRecognizing(remainder, lang, speakerId, true);
                    log.info("[AsrSession] asr final {}, costMs={}, len={}, speakerId={}",
                            hadForced ? "remainder(after force)" : "latency", asrTailMs, remainder.length(), speakerId);
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
            int start = emittedLen;
            if (start >= text.length()) {
                return;
            }
            String working = text.substring(start);
            int stableInWorking = Math.max(0, stableAbs - start);
            int safe = Math.min(stableInWorking, working.length() - FORCE_TAIL_MARGIN_CHARS);
            if (safe <= 0) {
                return;
            }
            int end = NO_SEGMENT;
            String reason = null;
            // 1) 句末标点(最早出现, 必须落在安全区内)
            if (sentenceSegmentationEnabled) {
                int e = findSentenceSegmentEnd(working, 0);
                if (e != NO_SEGMENT && e <= safe) {
                    end = e;
                    reason = "sentence";
                }
            }
            // 2) 长句强切(超字数/词数 或 超时): 优先逗号, 否则词/字边界
            if (end == NO_SEGMENT && shouldForce(working, lang)) {
                int c = findCommaSegmentEnd(working, 0);
                if (c != NO_SEGMENT && c <= safe) {
                    end = c;
                    reason = "force-comma";
                } else {
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
            String segment = working.substring(0, end).trim();
            emittedLen = start + end;   // 单调推进(下标), 下次从这里继续, 不会回头重切
            segmentStartMs.set(System.currentTimeMillis());
            if (!segment.isBlank()) {
                String resolvedSpeakerId = resolveSegmentSpeakerId(speakerId);
                log.info("[AsrSession] force-segment by {}, len={}, lang={}, speakerId={}",
                        reason, segment.length(), lang, resolvedSpeakerId);
                callback.onRecognizing(segment, lang, resolvedSpeakerId, true);
            }
        }

        private int commonPrefixLen(String a, String b) {
            int n = Math.min(a.length(), b.length());
            int i = 0;
            while (i < n && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            return i;
        }

        /** 是否触发长句强切: 中文超字数 / 拉丁超词数 / 任意超总字符, 或 超时。 */
        private boolean shouldForce(String w, String lang) {
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

        private int wordCount(String text) {
            String t = text.trim();
            return t.isEmpty() ? 0 : t.split("\\s+").length;
        }

        /** run-on 兜底：返回 [startIndex, len-尾余量) 内"最后一个逗号"之后的位置；无逗号返回 NO_SEGMENT(不切)。 */
        private int findCommaSegmentEnd(String text, int startIndex) {
            int safeEnd = text.length() - FORCE_TAIL_MARGIN_CHARS;
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
                if (isSentenceEnd(codePoint) && !isDecimalPoint(text, i)
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

        private boolean isDecimalPoint(String text, int index) {
            return text.charAt(index) == '.'
                    && index > 0
                    && index + 1 < text.length()
                    && Character.isDigit(text.charAt(index - 1))
                    && Character.isDigit(text.charAt(index + 1));
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

    public interface RecognizerCallback {
        void onRecognizing(String text, String language, String speakerId, boolean isFinal);
        void onError(String errorMessage);
    }
}
