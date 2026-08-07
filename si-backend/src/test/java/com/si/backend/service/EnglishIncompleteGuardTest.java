package com.si.backend.service;

import com.si.backend.config.AzureSpeechProperties;
import com.si.backend.service.EnglishIncompleteGuard.Decision;
import com.si.backend.service.EnglishIncompleteGuard.EmitAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnglishIncompleteGuardTest {

    private EnglishIncompleteGuard guard() {
        return guard(50, 80, 18, 18);
    }

    private EnglishIncompleteGuard guard(int softMaxWords, int overlongWords, int minEmitWords, int minInputWords) {
        AzureSpeechProperties properties = new AzureSpeechProperties();
        properties.getAsr().setEnSegmentGuardEnabled(true);
        properties.getAsr().setEnSoftMaxWords(softMaxWords);
        properties.getAsr().setEnOverlongEscalationWords(overlongWords);
        properties.getAsr().setEnMinEmitWords(minEmitWords);
        properties.getAsr().setEnSegMinInputWords(minInputWords);
        return new EnglishIncompleteGuard(properties);
    }

    @Test
    void connectorPrepositionAndFunctionTailsHold() {
        EnglishIncompleteGuard guard = guard();

        assertEquals(Decision.HOLD, guard.check("we need to review the financial report and", List.of()).decision());
        assertEquals(Decision.HOLD, guard.check("the implementation is waiting for", List.of()).decision());
        assertEquals(Decision.HOLD, guard.check("the current platform can", List.of()).decision());
        assertEquals(Decision.HOLD, guard.check("the service will call the web API which", List.of()).decision());
    }

    @Test
    void fillerOnlyMeetingNoiseDropsButShortAnswersCanPassFinalCheck() {
        EnglishIncompleteGuard guard = guard();

        assertEquals(Decision.DROP, guard.check("uh so yeah one second yeah", List.of()).decision());
        assertFalse(guard.shouldHoldFinalRemainder("yes", List.of()));
        assertFalse(guard.shouldHoldFinalRemainder("thank you", List.of()));
    }

    @Test
    void dynamicMeetingTermsAreProtectedWithoutHardCodingSpecificMeetingTerms() {
        EnglishIncompleteGuard guard = guard();
        String working = "today we will discuss the market data flow and the project timeline";

        assertTrue(guard.boundaryVeto(working, working.indexOf("data"), List.of("market data flow")));
        assertTrue(guard.boundaryVeto(working, working.indexOf("timeline"), List.of("project timeline")));
        assertFalse(guard.boundaryVeto(working, working.indexOf("and"), List.of("market data flow")));
    }

    @Test
    void financeNumbersUnitsAndFiscalYearsAreProtected() {
        EnglishIncompleteGuard guard = guard();
        String amount = "the budget is 1.5 million dollars for fiscal year 2026";

        assertTrue(guard.boundaryVeto(amount, amount.indexOf("5"), List.of()), "decimal");
        assertTrue(guard.boundaryVeto(amount, amount.indexOf("million"), List.of()), "scale");
        assertTrue(guard.boundaryVeto(amount, amount.indexOf("dollars"), List.of()), "currency");
        assertTrue(guard.boundaryVeto(amount, amount.indexOf("2026"), List.of()), "fiscal year");
    }

    @Test
    void weakBoundariesDoNotBecomeForcedFinals() {
        EnglishIncompleteGuard guard = guard(20, 40, 8, 8);
        String working = "we will review the financial report and the project timeline before the next steering meeting";

        assertEquals(EmitAction.DOWNGRADE_PARTIAL,
                guard.decideEmit(working, working.length(), "force-boundary", List.of()));
        assertEquals(EmitAction.EMIT_FINAL,
                guard.decideEmit(working, working.length(), "sentence-wtpsplit", List.of()));
    }

    @Test
    void shortInterimSegmentsAreHeldEvenWhenBoundaryIsStrong() {
        EnglishIncompleteGuard guard = guard();
        String shortInterim = "the system records are organized";

        assertEquals(EmitAction.HOLD,
                guard.decideEmit(shortInterim, shortInterim.length(), "sentence-wtpsplit", List.of()));
    }

    @Test
    void softMaxTriggersSearchButDoesNotForceUnsafeBoundary() {
        EnglishIncompleteGuard guard = guard(12, 20, 8, 8);
        String belowSoft = "we will review revenue cost and cash flow";
        String unsafe = "we will review revenue cost cash flow budget variance forecast liquidity position and";

        assertFalse(guard.shouldEscalateBoundarySearch(belowSoft));
        assertTrue(guard.shouldEscalateBoundarySearch(unsafe));
        assertEquals(EnglishIncompleteGuard.BoundaryCandidate.none(), guard.findHeuristicBoundary(unsafe, unsafe.length(), List.of()));
    }

    @Test
    void heuristicBoundaryFindsGeneralMeetingTransitionsAfterSoftMax() {
        EnglishIncompleteGuard guard = guard(18, 28, 8, 8);
        String working = "we will review the revenue forecast cash flow position and budget variance for this quarter "
                + "next we will discuss project risks ownership and the follow up actions for each department";

        EnglishIncompleteGuard.BoundaryCandidate candidate =
                guard.findHeuristicBoundary(working, working.length(), List.of("cash flow", "project risks"));

        assertTrue(candidate.found());
        assertEquals("sentence-english-heuristic", candidate.reason());
        String firstSegment = working.substring(0, candidate.index()).trim();
        assertTrue(firstSegment.endsWith("quarter"), firstSegment);
        assertEquals(EmitAction.EMIT_FINAL,
                guard.decideEmit(working, candidate.index(), candidate.reason(), List.of("cash flow", "project risks")));
    }

    @Test
    void finalRemainderWithIncompleteTailIsHeldForNextSegment() {
        EnglishIncompleteGuard guard = guard();

        assertTrue(guard.shouldHoldFinalRemainder("we are looking forward to", List.of()));
        assertFalse(guard.shouldHoldFinalRemainder("we are looking forward to continuing with it", List.of()));
    }
}
