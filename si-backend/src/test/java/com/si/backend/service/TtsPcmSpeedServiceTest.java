package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TtsPcmSpeedServiceTest {

    private static final int SAMPLE_RATE = 24_000;
    private static final int BYTES_PER_SAMPLE = 2;

    @Test
    void resolvesBackendSpeedByTargetLanguage() {
        TtsPcmSpeedService service = new TtsPcmSpeedService();

        assertEquals(1.0, service.resolveBackendSpeed("id"), 0.0001);
        assertEquals(1.0, service.resolveBackendSpeed("id-ID"), 0.0001);
        assertEquals(1.0, service.resolveBackendSpeed("zh-CN"), 0.0001);
        assertEquals(1.0, service.resolveBackendSpeed("en-US"), 0.0001);
    }

    @Test
    void neutralSpeedReturnsOriginalPcm() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("en-US");
        byte[] pcm = oneSecondPcm();

        byte[] output = processor.process(pcm);

        assertSame(pcm, output);
        assertEquals(1_000L, PcmAudioMetrics.durationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void indonesianBackendSpeedKeepsOneSecondPcmAtNaturalSpeed() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("id-ID");

        byte[] pcm = oneSecondPcm();
        byte[] output = processor.process(pcm);

        assertSame(pcm, output);
        assertEquals(48_000, output.length);
        assertEquals(1_000L, PcmAudioMetrics.durationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void chineseBackendSpeedKeepsOneSecondPcmAtNaturalSpeed() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("zh-CN");

        byte[] pcm = oneSecondPcm();
        byte[] output = processor.process(pcm);

        assertSame(pcm, output);
        assertEquals(48_000, output.length);
        assertEquals(1_000L, PcmAudioMetrics.durationMs(output.length, SAMPLE_RATE));
    }

    @Test
    void neutralIndonesianSpeedReturnsOddPcmChunkUnchanged() {
        TtsPcmSpeedService.PcmSpeedProcessor processor =
                new TtsPcmSpeedService().processor("id-ID");

        byte[] first = new byte[]{1};
        byte[] second = new byte[]{2, 3, 4};

        assertSame(first, processor.process(first));
        assertSame(second, processor.process(second));
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
