package com.si.backend.integration;

import com.si.backend.service.VoiceGender;

public record VoiceGenderDetectionResult(
        VoiceGender gender,
        double confidence,
        double maleScore,
        double femaleScore,
        double childScore,
        long latencyMs,
        boolean modelAvailable,
        String serviceReason
) {

    /** Reason marker returned by acceptedGender/acceptanceReason when the result passes all gates. */
    public static final String REASON_ACCEPTED = "accepted";

    public static VoiceGenderDetectionResult unavailable(long latencyMs) {
        return new VoiceGenderDetectionResult(
                VoiceGender.UNKNOWN, 0D, 0D, 0D, 0D, latencyMs, false, "service_unavailable");
    }

    public VoiceGender acceptedGender(double minConfidence, double minMargin) {
        return REASON_ACCEPTED.equals(acceptanceReason(minConfidence, minMargin))
                ? gender
                : VoiceGender.UNKNOWN;
    }

    /**
     * Explains why {@link #acceptedGender} kept or rejected the detected gender, so logs can
     * show exactly which backend gate dropped a male/female result to UNKNOWN.
     */
    public String acceptanceReason(double minConfidence, double minMargin) {
        if (!modelAvailable) {
            return "modelUnavailable";
        }
        if (gender != VoiceGender.MALE && gender != VoiceGender.FEMALE) {
            return "serviceGenderUnknown";
        }
        if (confidence < minConfidence) {
            return "belowMinConfidence";
        }
        if (Math.abs(maleScore - femaleScore) < minMargin) {
            return "belowMinMargin";
        }
        return REASON_ACCEPTED;
    }
}
