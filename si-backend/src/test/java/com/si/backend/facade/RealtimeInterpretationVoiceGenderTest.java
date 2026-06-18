package com.si.backend.facade;

import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.AudioRecordService;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.SpeakerTurnService;
import com.si.backend.service.SpeakerVoiceGenderService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TranslationService;
import com.si.backend.service.VoiceGender;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

class RealtimeInterpretationVoiceGenderTest {

    @Test
    void usesGlobalMaleVoiceWhenSpeakerGenderCacheIsMale() throws Exception {
        TestContext context = new TestContext();
        context.cartesiaProperties.getVoiceGender().setMaleVoiceId("global-male");
        when(context.speakerVoiceGenderService.resolveGender("voice-gender-session", "speaker-1"))
                .thenReturn(VoiceGender.MALE);

        String voiceId = context.synthesizeOnce(null, "speaker-1");

        assertEquals("global-male", voiceId);
    }

    @Test
    void unknownGenderFallsBackToTargetLanguageDefaultWithoutWaiting() throws Exception {
        TestContext context = new TestContext();
        context.cartesiaProperties.setDefaultVoiceIdIndonesian("default-id");
        when(context.speakerVoiceGenderService.resolveGender("voice-gender-session", "speaker-2"))
                .thenReturn(VoiceGender.UNKNOWN);

        String voiceId = context.synthesizeOnce(null, "speaker-2");

        assertEquals("default-id", voiceId);
    }

    @Test
    void explicitVoiceIdStillOverridesGenderCache() throws Exception {
        TestContext context = new TestContext();
        context.cartesiaProperties.getVoiceGender().setMaleVoiceId("global-male");
        when(context.speakerVoiceGenderService.resolveGender("voice-gender-session", "speaker-3"))
                .thenReturn(VoiceGender.MALE);

        String voiceId = context.synthesizeOnce("explicit-voice", "speaker-3");

        assertEquals("explicit-voice", voiceId);
    }

    private static class TestContext {
        private final TtsService ttsService = mock(TtsService.class);
        private final TranslationService translationService = mock(TranslationService.class);
        private final InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
        private final CartesiaProperties cartesiaProperties = new CartesiaProperties();
        private final SpeakerVoiceGenderService speakerVoiceGenderService = mock(SpeakerVoiceGenderService.class);
        private final RealtimeInterpretationFacade facade;

        private TestContext() throws Exception {
            AsrService asrService = mock(AsrService.class);
            InterpretationRecordService recordService = mock(InterpretationRecordService.class);
            AudioRecordService audioRecordService = mock(AudioRecordService.class);
            SpeakerTurnService speakerTurnService = mock(SpeakerTurnService.class);
            cartesiaProperties.getVoiceGender().setFemaleVoiceId("global-female");

            InterpretationSession session = new InterpretationSession();
            session.setSessionId("voice-gender-session");
            session.setUserId(1L);
            when(sessionService.getSession("voice-gender-session")).thenReturn(Optional.of(session));
            when(sessionService.isSessionActive("voice-gender-session")).thenReturn(true);
            when(translationService.translate(anyString(), anyString(), anyString(), anyLong(), anyBoolean()))
                    .thenAnswer(invocation -> invocation.getArgument(0) + "-translated");

            facade = new RealtimeInterpretationFacade(
                    asrService,
                    ttsService,
                    translationService,
                    sessionService,
                    cartesiaProperties,
                    recordService,
                    audioRecordService,
                    speakerTurnService,
                    speakerVoiceGenderService
            );
        }

        private String synthesizeOnce(String requestedVoiceId, String speakerId) throws Exception {
            AtomicReference<String> voiceIdRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                voiceIdRef.set(invocation.getArgument(0));
                @SuppressWarnings("unchecked")
                Consumer<byte[]> onChunk = invocation.getArgument(5);
                Runnable onComplete = invocation.getArgument(6);
                onChunk.accept(new byte[]{1});
                onComplete.run();
                latch.countDown();
                return null;
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

            facade.translateAndStreamTts(
                    "hello", "zh-CN", "id", requestedVoiceId,
                    "voice-gender-session", speakerId, speakerId, System.currentTimeMillis()
            );

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            return voiceIdRef.get();
        }
    }
}
