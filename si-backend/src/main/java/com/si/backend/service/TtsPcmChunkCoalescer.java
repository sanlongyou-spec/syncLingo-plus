package com.si.backend.service;

import com.si.backend.common.Constants;

import java.util.Arrays;

/**
 * Coalesces tiny streamed PCM fragments before forwarding them to the playback client.
 */
public final class TtsPcmChunkCoalescer {

    private static final int PCM_BYTES_PER_SAMPLE = 2;
    private static final byte[] EMPTY = new byte[0];

    private final int minChunkBytes;
    private byte[] pending = EMPTY;

    public TtsPcmChunkCoalescer(int sampleRate, long minDurationMs) {
        int safeSampleRate = sampleRate > 0 ? sampleRate : Constants.DEFAULT_SAMPLE_RATE_TTS;
        long safeDurationMs = Math.max(1L, minDurationMs);
        long minSamples = Math.max(1L, (safeSampleRate * safeDurationMs + 999L) / 1000L);
        long calculatedBytes = minSamples * PCM_BYTES_PER_SAMPLE;
        this.minChunkBytes = (int) Math.min(Integer.MAX_VALUE - 1L, calculatedBytes);
    }

    public byte[] accept(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return EMPTY;
        }
        if (pending.length == 0 && pcm.length >= minChunkBytes) {
            return pcm;
        }

        byte[] merged = appendPending(pcm);
        if (merged.length >= minChunkBytes) {
            return merged;
        }
        pending = merged;
        return EMPTY;
    }

    public byte[] finish() {
        if (pending.length == 0) {
            return EMPTY;
        }
        byte[] output = pending;
        pending = EMPTY;
        return output;
    }

    private byte[] appendPending(byte[] pcm) {
        if (pending.length == 0) {
            return pcm;
        }
        byte[] merged = Arrays.copyOf(pending, pending.length + pcm.length);
        System.arraycopy(pcm, 0, merged, pending.length, pcm.length);
        pending = EMPTY;
        return merged;
    }
}
