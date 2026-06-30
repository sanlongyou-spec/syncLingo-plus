package com.si.backend.facade;

import com.si.backend.common.Constants;
import com.si.backend.common.TtsStreamHandle;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.AudioRecordService;
import com.si.backend.service.IndonesianIncompleteGuard;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.RealtimeFallbackCallbacks;
import com.si.backend.service.RealtimeFallbackCoordinator;
import com.si.backend.service.SpeakerTurnService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TranslationService;
import com.si.backend.service.UserVoiceService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeInterpretationFacadeRealtimeAsrTest {

    @Test
    void openAiRealtimeRecognizedTextUsesNormalTranslationAndTtsPipeline() throws Exception {
        TestContext ctx = new TestContext("openai-asr-session");
        when(ctx.realtimeFallbackCoordinator.shouldEmitPrimaryOutput(ctx.sessionId)).thenReturn(false);
        when(ctx.translationService.translate(eq("Pupuk selesai."), eq(Constants.LANG_ID_SHORT),
                eq(Constants.LANG_ZH_CN), anyLong(), any(), anyBoolean(), any()))
                .thenReturn("肥料已完成。");

        CountDownLatch translated = new CountDownLatch(1);
        CountDownLatch audioSent = new CountDownLatch(1);
        AtomicReference<String> recognizedText = new AtomicReference<>();
        AtomicReference<String> translatedText = new AtomicReference<>();
        AtomicReference<byte[]> audioBytes = new AtomicReference<>();
        byte[] pcm = new byte[]{7, 8, 9};
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<byte[]> onChunk = invocation.getArgument(5);
            Runnable onComplete = invocation.getArgument(6);
            onChunk.accept(pcm);
            onComplete.run();
            return TtsStreamHandle.NOOP;
        }).when(ctx.ttsService).synthesizeStream(
                anyString(),
                anyString(),
                anyInt(),
                anyDouble(),
                anyString(),
                any(),
                any(),
                any()
        );

        ctx.facade.startInterpretation(
                ctx.sessionId,
                Constants.LANG_AUTO,
                Constants.LANG_ZH_CN,
                "voice-id",
                (text, lang, speakerId) -> {},
                (text, lang, speakerId) -> recognizedText.set(text),
                (originalText, resultText, sourceLang, targetLang, speakerId, speakerName) -> {
                    translatedText.set(resultText);
                    translated.countDown();
                },
                (pcmData, targetLang, ttsTaskId, ttsSequence, chunkIndex, speechStartAtMs) -> {
                    audioBytes.set(pcmData);
                    audioSent.countDown();
                },
                status -> {},
                error -> {}
        );

        ctx.realtimeCallbacks.get().onRecognized(
                "Pupuk selesai.",
                Constants.LANG_ID_SHORT,
                "OpenAI",
                System.currentTimeMillis() - 100
        );

        assertTrue(translated.await(3, TimeUnit.SECONDS));
        assertTrue(audioSent.await(3, TimeUnit.SECONDS));
        assertEquals("Pupuk selesai.", recognizedText.get());
        assertEquals("肥料已完成。", translatedText.get());
        assertArrayEquals(pcm, audioBytes.get());
        verify(ctx.translationService).translate(eq("Pupuk selesai."), eq(Constants.LANG_ID_SHORT),
                eq(Constants.LANG_ZH_CN), anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void primaryAsrFinalIsNotQueuedWhileOpenAiRealtimeIsActive() {
        TestContext ctx = new TestContext("primary-suppressed-session");
        when(ctx.realtimeFallbackCoordinator.shouldEmitPrimaryOutput(ctx.sessionId)).thenReturn(false);
        AtomicReference<AsrService.AsrCallback> primaryRecognized = new AtomicReference<>();
        doAnswer(invocation -> {
            primaryRecognized.set(invocation.getArgument(6));
            return null;
        }).when(ctx.asrService).startRecognition(
                anyString(),
                anyLong(),
                anyString(),
                any(),
                any(),
                any(),
                any(),
                any()
        );

        ctx.facade.startInterpretation(
                ctx.sessionId,
                Constants.LANG_AUTO,
                Constants.LANG_ZH_CN,
                "voice-id",
                (text, lang, speakerId) -> {},
                (text, lang, speakerId) -> {},
                (originalText, resultText, sourceLang, targetLang, speakerId, speakerName) -> {},
                (pcmData, targetLang, ttsTaskId, ttsSequence, chunkIndex, speechStartAtMs) -> {},
                status -> {},
                error -> {}
        );

        primaryRecognized.get().onResult("Azure text", Constants.LANG_ID_SHORT, "Guest-1");

        verify(ctx.translationService, never()).translate(anyString(), anyString(), anyString(), anyLong(),
                any(), anyBoolean(), any());
    }

    private static final class TestContext {
        private final String sessionId;
        private final AsrService asrService = mock(AsrService.class);
        private final TtsService ttsService = mock(TtsService.class);
        private final TranslationService translationService = mock(TranslationService.class);
        private final InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
        private final InterpretationRecordService recordService = mock(InterpretationRecordService.class);
        private final AudioRecordService audioRecordService = mock(AudioRecordService.class);
        private final SpeakerTurnService speakerTurnService = mock(SpeakerTurnService.class);
        private final UserVoiceService userVoiceService = mock(UserVoiceService.class);
        private final IndonesianIncompleteGuard indonesianIncompleteGuard = mock(IndonesianIncompleteGuard.class);
        private final RealtimeFallbackCoordinator realtimeFallbackCoordinator = mock(RealtimeFallbackCoordinator.class);
        private final AtomicReference<RealtimeFallbackCallbacks> realtimeCallbacks = new AtomicReference<>();
        private final RealtimeInterpretationFacade facade;

        private TestContext(String sessionId) {
            this.sessionId = sessionId;
            CartesiaProperties cartesiaProperties = new CartesiaProperties();
            InterpretationSession session = new InterpretationSession();
            session.setSessionId(sessionId);
            session.setUserId(1L);
            session.setEnabledLanguages(Constants.LANG_ID_SHORT + "," + Constants.LANG_ZH_CN);
            when(sessionService.getSession(sessionId)).thenReturn(Optional.of(session));
            when(sessionService.isSessionActive(sessionId)).thenReturn(true);
            doAnswer(invocation -> {
                realtimeCallbacks.set(invocation.getArgument(2));
                return null;
            }).when(realtimeFallbackCoordinator).startSession(anyString(), any(), any());
            facade = new RealtimeInterpretationFacade(
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
        }
    }
}
