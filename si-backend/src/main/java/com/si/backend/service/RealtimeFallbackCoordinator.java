package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.config.OpenAiRealtimeProperties;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration.RealtimeTranslationListener;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration.RealtimeTranslationSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeFallbackCoordinator {

    private static final String FALLBACK_SPEAKER_ID = "OpenAI";
    private static final String DEFAULT_REALTIME_SESSION_LANGUAGE = Constants.LANG_ZH_CN;
    private static final Set<Character> SENTENCE_ENDINGS = Set.of(
            '.', '!', '?', ';', '。', '！', '？', '；'
    );

    private static final ScheduledExecutorService FLUSH_EXECUTOR = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("realtime-fallback-flush-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });

    private final OpenAiRealtimeProperties properties;
    private final OpenAiRealtimeTranslateIntegration openAiRealtimeIntegration;
    private final Map<String, FallbackSessionState> sessions = new ConcurrentHashMap<>();

    public void startSession(
            String sessionId,
            List<String> targetLanguages,
            RealtimeFallbackCallbacks callbacks
    ) {
        log.info("[RealtimeFallbackCoordinator] startSession start, sessionId={}, targets={}, enabled={}",
                sessionId, targetLanguages, properties.isEnabled());
        stopSession(sessionId);
        FallbackSessionState state = new FallbackSessionState(sessionId, normalizeTargetLanguages(targetLanguages), callbacks);
        sessions.put(sessionId, state);
        if (!properties.isUsable()) {
            state.emitStatus(false, "OpenAI Realtime disabled or api key missing");
            log.info("[RealtimeFallbackCoordinator] startSession skipped, sessionId={}, reason=unusable", sessionId);
            return;
        }
        String realtimeSessionLanguage = state.realtimeSessionLanguage();
        RealtimeTranslationSession openAiSession = openAiRealtimeIntegration.openSession(
                sessionId,
                realtimeSessionLanguage,
                new OpenAiSessionListener(state, realtimeSessionLanguage)
        );
        state.openAiSessions.put(realtimeSessionLanguage, openAiSession);
        state.emitStatus(false, "OpenAI Realtime ASR warming up");
        log.info("[RealtimeFallbackCoordinator] startSession end, sessionId={}, targets={}, realtimeSessionLanguage={}",
                sessionId, state.targetLanguages, realtimeSessionLanguage);
    }

    public void pushAudio(String sessionId, byte[] pcmFrame) {
        FallbackSessionState state = sessions.get(sessionId);
        if (state == null || pcmFrame == null || pcmFrame.length == 0 || state.openAiSessions.isEmpty()) {
            return;
        }
        state.openAiSessions.values().forEach(openAiSession -> {
            try {
                openAiSession.sendAudio(pcmFrame);
            } catch (Exception e) {
                log.warn("[RealtimeFallbackCoordinator] OpenAI sendAudio failed, sessionId={}, targetLang={}",
                        sessionId, openAiSession.targetLanguage(), e);
            }
        });
    }

    public void observeSourceLanguage(String sessionId, String sourceLanguage) {
        FallbackSessionState state = sessions.get(sessionId);
        if (state == null || sourceLanguage == null || sourceLanguage.isBlank()) {
            return;
        }
        String normalized = normalizeLanguage(sourceLanguage);
        String previous = state.currentSourceLanguage.getAndSet(normalized);
        if (previous != null && !previous.equals(normalized)) {
            log.info("[RealtimeFallbackCoordinator] source language changed, sessionId={}, {}->{}",
                    sessionId, previous, normalized);
        }
    }

    public boolean shouldEmitPrimaryOutput(String sessionId) {
        FallbackSessionState state = sessions.get(sessionId);
        return state == null || !Constants.REALTIME_ENGINE_OPENAI.equals(state.activeEngine.get());
    }

    public boolean shouldFeedPrimaryAsr(String sessionId) {
        return shouldEmitPrimaryOutput(sessionId);
    }

    public FallbackEngineStatus switchEngine(String sessionId, String requestedEngine) {
        FallbackSessionState state = sessions.get(sessionId);
        if (state == null) {
            return new FallbackEngineStatus(sessionId, Constants.REALTIME_ENGINE_PRIMARY, false,
                    "Session has no fallback state");
        }
        String engine = normalizeEngine(requestedEngine);
        if (Constants.REALTIME_ENGINE_PRIMARY.equals(engine)) {
            state.activeEngine.set(Constants.REALTIME_ENGINE_PRIMARY);
            state.clearTextBuffers();
            FallbackEngineStatus status = state.status(state.hasReadyOpenAiTarget(), "Switched to primary realtime pipeline");
            state.callbacks.onStatus(status);
            log.info("[RealtimeFallbackCoordinator] switched engine, sessionId={}, active={}", sessionId, engine);
            return status;
        }
        if (!state.hasReadyOpenAiTarget()) {
            FallbackEngineStatus status = state.status(false, "OpenAI Realtime is not ready");
            state.callbacks.onStatus(status);
            log.warn("[RealtimeFallbackCoordinator] switch rejected, sessionId={}, requested={}, reason=not_ready",
                    sessionId, requestedEngine);
            return status;
        }
        state.activeEngine.set(Constants.REALTIME_ENGINE_OPENAI);
        state.clearTextBuffers();
        FallbackEngineStatus status = state.status(true, "Switched to OpenAI Realtime ASR");
        state.callbacks.onStatus(status);
        log.info("[RealtimeFallbackCoordinator] switched engine, sessionId={}, active={}", sessionId, engine);
        return status;
    }

    public FallbackEngineStatus currentStatus(String sessionId) {
        FallbackSessionState state = sessions.get(sessionId);
        if (state == null) {
            return new FallbackEngineStatus(sessionId, Constants.REALTIME_ENGINE_PRIMARY, false,
                    "Session has no fallback state");
        }
        return state.status(state.hasReadyOpenAiTarget(), "");
    }

    public void stopSession(String sessionId) {
        FallbackSessionState removed = sessions.remove(sessionId);
        if (removed == null) {
            return;
        }
        log.info("[RealtimeFallbackCoordinator] stopSession start, sessionId={}", sessionId);
        removed.cancelFlush();
        removed.openAiSessions.values().forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("[RealtimeFallbackCoordinator] close OpenAI session failed, sessionId={}, targetLang={}",
                        sessionId, session.targetLanguage(), e);
            }
        });
        removed.openAiSessions.clear();
        removed.readyTargets.clear();
        log.info("[RealtimeFallbackCoordinator] stopSession end, sessionId={}", sessionId);
    }

    private void onOpenAiInputTranscript(FallbackSessionState state, String delta) {
        if (!state.isOpenAiActive()) {
            return;
        }
        String detectedLanguage = detectScriptLanguage(delta);
        if (detectedLanguage != null) {
            state.currentSourceLanguage.set(detectedLanguage);
        }
        synchronized (state.textLock) {
            if (state.segmentStartedAtMs == 0L) {
                state.segmentStartedAtMs = System.currentTimeMillis();
            }
            state.inputTranscript.append(delta);
            String current = state.inputTranscript.toString().trim();
            if (!current.isBlank()) {
                state.callbacks.onRecognizing(current, state.currentSourceLanguage.get(), FALLBACK_SPEAKER_ID);
            }
            state.scheduleFlush(shouldFlushImmediately(delta));
        }
    }

    private void onOpenAiOutputTranscript(FallbackSessionState state, String targetLanguage, String delta) {
        if (state.isOpenAiActive() && log.isDebugEnabled()) {
            log.debug("[RealtimeFallbackCoordinator] discard OpenAI output transcript, sessionId={}, targetLang={}, chars={}",
                    state.sessionId, targetLanguage, delta == null ? 0 : delta.length());
        }
    }

    private void onOpenAiOutputAudio(FallbackSessionState state, String targetLanguage, byte[] pcm24k) {
        if (state.isOpenAiActive() && log.isDebugEnabled()) {
            log.debug("[RealtimeFallbackCoordinator] discard OpenAI output audio, sessionId={}, targetLang={}, bytes={}",
                    state.sessionId, targetLanguage, pcm24k == null ? 0 : pcm24k.length);
        }
    }

    private void flushOpenAiText(FallbackSessionState state, String reason) {
        if (!state.isOpenAiActive()) {
            return;
        }
        String sourceText;
        String sourceLanguage;
        long speechStartAtMs;
        synchronized (state.textLock) {
            state.flushFuture = null;
            sourceText = state.inputTranscript.toString().trim();
            sourceLanguage = state.currentSourceLanguage.get();
            speechStartAtMs = state.segmentStartedAtMs > 0 ? state.segmentStartedAtMs : System.currentTimeMillis();
            if (sourceText.isBlank()) {
                state.segmentStartedAtMs = 0L;
                return;
            }
            state.inputTranscript.setLength(0);
            state.segmentStartedAtMs = 0L;
        }
        state.callbacks.onRecognized(sourceText, sourceLanguage, FALLBACK_SPEAKER_ID, speechStartAtMs);
        log.info("[RealtimeFallbackCoordinator] flushed OpenAI ASR text, sessionId={}, reason={}, sourceLen={}, sourceLang={}",
                state.sessionId, reason, sourceText.length(), sourceLanguage);
    }

    private List<String> normalizeTargetLanguages(List<String> targetLanguages) {
        if (targetLanguages == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String targetLanguage : targetLanguages) {
            String lang = normalizeLanguage(targetLanguage);
            if (!normalized.contains(lang)) {
                normalized.add(lang);
            }
        }
        return normalized;
    }

    private static String normalizeEngine(String engine) {
        if (Constants.REALTIME_ENGINE_OPENAI.equalsIgnoreCase(String.valueOf(engine))) {
            return Constants.REALTIME_ENGINE_OPENAI;
        }
        return Constants.REALTIME_ENGINE_PRIMARY;
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return Constants.LANG_AUTO;
        }
        String lower = language.toLowerCase(Locale.ROOT);
        if (lower.startsWith("en")) {
            return Constants.LANG_EN_SHORT;
        }
        if (lower.startsWith("id") || lower.startsWith("in")) {
            return Constants.LANG_ID_SHORT;
        }
        if (lower.startsWith("zh")) {
            return Constants.LANG_ZH_CN;
        }
        return language;
    }

    private static boolean shouldFlushImmediately(String delta) {
        if (delta == null || delta.isBlank()) {
            return false;
        }
        char last = delta.trim().charAt(delta.trim().length() - 1);
        return SENTENCE_ENDINGS.contains(last);
    }

    private static String detectScriptLanguage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean hasLatin = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.HAN) {
                return Constants.LANG_ZH_CN;
            }
            if (script == Character.UnicodeScript.LATIN && Character.isLetter(ch)) {
                hasLatin = true;
            }
        }
        return hasLatin ? Constants.LANG_ID_SHORT : null;
    }

    private final class OpenAiSessionListener implements RealtimeTranslationListener {
        private final FallbackSessionState state;
        private final String targetLanguage;

        private OpenAiSessionListener(FallbackSessionState state, String targetLanguage) {
            this.state = state;
            this.targetLanguage = targetLanguage;
        }

        @Override
        public void onReady(String targetLanguage) {
            state.readyTargets.add(targetLanguage);
            state.callbacks.onStatus(state.status(true, "OpenAI Realtime ASR ready"));
            log.info("[RealtimeFallbackCoordinator] OpenAI ASR ready, sessionId={}, targetLang={}",
                    state.sessionId, targetLanguage);
        }

        @Override
        public void onInputTranscriptDelta(String delta) {
            onOpenAiInputTranscript(state, delta);
        }

        @Override
        public void onOutputTranscriptDelta(String targetLanguage, String delta) {
            onOpenAiOutputTranscript(state, targetLanguage, delta);
        }

        @Override
        public void onOutputAudio(String targetLanguage, byte[] pcm24k) {
            onOpenAiOutputAudio(state, targetLanguage, pcm24k);
        }

        @Override
        public void onError(String targetLanguage, String message) {
            state.readyTargets.remove(targetLanguage);
            String safeMessage = message == null || message.isBlank()
                    ? "OpenAI Realtime error"
                    : message;
            log.warn("[RealtimeFallbackCoordinator] OpenAI error, sessionId={}, targetLang={}, error={}",
                    state.sessionId, targetLanguage, safeMessage);
            if (state.isOpenAiActive() && !state.hasReadyOpenAiTarget()) {
                state.activeEngine.set(Constants.REALTIME_ENGINE_PRIMARY);
                state.callbacks.onStatus(state.status(false, "OpenAI Realtime unavailable, switched to primary"));
                state.callbacks.onError("OpenAI Realtime unavailable, switched to primary: " + safeMessage);
            } else {
                state.callbacks.onStatus(state.status(state.hasReadyOpenAiTarget(), safeMessage));
            }
        }

        @Override
        public void onClosed(String targetLanguage) {
            state.readyTargets.remove(targetLanguage);
            log.info("[RealtimeFallbackCoordinator] OpenAI closed, sessionId={}, targetLang={}",
                    state.sessionId, targetLanguage);
            if (state.isOpenAiActive() && !state.hasReadyOpenAiTarget()) {
                state.activeEngine.set(Constants.REALTIME_ENGINE_PRIMARY);
                state.callbacks.onStatus(state.status(false, "OpenAI Realtime closed, switched to primary"));
            }
        }
    }

    private final class FallbackSessionState {
        private final String sessionId;
        private final List<String> targetLanguages;
        private final RealtimeFallbackCallbacks callbacks;
        private final Map<String, RealtimeTranslationSession> openAiSessions = new ConcurrentHashMap<>();
        private final Set<String> readyTargets = ConcurrentHashMap.newKeySet();
        private final AtomicReference<String> activeEngine = new AtomicReference<>(Constants.REALTIME_ENGINE_PRIMARY);
        private final AtomicReference<String> currentSourceLanguage = new AtomicReference<>(Constants.LANG_AUTO);
        private final Object textLock = new Object();
        private final StringBuilder inputTranscript = new StringBuilder();
        private ScheduledFuture<?> flushFuture;
        private long segmentStartedAtMs;

        private FallbackSessionState(String sessionId, List<String> targetLanguages, RealtimeFallbackCallbacks callbacks) {
            this.sessionId = sessionId;
            this.targetLanguages = targetLanguages;
            this.callbacks = callbacks;
        }

        private boolean isOpenAiActive() {
            return Constants.REALTIME_ENGINE_OPENAI.equals(activeEngine.get());
        }

        private boolean hasReadyOpenAiTarget() {
            return !readyTargets.isEmpty();
        }

        private String realtimeSessionLanguage() {
            if (targetLanguages.isEmpty()) {
                return DEFAULT_REALTIME_SESSION_LANGUAGE;
            }
            return targetLanguages.get(0);
        }

        private FallbackEngineStatus status(boolean available, String message) {
            return new FallbackEngineStatus(sessionId, activeEngine.get(), available, message);
        }

        private void emitStatus(boolean available, String message) {
            callbacks.onStatus(status(available, message));
        }

        private void clearTextBuffers() {
            synchronized (textLock) {
                inputTranscript.setLength(0);
                segmentStartedAtMs = 0L;
                cancelFlush();
            }
        }

        private void scheduleFlush(boolean immediate) {
            cancelFlush();
            long delayMs = immediate ? 0L : properties.getFlushDelayMs();
            long now = System.currentTimeMillis();
            if (!immediate
                    && segmentStartedAtMs > 0
                    && now - segmentStartedAtMs >= properties.getMaxSegmentMs()) {
                delayMs = 0L;
            }
            flushFuture = FLUSH_EXECUTOR.schedule(
                    () -> flushOpenAiText(this, immediate ? "punctuation" : "silence"),
                    Math.max(0L, delayMs),
                    TimeUnit.MILLISECONDS
            );
        }

        private void cancelFlush() {
            if (flushFuture != null) {
                flushFuture.cancel(false);
                flushFuture = null;
            }
        }
    }
}
