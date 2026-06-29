package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.config.OpenAiRealtimeProperties;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration.RealtimeTranslationListener;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration.RealtimeTranslationSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealtimeFallbackCoordinatorTest {

    @Test
    void openAiShadowReceivesAudioAndOnlyEmitsWhenSelected() {
        OpenAiRealtimeProperties properties = usableProperties();
        OpenAiRealtimeTranslateIntegration integration = mock(OpenAiRealtimeTranslateIntegration.class);
        Map<String, RealtimeTranslationListener> listeners = new ConcurrentHashMap<>();
        Map<String, FakeRealtimeSession> openAiSessions = new ConcurrentHashMap<>();
        when(integration.openSession(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String targetLanguage = invocation.getArgument(1);
            RealtimeTranslationListener listener = invocation.getArgument(2);
            listeners.put(targetLanguage, listener);
            FakeRealtimeSession session = new FakeRealtimeSession(targetLanguage);
            openAiSessions.put(targetLanguage, session);
            return session;
        });

        RealtimeFallbackCoordinator coordinator = new RealtimeFallbackCoordinator(properties, integration);
        RecordingFallbackCallbacks callbacks = new RecordingFallbackCallbacks();

        coordinator.startSession("s1", List.of(Constants.LANG_ZH_CN, Constants.LANG_ID_SHORT), callbacks);
        coordinator.pushAudio("s1", new byte[]{1, 2, 3, 4});

        assertEquals(1, openAiSessions.get(Constants.LANG_ZH_CN).audioFrames.get());
        assertEquals(1, openAiSessions.get(Constants.LANG_ID_SHORT).audioFrames.get());
        assertTrue(coordinator.shouldEmitPrimaryOutput("s1"));

        listeners.get(Constants.LANG_ID_SHORT).onReady(Constants.LANG_ID_SHORT);
        coordinator.switchEngine("s1", Constants.REALTIME_ENGINE_OPENAI);

        assertFalse(coordinator.shouldEmitPrimaryOutput("s1"));
        listeners.get(Constants.LANG_ID_SHORT).onOutputAudio(Constants.LANG_ID_SHORT, new byte[]{8, 9});
        assertEquals(1, callbacks.audioTargets.size());
        assertEquals(Constants.LANG_ID_SHORT, callbacks.audioTargets.get(0));

        coordinator.switchEngine("s1", Constants.REALTIME_ENGINE_PRIMARY);
        listeners.get(Constants.LANG_ID_SHORT).onOutputAudio(Constants.LANG_ID_SHORT, new byte[]{10, 11});

        assertTrue(coordinator.shouldEmitPrimaryOutput("s1"));
        assertEquals(1, callbacks.audioTargets.size());
    }

    @Test
    void switchToOpenAiIsRejectedUntilReady() {
        OpenAiRealtimeProperties properties = usableProperties();
        OpenAiRealtimeTranslateIntegration integration = mock(OpenAiRealtimeTranslateIntegration.class);
        when(integration.openSession(anyString(), anyString(), any()))
                .thenAnswer(invocation -> new FakeRealtimeSession(invocation.getArgument(1)));
        RealtimeFallbackCoordinator coordinator = new RealtimeFallbackCoordinator(properties, integration);
        RecordingFallbackCallbacks callbacks = new RecordingFallbackCallbacks();

        coordinator.startSession("s2", List.of(Constants.LANG_ID_SHORT), callbacks);
        FallbackEngineStatus status = coordinator.switchEngine("s2", Constants.REALTIME_ENGINE_OPENAI);

        assertEquals(Constants.REALTIME_ENGINE_PRIMARY, status.activeEngine());
        assertFalse(status.available());
        assertTrue(coordinator.shouldEmitPrimaryOutput("s2"));
    }

    @Test
    void openAiAudioForCurrentSourceLanguageIsSuppressed() {
        OpenAiRealtimeProperties properties = usableProperties();
        OpenAiRealtimeTranslateIntegration integration = mock(OpenAiRealtimeTranslateIntegration.class);
        Map<String, RealtimeTranslationListener> listeners = new ConcurrentHashMap<>();
        when(integration.openSession(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String targetLanguage = invocation.getArgument(1);
            RealtimeTranslationListener listener = invocation.getArgument(2);
            listeners.put(targetLanguage, listener);
            return new FakeRealtimeSession(targetLanguage);
        });
        RealtimeFallbackCoordinator coordinator = new RealtimeFallbackCoordinator(properties, integration);
        RecordingFallbackCallbacks callbacks = new RecordingFallbackCallbacks();

        coordinator.startSession("s3", List.of(Constants.LANG_ID_SHORT), callbacks);
        listeners.get(Constants.LANG_ID_SHORT).onReady(Constants.LANG_ID_SHORT);
        coordinator.observeSourceLanguage("s3", Constants.LANG_ID);
        coordinator.switchEngine("s3", Constants.REALTIME_ENGINE_OPENAI);
        listeners.get(Constants.LANG_ID_SHORT).onOutputAudio(Constants.LANG_ID_SHORT, new byte[]{1, 2});

        assertEquals(List.of(), callbacks.audioTargets);
    }

    private static OpenAiRealtimeProperties usableProperties() {
        OpenAiRealtimeProperties properties = new OpenAiRealtimeProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setFlushDelayMs(10_000L);
        properties.setMaxSegmentMs(30_000L);
        return properties;
    }

    private static final class FakeRealtimeSession implements RealtimeTranslationSession {
        private final String targetLanguage;
        private final AtomicInteger audioFrames = new AtomicInteger();

        private FakeRealtimeSession(String targetLanguage) {
            this.targetLanguage = targetLanguage;
        }

        @Override
        public String targetLanguage() {
            return targetLanguage;
        }

        @Override
        public void sendAudio(byte[] pcm16k) {
            audioFrames.incrementAndGet();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class RecordingFallbackCallbacks implements RealtimeFallbackCallbacks {
        private final List<String> audioTargets = new ArrayList<>();

        @Override
        public void onRecognizing(String text, String language, String speakerId) {
            // no-op
        }

        @Override
        public void onRecognized(String text, String language, String speakerId) {
            // no-op
        }

        @Override
        public void onTranslated(String originalText, String translatedText, String sourceLang,
                                 String targetLang, String speakerId, String speakerName) {
            // no-op
        }

        @Override
        public void onTtsAudio(byte[] pcmData, String targetLang, String ttsTaskId,
                               Long ttsSequence, Integer chunkIndex, long speechStartAtMs) {
            audioTargets.add(targetLang);
        }

        @Override
        public void onStatus(FallbackEngineStatus status) {
            // no-op
        }

        @Override
        public void onError(String message) {
            // no-op
        }
    }
}
