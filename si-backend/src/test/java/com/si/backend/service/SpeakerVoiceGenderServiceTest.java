package com.si.backend.service;

import com.si.backend.config.VoiceGenderProperties;
import com.si.backend.integration.VoiceGenderDetectionResult;
import com.si.backend.integration.VoiceGenderIntegration;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeakerVoiceGenderServiceTest {

    @Test
    void appendingSixSecondsSchedulesBackgroundDetectionAndCachesGender() {
        VoiceGenderProperties properties = properties();
        VoiceGenderIntegration integration = mock(VoiceGenderIntegration.class);
        when(integration.detect(any(), eq("speaker-a")))
                .thenReturn(new VoiceGenderDetectionResult(
                        VoiceGender.MALE, 0.91D, 0.91D, 0.06D, 0.03D, 120L, true));
        SpeakerVoiceGenderService service = service(properties, integration);
        try {
            service.observeSpeaker("session-1", "speaker-a");
            service.appendAudio("session-1", pcmSeconds(6));

            verify(integration, timeout(1000).times(1)).detect(any(), eq("speaker-a"));
            assertTrue(waitFor(() -> service.resolveGender("session-1", "speaker-a") == VoiceGender.MALE,
                    Duration.ofSeconds(2)));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void audioShorterThanMinimumDoesNotCallDetector() {
        VoiceGenderProperties properties = properties();
        VoiceGenderIntegration integration = mock(VoiceGenderIntegration.class);
        SpeakerVoiceGenderService service = service(properties, integration);
        try {
            service.observeSpeaker("session-2", "speaker-a");
            service.appendAudio("session-2", pcmSeconds(5));

            verifyNoInteractions(integration);
            assertEquals(VoiceGender.UNKNOWN, service.resolveGender("session-2", "speaker-a"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void lowConfidenceResultDoesNotLockGender() {
        VoiceGenderProperties properties = properties();
        VoiceGenderIntegration integration = mock(VoiceGenderIntegration.class);
        when(integration.detect(any(), eq("speaker-a")))
                .thenReturn(new VoiceGenderDetectionResult(
                        VoiceGender.MALE, 0.56D, 0.56D, 0.44D, 0D, 120L, true));
        SpeakerVoiceGenderService service = service(properties, integration);
        try {
            service.observeSpeaker("session-3", "speaker-a");
            service.appendAudio("session-3", pcmSeconds(6));

            verify(integration, timeout(1000).times(1)).detect(any(), eq("speaker-a"));
            assertTrue(waitFor(() -> service.resolveGender("session-3", "speaker-a") == VoiceGender.UNKNOWN,
                    Duration.ofSeconds(2)));
        } finally {
            service.shutdown();
        }
    }

    private static SpeakerVoiceGenderService service(
            VoiceGenderProperties properties,
            VoiceGenderIntegration integration) {
        return new SpeakerVoiceGenderService(
                properties,
                new SpeakerGenderAudioBufferService(properties),
                integration
        );
    }

    private static VoiceGenderProperties properties() {
        VoiceGenderProperties properties = new VoiceGenderProperties();
        properties.setEnabled(true);
        properties.setSampleRate(16_000);
        properties.setMinAudioSeconds(6);
        properties.setMaxAudioSeconds(8);
        properties.setMaxBufferSeconds(12);
        properties.setMaxRetries(3);
        properties.setRetryIntervalSeconds(10);
        properties.setQueueCapacity(4);
        properties.setMinConfidence(0.75D);
        properties.setMinMargin(0.15D);
        return properties;
    }

    private static byte[] pcmSeconds(int seconds) {
        return new byte[16_000 * 2 * seconds];
    }

    private static boolean waitFor(BooleanSupplier supplier, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (supplier.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return supplier.getAsBoolean();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
