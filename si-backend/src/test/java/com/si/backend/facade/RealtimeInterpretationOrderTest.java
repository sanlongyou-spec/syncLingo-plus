package com.si.backend.facade;

import com.si.backend.common.TtsStreamHandle;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.AudioRecordService;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.RealtimeFallbackCoordinator;
import com.si.backend.service.SpeakerTurnService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TtsTextNormalizer;
import com.si.backend.service.TranslationService;
import com.si.backend.service.UserVoiceService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that translated TTS audio keeps ASR sentence order even when a later translation finishes first.
 */
class RealtimeInterpretationOrderTest {

    @Test
    void laterTranslationCannotPlayBeforeEarlierSentence() throws Exception {
        AsrService asrService = mock(AsrService.class);
        TtsService ttsService = mock(TtsService.class);
        TranslationService translationService = mock(TranslationService.class);
        InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
        CartesiaProperties cartesiaProperties = new CartesiaProperties();
        InterpretationRecordService recordService = mock(InterpretationRecordService.class);
        AudioRecordService audioRecordService = mock(AudioRecordService.class);
        SpeakerTurnService speakerTurnService = mock(SpeakerTurnService.class);
        UserVoiceService userVoiceService = mock(UserVoiceService.class);
        com.si.backend.service.IndonesianIncompleteGuard indonesianIncompleteGuard =
                mock(com.si.backend.service.IndonesianIncompleteGuard.class);
        RealtimeFallbackCoordinator realtimeFallbackCoordinator = mock(RealtimeFallbackCoordinator.class);
        when(realtimeFallbackCoordinator.shouldEmitPrimaryOutput(anyString())).thenReturn(true);

        RealtimeInterpretationFacade facade = new RealtimeInterpretationFacade(
                asrService,
                ttsService,
                translationService,
                sessionService,
                cartesiaProperties,
                recordService,
                audioRecordService,
                speakerTurnService,
                userVoiceService,
                indonesianIncompleteGuard,
                realtimeFallbackCoordinator
        );

        String sessionId = "order-test-session";
        InterpretationSession session = new InterpretationSession();
        session.setSessionId(sessionId);
        session.setUserId(1L);
        when(sessionService.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.isSessionActive(sessionId)).thenReturn(true);

        CountDownLatch firstTranslationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTranslation = new CountDownLatch(1);
        when(translationService.translate(anyString(), anyString(), anyString(), anyLong(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    if ("first".equals(text)) {
                        firstTranslationStarted.countDown();
                        assertTrue(releaseFirstTranslation.await(5, TimeUnit.SECONDS));
                    }
                    return text + "-translated";
                });

        doAnswer(invocation -> {
            String text = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            Consumer<byte[]> onChunk = invocation.getArgument(5);
            Runnable onComplete = invocation.getArgument(6);
            onChunk.accept(new byte[]{(byte) ("first-translated".equals(text) ? 1 : 2)});
            onComplete.run();
            return TtsStreamHandle.NOOP;
        }).when(ttsService).synthesizeStream(
                anyString(),
                anyString(),
                anyInt(),
                anyDouble(),
                anyString(),
                any(),
                any(),
                any()
        );

        List<Long> playedSequences = new CopyOnWriteArrayList<>();
        CountDownLatch played = new CountDownLatch(2);
        putTtsCallback(facade, sessionId, (pcm, lang, taskId, sequence, chunkIndex, speechStartAtMs) -> {
            if (chunkIndex == 0) {
                playedSequences.add(sequence);
                played.countDown();
            }
        });

        Thread first = new Thread(() -> facade.translateAndStreamTts(
                "first", "zh-CN", "id", null, sessionId, "speaker-1", null, System.currentTimeMillis()
        ));
        first.start();
        assertTrue(firstTranslationStarted.await(5, TimeUnit.SECONDS));

        facade.translateAndStreamTts(
                "second", "zh-CN", "id", null, sessionId, "speaker-1", null, System.currentTimeMillis()
        );
        releaseFirstTranslation.countDown();
        first.join(5_000);

        assertTrue(played.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L), playedSequences);
    }

    @Test
    void laterSentenceSynthesizesWhileEarlierPlaybackIsStillWaiting() throws Exception {
        AsrService asrService = mock(AsrService.class);
        TtsService ttsService = mock(TtsService.class);
        TranslationService translationService = mock(TranslationService.class);
        InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
        CartesiaProperties cartesiaProperties = new CartesiaProperties();
        InterpretationRecordService recordService = mock(InterpretationRecordService.class);
        AudioRecordService audioRecordService = mock(AudioRecordService.class);
        SpeakerTurnService speakerTurnService = mock(SpeakerTurnService.class);
        UserVoiceService userVoiceService = mock(UserVoiceService.class);
        com.si.backend.service.IndonesianIncompleteGuard indonesianIncompleteGuard =
                mock(com.si.backend.service.IndonesianIncompleteGuard.class);
        RealtimeFallbackCoordinator realtimeFallbackCoordinator = mock(RealtimeFallbackCoordinator.class);
        when(realtimeFallbackCoordinator.shouldEmitPrimaryOutput(anyString())).thenReturn(true);

        RealtimeInterpretationFacade facade = new RealtimeInterpretationFacade(
                asrService,
                ttsService,
                translationService,
                sessionService,
                cartesiaProperties,
                recordService,
                audioRecordService,
                speakerTurnService,
                userVoiceService,
                indonesianIncompleteGuard,
                realtimeFallbackCoordinator
        );

        String sessionId = "skip-wait-session";
        InterpretationSession session = new InterpretationSession();
        session.setSessionId(sessionId);
        session.setUserId(1L);
        when(sessionService.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.isSessionActive(sessionId)).thenReturn(true);
        when(translationService.translate(anyString(), anyString(), anyString(), anyLong(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "-translated");

        CountDownLatch firstSynthStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSynth = new CountDownLatch(1);
        CountDownLatch secondSynthStarted = new CountDownLatch(1);
        AtomicInteger synthCalls = new AtomicInteger();
        doAnswer(invocation -> {
            String text = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            Consumer<byte[]> onChunk = invocation.getArgument(5);
            Runnable onComplete = invocation.getArgument(6);
            synthCalls.incrementAndGet();
            if ("first-translated".equals(text)) {
                firstSynthStarted.countDown();
                assertTrue(releaseFirstSynth.await(5, TimeUnit.SECONDS));
                onChunk.accept(new byte[]{1});
                onComplete.run();
                return TtsStreamHandle.NOOP;
            }
            if ("second-translated".equals(text)) {
                secondSynthStarted.countDown();
                onChunk.accept(new byte[]{2});
                onComplete.run();
                return TtsStreamHandle.NOOP;
            }
            throw new AssertionError("Unexpected TTS text: " + text);
        }).when(ttsService).synthesizeStream(
                anyString(),
                anyString(),
                anyInt(),
                anyDouble(),
                anyString(),
                any(),
                any(),
                any()
        );

        List<Long> playedSequences = new CopyOnWriteArrayList<>();
        CountDownLatch played = new CountDownLatch(2);
        putTtsCallback(facade, sessionId, (pcm, lang, taskId, sequence, chunkIndex, speechStartAtMs) -> {
            if (chunkIndex == 0) {
                playedSequences.add(sequence);
                played.countDown();
            }
        });

        facade.translateAndStreamTts(
                "first", "zh-CN", "id", null, sessionId, "speaker-1", null, System.currentTimeMillis()
        );
        assertTrue(firstSynthStarted.await(5, TimeUnit.SECONDS));

        facade.translateAndStreamTts(
                "second", "zh-CN", "id", null, sessionId, "speaker-1", null, System.currentTimeMillis()
        );

        assertTrue(secondSynthStarted.await(5, TimeUnit.SECONDS));
        assertEquals(2, synthCalls.get());
        assertEquals(List.of(), playedSequences);

        releaseFirstSynth.countDown();
        assertTrue(played.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L), playedSequences);
    }

    @Test
    void chineseTtsUsesNormalizedTextAndStopsForwardingAfterDurationBudget() throws Exception {
        AsrService asrService = mock(AsrService.class);
        TtsService ttsService = mock(TtsService.class);
        TranslationService translationService = mock(TranslationService.class);
        InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
        CartesiaProperties cartesiaProperties = new CartesiaProperties();
        InterpretationRecordService recordService = mock(InterpretationRecordService.class);
        AudioRecordService audioRecordService = mock(AudioRecordService.class);
        SpeakerTurnService speakerTurnService = mock(SpeakerTurnService.class);
        UserVoiceService userVoiceService = mock(UserVoiceService.class);
        com.si.backend.service.IndonesianIncompleteGuard indonesianIncompleteGuard =
                mock(com.si.backend.service.IndonesianIncompleteGuard.class);
        RealtimeFallbackCoordinator realtimeFallbackCoordinator = mock(RealtimeFallbackCoordinator.class);
        when(realtimeFallbackCoordinator.shouldEmitPrimaryOutput(anyString())).thenReturn(true);

        RealtimeInterpretationFacade facade = new RealtimeInterpretationFacade(
                asrService,
                ttsService,
                translationService,
                sessionService,
                cartesiaProperties,
                recordService,
                audioRecordService,
                speakerTurnService,
                userVoiceService,
                indonesianIncompleteGuard,
                realtimeFallbackCoordinator
        );

        String sessionId = "duration-guard-session";
        InterpretationSession session = new InterpretationSession();
        session.setSessionId(sessionId);
        session.setUserId(1L);
        when(sessionService.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.isSessionActive(sessionId)).thenReturn(true);

        String translated = "弄给分区的最高施肥剂量为12.36公斤/株。";
        TtsTextNormalizer.Result normalized = TtsTextNormalizer.normalizeForTts(translated, "zh-CN");
        int sampleRate = cartesiaProperties.getTts().getSampleRate();
        byte[] oneSecondPcm = new byte[sampleRate * 2];
        int expectedForwardedChunks =
                (int) (TtsTextNormalizer.maxForwardAudioMs(normalized.text(), "zh-CN") / 1_000L);

        when(translationService.translate(anyString(), anyString(), anyString(), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(translated);

        AtomicReference<String> synthesizedText = new AtomicReference<>();
        AtomicReference<String> cancelReason = new AtomicReference<>();
        doAnswer(invocation -> {
            synthesizedText.set(invocation.getArgument(1));
            @SuppressWarnings("unchecked")
            Consumer<byte[]> onChunk = invocation.getArgument(5);
            Runnable onComplete = invocation.getArgument(6);
            for (int i = 0; i < 40; i++) {
                onChunk.accept(oneSecondPcm);
            }
            onComplete.run();
            return (TtsStreamHandle) cancelReason::set;
        }).when(ttsService).synthesizeStream(
                anyString(),
                anyString(),
                anyInt(),
                anyDouble(),
                anyString(),
                any(),
                any(),
                any()
        );

        AtomicInteger forwardedChunks = new AtomicInteger();
        putTtsCallback(facade, sessionId, (pcm, lang, taskId, sequence, chunkIndex, speechStartAtMs) ->
                forwardedChunks.incrementAndGet());

        facade.translateAndStreamTts(
                "source", "id", "zh-CN", null, sessionId, "speaker-1", null, System.currentTimeMillis()
        );
        awaitTtsChain(facade, sessionId, "zh-CN");

        assertEquals(normalized.text(), synthesizedText.get());
        assertEquals(expectedForwardedChunks, forwardedChunks.get());
        assertTrue(forwardedChunks.get() < 40);
        assertTrue(cancelReason.get() != null && cancelReason.get().contains("duration guard"));
    }

    @SuppressWarnings("unchecked")
    private void putTtsCallback(
            RealtimeInterpretationFacade facade,
            String sessionId,
            RealtimeInterpretationFacade.TtsAudioCallback callback
    ) throws Exception {
        Field field = RealtimeInterpretationFacade.class.getDeclaredField("sessionTtsAudioCallbackMap");
        field.setAccessible(true);
        ((Map<String, RealtimeInterpretationFacade.TtsAudioCallback>) field.get(facade)).put(sessionId, callback);
    }

    @SuppressWarnings("unchecked")
    private void awaitTtsChain(RealtimeInterpretationFacade facade, String sessionId, String targetLang) throws Exception {
        Field field = RealtimeInterpretationFacade.class.getDeclaredField("sessionTtsChain");
        field.setAccessible(true);
        Map<String, CompletableFuture<Void>> chain =
                (ConcurrentHashMap<String, CompletableFuture<Void>>) field.get(facade);
        CompletableFuture<Void> future = chain.get(sessionId + "::" + targetLang);
        assertTrue(future != null, "TTS chain future should be reserved");
        future.get(5, TimeUnit.SECONDS);
    }
}
