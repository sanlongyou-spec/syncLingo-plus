package com.si.backend.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureAsrFinalRemainderTest {

    @Test
    void alignsFinalRemainderByEmittedTextInsteadOfInterimOffset() {
        String full = "Global yang sesungguhnya. Oleh karena itu satelit hanyalah pintu masuk. "
                + "Starling adalah konektivitas. Starship adalah platform ke depan spartan industri bisnis ai digital twin "
                + "penghindalan satelit platform amerika industri indonesia semua akan digabungkan menjadi 1 "
                + "sistem industri masa depan yang terintegrasi sepenuhnya.";
        int driftedOffset = full.indexOf("i 1 sistem");

        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                full,
                driftedOffset,
                "digabungkan menjadi 1",
                "global yang sesungguhnya oleh karena itu satelit hanyalah pintu masuk "
                        + "starling adalah konektivitas starship adalah platform ke depan spartan industri bisnis ai digital twin "
                        + "penghindalan satelit platform amerika industri indonesia semua akan digabungkan menjadi 1");

        assertTrue(result.aligned());
        assertEquals("sistem industri masa depan yang terintegrasi sepenuhnya.", result.text());
    }

    @Test
    void alignsFinalRemainderWhenAzureAddsPunctuationInsideSuffix() {
        String full = "global yang sesungguhnya oleh karena itu satelit hanyalah pintu masuk "
                + "amerika industri indonesia semua akan digabungkan, menjadi 1 "
                + "sistem industri masa depan yang terintegrasi sepenuhnya.";
        int driftedOffset = full.indexOf("i 1 sistem");

        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                full,
                driftedOffset,
                "digabungkan menjadi 1",
                "global yang sesungguhnya oleh karena itu satelit hanyalah pintu masuk "
                        + "amerika industri indonesia semua akan digabungkan menjadi 1");

        assertTrue(result.aligned());
        assertEquals("sistem industri masa depan yang terintegrasi sepenuhnya.", result.text());
    }

    @Test
    void alignsFinalRemainderWhenAzureNormalizesNumberWord() {
        String full = "global yang sesungguhnya oleh karena itu satelit hanyalah pintu masuk "
                + "amerika industri indonesia semua akan digabungkan menjadi 1 "
                + "sistem industri masa depan yang terintegrasi sepenuhnya.";
        int driftedOffset = full.indexOf("i 1 sistem");

        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                full,
                driftedOffset,
                "digabungkan menjadi satu",
                "global yang sesungguhnya oleh karena itu satelit hanyalah pintu masuk "
                        + "amerika industri indonesia semua akan digabungkan menjadi satu");

        assertTrue(result.aligned());
        assertEquals("sistem industri masa depan yang terintegrasi sepenuhnya.", result.text());
    }

    @Test
    void keepsFullFinalTextWhenAlignmentFailsInsteadOfCuttingFromStaleOffset() {
        String full = "kita sedang membangun platform industri masa depan";
        int driftedOffset = full.indexOf("dustri");

        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                full,
                driftedOffset,
                "not found",
                "not found");

        assertEquals(full, result.text());
    }

    @Test
    void keepsChineseWordsBetweenForcedSegmentAndFinalRemainder() {
        String emitted = "五啊，这个下面第一个图呢是呃我们现在SCB工厂的地方也是新新地方，它正在实行。我我拿了它这个图，大家看一下，";
        String full = "五啊，这个下面第一个图呢是，呃，我们现在SCB工厂的地方也是新新地方，它正在实行。"
                + "我我拿了它这个图大家看一下这里面呃，会写是从你哪个地方过来的，说呃哪一号车，然后司机是谁？";
        int staleOffset = full.indexOf("会写");

        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                full,
                staleOffset,
                "not found",
                emitted);

        assertEquals("这里面呃，会写是从你哪个地方过来的，说呃哪一号车，然后司机是谁？", result.text());
        assertTrue(result.aligned() || result.overlapChars() > 0);
    }

    @Test
    void trimsRepeatedWordAtFinalRemainderBoundary() {
        String full = "terus melakukan uji coba dan perbaikan pada akhirnya menemukan jalur perkembangan yang benar benar tepat 14.";
        int driftedOffset = full.indexOf("benar benar");

        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                full,
                driftedOffset,
                "yang benar",
                "terus melakukan uji coba dan perbaikan pada akhirnya menemukan jalur perkembangan yang benar");

        assertEquals("tepat 14.", result.text());
        assertEquals(5, result.overlapChars());
    }

    @Test
    void stripsLeadingPunctuationFromFinalRemainder() {
        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                "Satelit adalah pintu masuk. Dunia telah memasuki era pertanian digital.",
                "Satelit adalah pintu masuk".length(),
                "pintu masuk",
                "Satelit adalah pintu masuk");

        assertEquals("Dunia telah memasuki era pertanian digital.", result.text());
    }

    @Test
    void returnsFullTextWhenNothingWasForced() {
        AzureAsrIntegration.FinalRemainderResult result = AzureAsrIntegration.finalRemainderAfterForcedSegments(
                "Karena.",
                0,
                "",
                "");

        assertEquals("Karena.", result.text());
    }
}
