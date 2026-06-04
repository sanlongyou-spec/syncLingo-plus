package com.si.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for P1-6 dimension extraction (speaker + time range) from a natural-language question. */
class QuestionFilterTest {

    @Test
    void noDimensionsReturnsEmpty() {
        var f = TeamsBotQueryService.extractQuestionFilter("柴油价格涨了多少");
        assertNull(f.speakerName());
        assertNull(f.since());
    }

    @Test
    void extractsSpeakerFromPlainPrefix() {
        var f = TeamsBotQueryService.extractQuestionFilter("张世杰说了什么");
        assertEquals("张世杰", f.speakerName());
    }

    @Test
    void extractsSpeakerFromVerbPattern() {
        var f = TeamsBotQueryService.extractQuestionFilter("关于采购，朱国辉提到的目标是什么");
        assertEquals("朱国辉", f.speakerName());
    }

    @Test
    void recentDaysSince() {
        var f = TeamsBotQueryService.extractQuestionFilter("最近7天的会议讲了什么");
        assertEquals(LocalDate.now().minusDays(7).toString(), f.since());
    }

    @Test
    void nDaysAgoSince() {
        var f = TeamsBotQueryService.extractQuestionFilter("3天前的会议");
        assertEquals(LocalDate.now().minusDays(3).toString(), f.since());
    }

    @Test
    void absoluteYmdSince() {
        var f = TeamsBotQueryService.extractQuestionFilter("2026年5月26日的会议结论");
        assertEquals("2026-05-26", f.since());
    }

    @Test
    void absoluteMonthDaySinceUsesCurrentYear() {
        var f = TeamsBotQueryService.extractQuestionFilter("5月26日讨论了什么");
        assertEquals(LocalDate.of(LocalDate.now().getYear(), 5, 26).toString(), f.since());
    }

    @Test
    void invalidAbsoluteDateIgnored() {
        // 13月 is not a real month -> no date filter (don't filter on junk)
        var f = TeamsBotQueryService.extractQuestionFilter("13月45日");
        assertNull(f.since());
    }

    @Test
    void yesterdaySince() {
        var f = TeamsBotQueryService.extractQuestionFilter("昨天的会议");
        assertEquals(LocalDate.now().minusDays(1).toString(), f.since());
    }
}
