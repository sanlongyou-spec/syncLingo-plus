package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热词质量过滤:剔除整句/长标题/表头/单位,保留短专名/术语/人名。
 */
class HotwordUsabilityFilterTest {

    @Test
    void dropsSentencesAndTableHeaders() {
        assertFalse(HotwordExtractionService.isUsableHotword("Perbandingan Nutrient Level LSU Jul '25 vs May '26"));
        assertFalse(HotwordExtractionService.isUsableHotword("Hasil Pengujian Laboratorium Sampel Daun PT. KEZA LINTAS BUANA"));
        assertFalse(HotwordExtractionService.isUsableHotword("根据实验室分析结果调整的施肥剂量（公斤/棵）"));
        assertFalse(HotwordExtractionService.isUsableHotword("叶面分析结果评估及施肥预算方法讨论会"));
        assertFalse(HotwordExtractionService.isUsableHotword("Rd 1"));
        assertFalse(HotwordExtractionService.isUsableHotword("Grand Total"));
        assertFalse(HotwordExtractionService.isUsableHotword("Tabel 1"));
    }

    @Test
    void dropsUnitsAndSymbols() {
        assertFalse(HotwordExtractionService.isUsableHotword("Mg (%)"));
        assertFalse(HotwordExtractionService.isUsableHotword("B (ppm)"));
        assertFalse(HotwordExtractionService.isUsableHotword("Rp000/Ha"));
        assertFalse(HotwordExtractionService.isUsableHotword("Rp/Kg"));
        assertFalse(HotwordExtractionService.isUsableHotword("Ha"));
        assertFalse(HotwordExtractionService.isUsableHotword("公顷"));
        assertFalse(HotwordExtractionService.isUsableHotword("Kg/Pkk"));
        assertFalse(HotwordExtractionService.isUsableHotword("123"));
    }

    @Test
    void keepsShortProperNounsAndTerms() {
        assertTrue(HotwordExtractionService.isUsableHotword("PT. KEZA LINTAS BUANA")); // 4 词机构名
        assertTrue(HotwordExtractionService.isUsableHotword("KSU Bina Usaha"));
        assertTrue(HotwordExtractionService.isUsableHotword("Estate Nungki"));
        assertTrue(HotwordExtractionService.isUsableHotword("Liang Furun"));
        assertTrue(HotwordExtractionService.isUsableHotword("Emdek"));
        assertTrue(HotwordExtractionService.isUsableHotword("南加大区"));
        assertTrue(HotwordExtractionService.isUsableHotword("叶片营养元素状态数据")); // 10 字术语,保留
        assertTrue(HotwordExtractionService.isUsableHotword("LSU"));
    }
}
