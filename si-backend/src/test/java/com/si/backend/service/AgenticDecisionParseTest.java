package com.si.backend.service;

import com.si.backend.service.RagEnhancementService.AgenticDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for P2-8 agentic follow-up decision parsing (must fail safe to "enough"). */
class AgenticDecisionParseTest {

    @Test
    void enoughTrueStopsLoop() {
        AgenticDecision d = RagEnhancementService.parseAgenticDecision("{\"enough\":true}");
        assertTrue(d.enough());
        assertNull(d.nextQuery());
    }

    @Test
    void notEnoughWithNextQuery() {
        AgenticDecision d = RagEnhancementService.parseAgenticDecision(
                "{\"enough\":false,\"next_query\":\"化肥发货效率的具体数字\"}");
        assertFalse(d.enough());
        assertEquals("化肥发货效率的具体数字", d.nextQuery());
    }

    @Test
    void toleratesCodeFences() {
        AgenticDecision d = RagEnhancementService.parseAgenticDecision(
                "```json\n{\"enough\":false,\"next_query\":\"q\"}\n```");
        assertFalse(d.enough());
        assertEquals("q", d.nextQuery());
    }

    @Test
    void notEnoughButBlankQueryTreatedAsEnough() {
        // No actionable follow-up -> must stop the loop.
        AgenticDecision d = RagEnhancementService.parseAgenticDecision("{\"enough\":false,\"next_query\":\"\"}");
        assertTrue(d.enough());
        assertNull(d.nextQuery());
    }

    @Test
    void malformedFailsSafeToEnough() {
        assertTrue(RagEnhancementService.parseAgenticDecision("garbage").enough());
        assertTrue(RagEnhancementService.parseAgenticDecision("").enough());
        assertTrue(RagEnhancementService.parseAgenticDecision(null).enough());
    }
}
