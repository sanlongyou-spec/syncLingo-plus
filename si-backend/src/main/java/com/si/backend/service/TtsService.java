package com.si.backend.service;

import com.si.backend.config.CartesiaProperties;
import com.si.backend.integration.CartesiaStreamingIntegration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * TTS business service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final CartesiaProperties properties;
    private final CartesiaStreamingIntegration cartesiaStreamingIntegration;

    @PostConstruct
    public void init() {
        log.info("[TtsService] init start, prewarming default voice pools async");
        int warmCount = properties.getPool().getMinIdlePerVoice();
        CompletableFuture.runAsync(() -> {
            cartesiaStreamingIntegration.prewarmPool(properties.getDefaultVoiceIdChinese(), warmCount);
            cartesiaStreamingIntegration.prewarmPool(properties.getDefaultVoiceIdIndonesian(), warmCount);
            if (properties.getDefaultVoiceIdEnglish() != null && !properties.getDefaultVoiceIdEnglish().isBlank()) {
                cartesiaStreamingIntegration.prewarmPool(properties.getDefaultVoiceIdEnglish(), warmCount);
            }
            if (properties.getVoiceGender() != null) {
                prewarmIfConfigured(properties.getVoiceGender().getMaleVoiceId(), warmCount);
                prewarmIfConfigured(properties.getVoiceGender().getFemaleVoiceId(), warmCount);
            }
            log.info("[TtsService] init end");
        });
    }

    public void synthesizeStream(
            String voiceId,
            String text,
            int sampleRate,
            double speed,
            String language,
            Consumer<byte[]> onChunk,
            Runnable onComplete,
            Consumer<String> onError
    ) {
        log.info("[TtsService] synthesizeStream start, voiceId={}, textLen={}, sampleRate={}, speed={}, language={}",
                voiceId, text != null ? text.length() : 0, sampleRate, speed, language);
        cartesiaStreamingIntegration.synthesizeStream(
                voiceId,
                text,
                sampleRate,
                speed,
                language,
                onChunk,
                () -> {
                    log.info("[TtsService] synthesizeStream end, voiceId={}", voiceId);
                    onComplete.run();
                },
                error -> {
                    log.error("[TtsService] synthesizeStream failed, voiceId={}, error={}", voiceId, error);
                    onError.accept(error);
                }
        );
    }

    private void prewarmIfConfigured(String voiceId, int warmCount) {
        if (voiceId != null && !voiceId.isBlank()) {
            cartesiaStreamingIntegration.prewarmPool(voiceId.trim(), warmCount);
        }
    }
}
