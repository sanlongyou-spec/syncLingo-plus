package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsPcmSpeedServiceTest {

    private static final int SAMPLE_RATE = 24_000;
    private static final int BYTES_PER_SAMPLE = 2;

    @Test
    void resolvesBackendSpeedByTargetLanguage() {
        TtsPcmSpeedService service = new TtsPcmSpeedService();

        assertEquals(1.1, service.resolveBackendSpeed("id"), 0.0001);
        assertEquals(1.1, service.resolveBackendSpeed("id-ID"), 0.0001);
        assertEquals(1.1, service.resolveBackendSpeed("zh-CN"), 0.0001);
        assertEquals(1.1, service.resolveBackendSpeed("en"), 0.0001);
        assertEquals(1.1, service.resolveBackendSpeed("en-US"), 0.0001);
        assertEquals(1.0, service.resolveBackendSpeed("fr-FR"), 0.0001);
    }

    @Test
    void neutralSpeedReturnsOriginalPcm() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("fr-FR");
        byte[] pcm = oneSecondPcm();

        byte[] output = processor.process(pcm);

        assertSame(pcm, output);
        assertEquals(1_000L, TtsTextNormalizer.pcmDurationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void englishBackendSpeedShortensOneSecondPcmToRealOnePointOneSpeed() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("en-US");

        byte[] output = processor.process(oneSecondPcm());

        assertEquals(43_638, output.length);
        assertEquals(909L, TtsTextNormalizer.pcmDurationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void indonesianBackendSpeedShortensOneSecondPcmToRealOnePointOneSpeed() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("id-ID");

        byte[] output = processor.process(oneSecondPcm());

        assertEquals(43_638, output.length);
        assertEquals(909L, TtsTextNormalizer.pcmDurationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void chineseBackendSpeedShortensOneSecondPcmToRealOnePointOneSpeed() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("zh-CN");

        byte[] output = processor.process(oneSecondPcm());

        assertEquals(43_638, output.length);
        assertEquals(909L, TtsTextNormalizer.pcmDurationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void oddPcmByteIsCarriedIntoNextChunkInsteadOfSkippingWholeChunk() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("zh-CN");

        byte[] first = processor.process(new byte[]{1});
        byte[] second = processor.process(new byte[]{2, 3, 4});

        assertEquals(0, first.length);
        assertTrue(second.length > 0);
        assertEquals(0, second.length % BYTES_PER_SAMPLE);
        assertEquals(0, processor.finish().length);
    }

    private static byte[] oneSecondPcm() {
        byte[] pcm = new byte[SAMPLE_RATE * BYTES_PER_SAMPLE];
        for (int sample = 0; sample < SAMPLE_RATE; sample++) {
            short value = (short) (sample % Short.MAX_VALUE);
            int offset = sample * BYTES_PER_SAMPLE;
            pcm[offset] = (byte) (value & 0xff);
            pcm[offset + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return pcm;
    }
}
