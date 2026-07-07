package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TtsPcmChunkCoalescerTest {

    private static final int SAMPLE_RATE = 24_000;
    private static final long MIN_CHUNK_MS = 120L;
    private static final int BYTES_PER_SAMPLE = 2;

    @Test
    void largeChunkPassesThroughWhenNoPending() {
        TtsPcmChunkCoalescer coalescer = new TtsPcmChunkCoalescer(SAMPLE_RATE, MIN_CHUNK_MS);
        byte[] pcm = pcmForMs(121);

        byte[] output = coalescer.accept(pcm);

        assertSame(pcm, output);
        assertEquals(0, coalescer.finish().length);
    }

    @Test
    void shortChunksAreMergedUntilThreshold() {
        TtsPcmChunkCoalescer coalescer = new TtsPcmChunkCoalescer(SAMPLE_RATE, MIN_CHUNK_MS);
        byte[] first = pcmForMs(40);
        byte[] second = pcmForMs(80);

        assertEquals(0, coalescer.accept(first).length);
        byte[] output = coalescer.accept(second);

        assertArrayEquals(concat(first, second), output);
        assertEquals(0, coalescer.finish().length);
    }

    @Test
    void finishFlushesRemainingShortChunk() {
        TtsPcmChunkCoalescer coalescer = new TtsPcmChunkCoalescer(SAMPLE_RATE, MIN_CHUNK_MS);
        byte[] pcm = pcmForMs(30);

        assertEquals(0, coalescer.accept(pcm).length);

        assertArrayEquals(pcm, coalescer.finish());
        assertEquals(0, coalescer.finish().length);
    }

    @Test
    void nullAndEmptyInputAreIgnored() {
        TtsPcmChunkCoalescer coalescer = new TtsPcmChunkCoalescer(SAMPLE_RATE, MIN_CHUNK_MS);

        assertEquals(0, coalescer.accept(null).length);
        assertEquals(0, coalescer.accept(new byte[0]).length);
        assertEquals(0, coalescer.finish().length);
    }

    private static byte[] pcmForMs(int durationMs) {
        int sampleCount = SAMPLE_RATE * durationMs / 1000;
        byte[] pcm = new byte[sampleCount * BYTES_PER_SAMPLE];
        for (int sample = 0; sample < sampleCount; sample++) {
            short value = (short) (sample % Short.MAX_VALUE);
            int offset = sample * BYTES_PER_SAMPLE;
            pcm[offset] = (byte) (value & 0xff);
            pcm[offset + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return pcm;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] merged = new byte[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }
}
