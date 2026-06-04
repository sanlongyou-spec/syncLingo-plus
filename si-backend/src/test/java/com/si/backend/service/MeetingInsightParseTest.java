package com.si.backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for P1-5 structured-insight JSON parsing (tolerant of fences / missing keys / junk). */
class MeetingInsightParseTest {

    @Test
    void parsesAllCategories() {
        String json = "{\"decisions\":[\"第三季度攻克供应商评估\"],"
                + "\"risks\":[\"供应商评价率仅35%\",\"化肥发货效率66%未达标\"],"
                + "\"metrics\":[\"柴油+94%\",\"销量64673吨\"],"
                + "\"topics\":[\"数字化转型\"]}";
        Map<String, List<String>> m = MeetingInsightService.parseInsights(json, 12);
        assertEquals(1, m.get("decisions").size());
        assertEquals(2, m.get("risks").size());
        assertEquals(2, m.get("metrics").size());
        assertEquals(1, m.get("topics").size());
        assertTrue(m.get("risks").contains("供应商评价率仅35%"));
    }

    @Test
    void toleratesCodeFences() {
        String json = "```json\n{\"risks\":[\"风险A\"],\"decisions\":[],\"metrics\":[],\"topics\":[]}\n```";
        Map<String, List<String>> m = MeetingInsightService.parseInsights(json, 12);
        assertEquals(List.of("风险A"), m.get("risks"));
        assertFalse(m.containsKey("decisions"), "empty category should be omitted");
    }

    @Test
    void missingKeysAndBlanksHandled() {
        String json = "{\"risks\":[\"\",\"无\",\"真实风险\"]}";
        Map<String, List<String>> m = MeetingInsightService.parseInsights(json, 12);
        assertEquals(List.of("真实风险"), m.get("risks"), "blank and 无 must be filtered");
        assertNull(m.get("metrics"));
    }

    @Test
    void capsPerCategory() {
        String json = "{\"topics\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}";
        Map<String, List<String>> m = MeetingInsightService.parseInsights(json, 3);
        assertEquals(3, m.get("topics").size());
    }

    @Test
    void malformedReturnsEmptyNoThrow() {
        assertTrue(MeetingInsightService.parseInsights("not json at all", 12).isEmpty());
        assertTrue(MeetingInsightService.parseInsights("", 12).isEmpty());
        assertTrue(MeetingInsightService.parseInsights(null, 12).isEmpty());
    }
}
