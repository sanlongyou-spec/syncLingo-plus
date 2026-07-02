package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsTextNormalizerTest {

    @Test
    void normalizesGeneralChineseTtsRiskPatternsWithoutHardCodingNumbers() {
        String input = "2025/2026年度预算为61,662.65吨，平均12.36公斤/株，NPK13达到29,688吨。";

        TtsTextNormalizer.Result result = TtsTextNormalizer.normalizeForTts(input, "zh-CN");

        assertTrue(result.changed());
        assertEquals("2025至2026年度预算为61662.65吨，平均12.36公斤每株，N P K 13达到29688吨。", result.text());
    }

    @Test
    void keepsNonThousandsDecimalCommaButStillNormalizesUnitSlash() {
        String input = "剂量为13,71公斤/株。";

        TtsTextNormalizer.Result result = TtsTextNormalizer.normalizeForTts(input, "zh-CN");

        assertEquals("剂量为13,71公斤每株。", result.text());
    }

    @Test
    void leavesNonChineseTargetTextMostlyUntouched() {
        String input = "Budget 2026/2027 uses 52,670 ton.";

        TtsTextNormalizer.Result result = TtsTextNormalizer.normalizeForTts(input, "id");

        assertFalse(result.changed());
        assertEquals(input, result.text());
    }

    @Test
    void calculatesPcmDurationFromSampleRate() {
        assertEquals(1_000L, TtsTextNormalizer.pcmDurationMs(48_000L, 24_000));
        assertEquals(0L, TtsTextNormalizer.pcmDurationMs(0L, 24_000));
        assertEquals(0L, TtsTextNormalizer.pcmDurationMs(48_000L, 0));
    }
}
