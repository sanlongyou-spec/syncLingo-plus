package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechDurationCalibrationServiceTest {

    @Test
    void estimatesIndonesianFromDefaultWordRateBeforeSamples() {
        SpeechDurationCalibrationService service = new SpeechDurationCalibrationService();

        SpeechDurationCalibrationService.Estimate estimate =
                service.estimate("id-ID", "minggu depan kirim kontrak");

        assertEquals("id", estimate.normalizedLang());
        assertEquals(4, estimate.wordCount());
        assertEquals(1_400L, estimate.estimateMs());
        assertEquals(0, estimate.wordSamples());
    }

    @Test
    void keepsOnlyTheLatestAcceptedSamplesInRollingWindow() {
        SpeechDurationCalibrationService service = new SpeechDurationCalibrationService(2);
        String text = "kata satu";

        assertTrue(service.recordActualDuration("id", text, 1_000L, false).accepted());
        assertTrue(service.recordActualDuration("id", text, 1_200L, false).accepted());
        assertTrue(service.recordActualDuration("id", text, 1_400L, false).accepted());

        SpeechDurationCalibrationService.Estimate estimate = service.estimate("id", text);
        assertEquals(2, estimate.wordSamples());
        assertEquals(1_300L, estimate.estimateMs());
    }

    @Test
    void skipsTruncatedAndOutOfRangeSamples() {
        SpeechDurationCalibrationService service = new SpeechDurationCalibrationService(2);
        String text = "kata satu";
        long defaultEstimateMs = service.estimate("id", text).estimateMs();

        SpeechDurationCalibrationService.UpdateResult truncated =
                service.recordActualDuration("id", text, 1_000L, true);
        SpeechDurationCalibrationService.UpdateResult outOfRange =
                service.recordActualDuration("id", text, 61_000L, false);

        SpeechDurationCalibrationService.Estimate estimate = service.estimate("id", text);
        assertFalse(truncated.accepted());
        assertEquals("truncated", truncated.reason());
        assertFalse(outOfRange.accepted());
        assertEquals("audio_duration_out_of_range", outOfRange.reason());
        assertEquals(0, estimate.wordSamples());
        assertEquals(defaultEstimateMs, estimate.estimateMs());
    }

    @Test
    void estimatesChineseFromVisibleCharacters() {
        SpeechDurationCalibrationService service = new SpeechDurationCalibrationService();
        String text = "你好 世界";

        SpeechDurationCalibrationService.Estimate before = service.estimate("zh-CN", text);
        assertEquals("zh", before.normalizedLang());
        assertEquals(4, before.visibleCharCount());
        assertEquals(840L, before.estimateMs());

        assertTrue(service.recordActualDuration("zh-CN", text, 1_000L, false).accepted());

        SpeechDurationCalibrationService.Estimate after = service.estimate("zh-CN", text);
        assertEquals(1, after.charSamples());
        assertEquals(1_000L, after.estimateMs());
    }
}
