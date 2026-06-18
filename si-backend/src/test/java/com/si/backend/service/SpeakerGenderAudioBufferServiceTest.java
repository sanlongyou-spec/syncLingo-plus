package com.si.backend.service;

import com.si.backend.config.VoiceGenderProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerGenderAudioBufferServiceTest {

    @Test
    void switchSpeakerReturnsPreviousSampleOnlyAfterMinimumAudio() {
        SpeakerGenderAudioBufferService service = new SpeakerGenderAudioBufferService(properties());

        service.observeSpeaker("session-1", "speaker-a");
        service.append("session-1", pcmSeconds(5));
        List<SpeakerGenderAudioBufferService.SpeakerAudioSample> tooShort =
                service.observeSpeaker("session-1", "speaker-b");
        assertTrue(tooShort.isEmpty());

        service.observeSpeaker("session-1", "speaker-a");
        service.append("session-1", pcmSeconds(6));
        List<SpeakerGenderAudioBufferService.SpeakerAudioSample> ready =
                service.observeSpeaker("session-1", "speaker-b");

        assertEquals(1, ready.size());
        assertEquals("speaker-a", ready.getFirst().speakerId());
        assertTrue(ready.getFirst().durationMs() >= 6000L);
        assertTrue(ready.getFirst().durationMs() <= 8000L);
    }

    @Test
    void activeSnapshotUsesTailWhenBufferExceedsMaxSampleSeconds() {
        SpeakerGenderAudioBufferService service = new SpeakerGenderAudioBufferService(properties());

        service.observeSpeaker("session-2", "speaker-a");
        service.append("session-2", pcmSeconds(10));
        Optional<SpeakerGenderAudioBufferService.SpeakerAudioSample> sample =
                service.snapshotActiveIfReady("session-2");

        assertTrue(sample.isPresent());
        assertEquals(8000L, sample.get().durationMs());
        assertEquals(pcmSeconds(8).length, sample.get().pcmData().length);
    }

    @Test
    void cleanupRemovesSessionBuffers() {
        SpeakerGenderAudioBufferService service = new SpeakerGenderAudioBufferService(properties());

        service.observeSpeaker("session-3", "speaker-a");
        service.append("session-3", pcmSeconds(6));
        assertTrue(service.snapshotActiveIfReady("session-3").isPresent());

        service.cleanupSession("session-3");

        assertTrue(service.snapshotActiveIfReady("session-3").isEmpty());
    }

    private static VoiceGenderProperties properties() {
        VoiceGenderProperties properties = new VoiceGenderProperties();
        properties.setEnabled(true);
        properties.setSampleRate(16_000);
        properties.setMinAudioSeconds(6);
        properties.setMaxAudioSeconds(8);
        properties.setMaxBufferSeconds(12);
        return properties;
    }

    private static byte[] pcmSeconds(int seconds) {
        return new byte[16_000 * 2 * seconds];
    }
}
