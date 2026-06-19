package com.si.backend.integration;

import com.si.backend.service.VoiceGender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 校验 {@link VoiceGenderDetectionResult} 的接受判定与拒绝原因，
 * 确保日志能明确说明为何未选用男声/女声。
 */
class VoiceGenderDetectionResultTest {

    private static final double MIN_CONFIDENCE = 0.75D;
    private static final double MIN_MARGIN = 0.15D;

    @Test
    void acceptsClearGender() {
        VoiceGenderDetectionResult result = new VoiceGenderDetectionResult(
                VoiceGender.MALE, 0.91D, 0.91D, 0.06D, 0.03D, 120L, true, "accepted");
        assertEquals("accepted", result.acceptanceReason(MIN_CONFIDENCE, MIN_MARGIN));
        assertEquals(VoiceGender.MALE, result.acceptedGender(MIN_CONFIDENCE, MIN_MARGIN));
    }

    @Test
    void reportsModelUnavailable() {
        VoiceGenderDetectionResult result = VoiceGenderDetectionResult.unavailable(50L);
        assertEquals("modelUnavailable", result.acceptanceReason(MIN_CONFIDENCE, MIN_MARGIN));
        assertEquals(VoiceGender.UNKNOWN, result.acceptedGender(MIN_CONFIDENCE, MIN_MARGIN));
    }

    @Test
    void reportsServiceGenderUnknown() {
        VoiceGenderDetectionResult result = new VoiceGenderDetectionResult(
                VoiceGender.UNKNOWN, 0.90D, 0.45D, 0.45D, 0.10D, 120L, true, "low_margin");
        assertEquals("serviceGenderUnknown", result.acceptanceReason(MIN_CONFIDENCE, MIN_MARGIN));
        assertEquals(VoiceGender.UNKNOWN, result.acceptedGender(MIN_CONFIDENCE, MIN_MARGIN));
    }

    @Test
    void reportsBelowMinConfidence() {
        VoiceGenderDetectionResult result = new VoiceGenderDetectionResult(
                VoiceGender.MALE, 0.56D, 0.56D, 0.44D, 0D, 120L, true, "low_confidence");
        assertEquals("belowMinConfidence", result.acceptanceReason(MIN_CONFIDENCE, MIN_MARGIN));
        assertEquals(VoiceGender.UNKNOWN, result.acceptedGender(MIN_CONFIDENCE, MIN_MARGIN));
    }

    @Test
    void reportsBelowMinMargin() {
        VoiceGenderDetectionResult result = new VoiceGenderDetectionResult(
                VoiceGender.MALE, 0.80D, 0.80D, 0.78D, 0D, 120L, true, "low_margin");
        assertEquals("belowMinMargin", result.acceptanceReason(MIN_CONFIDENCE, MIN_MARGIN));
        assertEquals(VoiceGender.UNKNOWN, result.acceptedGender(MIN_CONFIDENCE, MIN_MARGIN));
    }
}
