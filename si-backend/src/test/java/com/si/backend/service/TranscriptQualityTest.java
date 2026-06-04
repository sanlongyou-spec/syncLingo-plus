package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the P0-1 ASR transcript noise filter — must drop junk but keep genuine content. */
class TranscriptQualityTest {

    @Test
    void dropsNullEmptyAndWhitespace() {
        assertTrue(TranscriptQuality.isLikelyNoise(null));
        assertTrue(TranscriptQuality.isLikelyNoise(""));
        assertTrue(TranscriptQuality.isLikelyNoise("   \n\t "));
    }

    @Test
    void dropsPunctuationOnly() {
        assertTrue(TranscriptQuality.isLikelyNoise("..."));
        assertTrue(TranscriptQuality.isLikelyNoise("—— !!! ???"));
    }

    @Test
    void dropsSingleCharNoise() {
        assertTrue(TranscriptQuality.isLikelyNoise("a"));
        assertTrue(TranscriptQuality.isLikelyNoise("。"));
    }

    @Test
    void keepsGenuineChinese() {
        assertFalse(TranscriptQuality.isLikelyNoise("柴油价格暴涨94%"));
        assertFalse(TranscriptQuality.isLikelyNoise("销量64673吨"));
    }

    @Test
    void keepsGenuineIndonesianAndEnglish() {
        assertFalse(TranscriptQuality.isLikelyNoise("Harga solar naik"));
        assertFalse(TranscriptQuality.isLikelyNoise("Logistics cost up 30%"));
    }

    @Test
    void keepsShortButRealUtterance() {
        assertFalse(TranscriptQuality.isLikelyNoise("好的"));
        assertFalse(TranscriptQuality.isLikelyNoise("OK"));
    }
}
