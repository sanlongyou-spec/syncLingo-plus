package com.si.backend.service;

public enum VoiceGender {
    MALE,
    FEMALE,
    UNKNOWN;

    public static VoiceGender from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return VoiceGender.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
