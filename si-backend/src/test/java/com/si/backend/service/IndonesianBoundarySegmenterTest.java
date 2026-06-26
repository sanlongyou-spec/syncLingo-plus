package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 方案2 分句切点映射纯函数测试:把 LLM 逐字照抄的"完整句前缀"映射回原文切点。
 * 关键安全属性:LLM 改写/幻觉 → 前缀不匹配 → 返回 0(本轮不切),绝不切错。
 */
class IndonesianBoundarySegmenterTest {

    @Test
    void cleanPrefixCutsAfterTrailingSpace() {
        String working = "Selamat pagi semua. Hari ini kita bahas anggaran";
        String completed = "Selamat pagi semua.";
        int end = IndonesianBoundarySegmenter.completedPrefixEnd(working, completed);
        // 切点应落在句号后、下一个词之前(吃掉空白)
        assertEquals("Selamat pagi semua. ".length(), end);
        assertEquals("Selamat pagi semua.", working.substring(0, end).trim());
    }

    @Test
    void multipleSentencesPrefix() {
        String working = "Satu dua tiga. Empat lima enam. Tujuh delapan";
        String completed = "Satu dua tiga. Empat lima enam.";
        int end = IndonesianBoundarySegmenter.completedPrefixEnd(working, completed);
        assertEquals("Satu dua tiga. Empat lima enam.", working.substring(0, end).trim());
    }

    @Test
    void whitespaceAndCaseInsensitive() {
        String working = "Selamat   pagi  semua. lalu";
        String completed = "selamat pagi   SEMUA.";
        int end = IndonesianBoundarySegmenter.completedPrefixEnd(working, completed);
        assertEquals("Selamat   pagi  semua.", working.substring(0, end).trim());
    }

    @Test
    void llmRewroteText_notAPrefix_returnsZero() {
        String working = "Kita akan membahas anggaran tahun depan";
        String completed = "Mari kita diskusikan anggaran.";   // 改写,非逐字前缀
        assertEquals(0, IndonesianBoundarySegmenter.completedPrefixEnd(working, completed));
    }

    @Test
    void completedLongerThanWorking_returnsZero() {
        String working = "Selamat pagi";
        String completed = "Selamat pagi semua, apa kabar.";
        assertEquals(0, IndonesianBoundarySegmenter.completedPrefixEnd(working, completed));
    }

    @Test
    void blankOrNullInputs_returnZero() {
        assertEquals(0, IndonesianBoundarySegmenter.completedPrefixEnd(null, "x"));
        assertEquals(0, IndonesianBoundarySegmenter.completedPrefixEnd("x", null));
        assertEquals(0, IndonesianBoundarySegmenter.completedPrefixEnd("abc", "   "));
        assertEquals(0, IndonesianBoundarySegmenter.completedPrefixEnd("", "abc"));
    }

    @Test
    void fullMatchConsumesEntireWorking() {
        String working = "Halo dunia.";
        String completed = "Halo dunia.";
        int end = IndonesianBoundarySegmenter.completedPrefixEnd(working, completed);
        assertEquals(working.length(), end);
    }
}
