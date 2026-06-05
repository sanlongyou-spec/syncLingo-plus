package com.si.backend.service;

import com.si.backend.service.NameMatchService.Entry;
import com.si.backend.service.NameMatchService.Outcome;
import com.si.backend.service.NameMatchService.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the 会议安排 name → directory matching strategy (Chinese-name anchor + Latin disambiguation). */
class NameMatchServiceTest {

    @Test
    void chinesePartExtractsHanInAnyOrder() {
        assertEquals("张伟", NameMatchService.chinesePart("张伟 David Zhang")); // 华人: 中文在前
        assertEquals("苏明", NameMatchService.chinesePart("Budi 苏明"));        // 印尼: 中文在后
        assertEquals("李明", NameMatchService.chinesePart("李明"));             // 中国人
        assertEquals("", NameMatchService.chinesePart("David Smith"));
    }

    @Test
    void latinTokensLowercaseAndSkipInitials() {
        assertEquals(Set.of("david", "zhang"), NameMatchService.latinTokens("张伟 David Zhang"));
        assertEquals(Set.of("budi"), NameMatchService.latinTokens("Budi 苏明"));
        assertTrue(NameMatchService.latinTokens("李明").isEmpty());
    }

    private static final List<Entry> DIR = List.of(
            new Entry(1, "李明", Set.of()),                       // 中国人
            new Entry(2, "张伟", Set.of("david", "zhang")),       // 华人 张伟 David Zhang
            new Entry(3, "张伟", Set.of("kevin", "zhang")),       // 另一个华人 张伟 Kevin Zhang
            new Entry(4, "苏明", Set.of("budi")));                // 印尼 Budi 苏明

    @Test
    void uniqueChineseNameMatches() {
        assertEquals(new Outcome(Status.MATCHED, 1L), NameMatchService.resolve("李明", DIR));
        assertEquals(new Outcome(Status.MATCHED, 4L), NameMatchService.resolve("Budi 苏明", DIR));
        assertEquals(new Outcome(Status.MATCHED, 4L), NameMatchService.resolve("苏明", DIR)); // 只给中文也命中
    }

    @Test
    void sameChineseNameDisambiguatedByLatin() {
        assertEquals(new Outcome(Status.MATCHED, 2L), NameMatchService.resolve("张伟 David", DIR));
        assertEquals(new Outcome(Status.MATCHED, 3L), NameMatchService.resolve("张伟 Kevin Zhang", DIR));
    }

    @Test
    void sameChineseNameNoLatinIsAmbiguous() {
        // 两个 张伟，会议安排只给中文 → 无法消歧
        assertEquals(Status.AMBIGUOUS, NameMatchService.resolve("张伟", DIR).status());
    }

    @Test
    void unmatchedWhenNoCandidate() {
        assertEquals(Status.UNMATCHED, NameMatchService.resolve("王五", DIR).status());
        assertEquals(Status.UNMATCHED, NameMatchService.resolve("Unknown Person", DIR).status());
    }

    @Test
    void latinOnlyNameMatchesViaTokens() {
        // 没有中文的名字 → 回退到外文匹配（Budi 唯一）
        assertEquals(new Outcome(Status.MATCHED, 4L), NameMatchService.resolve("Budi", DIR));
    }
}
