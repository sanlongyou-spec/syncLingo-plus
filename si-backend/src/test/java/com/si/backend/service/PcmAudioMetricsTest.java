package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PcmAudioMetricsTest {

    @Test
    void calculatesMonoS16Duration() {
        assertEquals(1_000L, PcmAudioMetrics.durationMs(48_000L, 24_000));
        assertEquals(0L, PcmAudioMetrics.durationMs(0L, 24_000));
        assertEquals(0L, PcmAudioMetrics.durationMs(48_000L, 0));
    }
}
