package com.si.backend.integration;

import com.si.backend.service.VoiceGender;

public record VoiceGenderDetectionResult(
        VoiceGender gender,
        double confidence,
        double maleScore,
        double femaleScore,
        double childScore,
        long latencyMs,
        boolean modelAvailable
) {

    public static VoiceGenderDetectionResult unavailable(long latencyMs) {
        return new VoiceGenderDetectionResult(
                VoiceGender.UNKNOWN, 0D, 0D, 0D, 0D, latencyMs, false);
    }

    public VoiceGender acceptedGender(double minConfidence, double minMargin) {
        if (!modelAvailable) {
            return VoiceGender.UNKNOWN;
        }
        if (gender != VoiceGender.MALE && gender != VoiceGender.FEMALE) {
            return VoiceGender.UNKNOWN;
        }
        if (confidence < minConfidence) {
            return VoiceGender.UNKNOWN;
        }
        if (Math.abs(maleScore - femaleScore) < minMargin) {
            return VoiceGender.UNKNOWN;
        }
        return gender;
    }
}
