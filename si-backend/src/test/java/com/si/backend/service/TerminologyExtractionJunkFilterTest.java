package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动术语垃圾过滤:通用单位/符号/纯数字被丢弃,真实专业术语保留。
 */
class TerminologyExtractionJunkFilterTest {

    @Test
    void filtersGenericUnitsAndSymbols() {
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("百分比", "%"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("PPM", "ppm"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("公顷", "Ha"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("公斤", "kg"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("吨", "ton"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("印尼盾", "Rp"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("单位", "KEBUN"));
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("面积", "LUAS"));
    }

    @Test
    void filtersSingleCharAndPureNumberOrSymbol() {
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("钾", "K"));      // 单字符印尼侧
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("数字", "123"));   // 纯数字
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm("符号", "%%"));    // 纯符号
        assertTrue(TerminologyExtractionService.isLikelyJunkTerm(null, "x"));
    }

    @Test
    void keepsRealDomainTerms() {
        assertFalse(TerminologyExtractionService.isLikelyJunkTerm("施肥机", "Emdek"));
        assertFalse(TerminologyExtractionService.isLikelyJunkTerm("弄给", "Nungki"));
        assertFalse(TerminologyExtractionService.isLikelyJunkTerm("四园二区", "Berlian 2"));
        assertFalse(TerminologyExtractionService.isLikelyJunkTerm("叶片分析", "LSU"));
        assertFalse(TerminologyExtractionService.isLikelyJunkTerm("机械化施肥", "Mekanisasi Pemupukan"));
    }
}
