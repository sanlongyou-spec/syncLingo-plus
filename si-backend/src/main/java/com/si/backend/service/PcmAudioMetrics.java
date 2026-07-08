package com.si.backend.service;

/**
 * Audio metrics for mono 16-bit PCM streams.
 */
public final class PcmAudioMetrics {

    private static final int PCM_BYTES_PER_SAMPLE = 2;

    private PcmAudioMetrics() {
    }

    public static long durationMs(long pcmBytes, int sampleRate) {
        if (pcmBytes <= 0 || sampleRate <= 0) {
            return 0L;
        }
        return pcmBytes * 1_000L / ((long) sampleRate * PCM_BYTES_PER_SAMPLE);
    }
}
