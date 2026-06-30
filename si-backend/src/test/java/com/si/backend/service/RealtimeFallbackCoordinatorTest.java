package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.config.OpenAiRealtimeProperties;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration.RealtimeTranslationListener;
import com.si.backend.integration.OpenAiRealtimeTranslateIntegration.RealtimeTranslationSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
    void openAiShadowUsesSingleAsrSessionAndSuppressesPrimaryWhenSelected() {
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

        assertEquals(1, openAiSessions.size());
        FakeRealtimeSession session = openAiSessions.values().iterator().next();
        assertEquals(1, session.audioFrames.get());
        assertTrue(coordinator.shouldEmitPrimaryOutput("s1"));
        assertTrue(coordinator.shouldFeedPrimaryAsr("s1"));

        listeners.get(session.targetLanguage()).onReady(session.targetLanguage());
        FallbackEngineStatus status = coordinator.switchEngine("s1", Constants.REALTIME_ENGINE_OPENAI);

        assertEquals(Constants.REALTIME_ENGINE_OPENAI, status.activeEngine());
        assertFalse(coordinator.shouldEmitPrimaryOutput("s1"));
        assertFalse(coordinator.shouldFeedPrimaryAsr("s1"));
    }

    @Test
    void openAiInputTranscriptFlushesAsRecognizedText() throws Exception {
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

        coordinator.startSession("s2", List.of(Constants.LANG_ZH_CN, Constants.LANG_ID_SHORT), callbacks);
        RealtimeTranslationListener listener = listeners.values().iterator().next();
        listener.onReady(Constants.LANG_ZH_CN);
        coordinator.switchEngine("s2", Constants.REALTIME_ENGINE_OPENAI);

        listener.onInputTranscriptDelta("Pupuk selesai.");

        assertTrue(callbacks.awaitRecognized());
        assertEquals(List.of("Pupuk selesai."), callbacks.recognizedTexts);
        assertEquals(List.of(Constants.LANG_ID_SHORT), callbacks.recognizedLanguages);
        assertEquals(List.of("OpenAI"), callbacks.recognizedSpeakers);
        assertTrue(callbacks.recognizedStartedAtMs.get(0) > 0);
    }

    @Test
    void openAiOutputTranscriptAndAudioAreDiscarded() throws Exception {
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
        RealtimeTranslationListener listener = listeners.get(Constants.LANG_ID_SHORT);
        listener.onReady(Constants.LANG_ID_SHORT);
        coordinator.switchEngine("s3", Constants.REALTIME_ENGINE_OPENAI);

        listener.onOutputTranscriptDelta(Constants.LANG_ID_SHORT, "ignored output.");
        listener.onOutputAudio(Constants.LANG_ID_SHORT, new byte[]{1, 2});

        assertFalse(callbacks.awaitRecognized());
        assertEquals(List.of(), callbacks.recognizingTexts);
        assertEquals(List.of(), callbacks.recognizedTexts);
    }

    @Test
    void switchToOpenAiIsRejectedUntilReady() {
        OpenAiRealtimeProperties properties = usableProperties();
        OpenAiRealtimeTranslateIntegration integration = mock(OpenAiRealtimeTranslateIntegration.class);
        when(integration.openSession(anyString(), anyString(), any()))
                .thenAnswer(invocation -> new FakeRealtimeSession(invocation.getArgument(1)));
        RealtimeFallbackCoordinator coordinator = new RealtimeFallbackCoordinator(properties, integration);
        RecordingFallbackCallbacks callbacks = new RecordingFallbackCallbacks();

        coordinator.startSession("s4", List.of(Constants.LANG_ID_SHORT), callbacks);
        FallbackEngineStatus status = coordinator.switchEngine("s4", Constants.REALTIME_ENGINE_OPENAI);

        assertEquals(Constants.REALTIME_ENGINE_PRIMARY, status.activeEngine());
        assertFalse(status.available());
        assertTrue(coordinator.shouldEmitPrimaryOutput("s4"));
        assertTrue(coordinator.shouldFeedPrimaryAsr("s4"));
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
        private final CountDownLatch recognizedLatch = new CountDownLatch(1);
        private final List<String> recognizingTexts = new CopyOnWriteArrayList<>();
        private final List<String> recognizedTexts = new CopyOnWriteArrayList<>();
        private final List<String> recognizedLanguages = new CopyOnWriteArrayList<>();
        private final List<String> recognizedSpeakers = new CopyOnWriteArrayList<>();
        private final List<Long> recognizedStartedAtMs = new CopyOnWriteArrayList<>();

        @Override
        public void onRecognizing(String text, String language, String speakerId) {
            recognizingTexts.add(text);
        }

        @Override
        public void onRecognized(String text, String language, String speakerId, long speechStartAtMs) {
            recognizedTexts.add(text);
            recognizedLanguages.add(language);
            recognizedSpeakers.add(speakerId);
            recognizedStartedAtMs.add(speechStartAtMs);
            recognizedLatch.countDown();
        }

        @Override
        public void onStatus(FallbackEngineStatus status) {
            // no-op
        }

        @Override
        public void onError(String message) {
            // no-op
        }

        private boolean awaitRecognized() throws InterruptedException {
            return recognizedLatch.await(1, TimeUnit.SECONDS);
        }
    }
}
