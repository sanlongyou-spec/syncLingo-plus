package com.si.backend.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账本式去重纯函数测试：防 emittedLen 回退导致的 id final 重发/前缀重叠。
 */
class AzureAsrLedgerDedupTest {

    private static String ledgerOf(String emitted) {
        return AzureAsrIntegration.comparableString(emitted);
    }

    private static String dedup(String ledger, String segment) {
        return AzureAsrIntegration.dedupEmitAgainstLedger(ledger, segment);
    }

    // 完全重复:候选整段已在账本中 → 丢弃
    @Test
    void fullyDuplicateSuppressed() {
        String prev = "Pupuk sumber fosfat selanjutnya adalah rencana mengaplikasi pupuk organik";
        assertEquals("", dedup(ledgerOf(prev), prev));
    }

    // 前缀重叠:候选前缀与账本尾部重叠 → 裁掉重叠前缀，发剩余
    @Test
    void prefixOverlapTrimmed() {
        String prevEmitted = "alpha beta gamma delta epsilon zeta eta";
        String cand = "delta epsilon zeta eta theta iota kappa lambda";
        String out = dedup(ledgerOf(prevEmitted), cand);
        assertTrue(out.startsWith("theta"), out);
        assertFalse(out.toLowerCase().contains("delta"), out);
    }

    // 无重叠:原样返回
    @Test
    void noOverlapUnchanged() {
        String ledger = ledgerOf("alpha beta gamma delta epsilon");
        String cand = "completely different words that are long enough here";
        assertEquals(cand, dedup(ledger, cand));
    }

    // 短候选:低于最小判定长度，不去重(避免误伤合法短重复，如多次"谢谢")
    @Test
    void shortCandidateNotDeduped() {
        String ledger = ledgerOf("terima kasih banyak semua hadirin sekalian");
        assertEquals("ya", dedup(ledger, "ya"));
        assertEquals("terima kasih", dedup(ledger, "terima kasih"));
    }

    // 空账本:原样返回
    @Test
    void emptyLedgerUnchanged() {
        String cand = "hello world this is a long enough test sentence";
        assertEquals(cand, dedup("", cand));
    }

    // 大小写/标点不同也能识别为重复(归一化比对)
    @Test
    void normalizedMatchIgnoresCaseAndPunct() {
        String prev = "Komposisi Pupuk didominasi oleh MPK dan DOLOMIT";
        String ledger = ledgerOf(prev);
        assertEquals("", dedup(ledger, "komposisi pupuk, didominasi oleh mpk dan dolomit!"));
    }

    // 真实场景:101 字段后又发 210 字(含 101 为前缀)→ 只发新增部分
    @Test
    void realPrefixOverlapFromLog() {
        String first = "rata rata kg per pokok sedangkan budget mencapai ton dengan rata rata kg per pokok";
        String second = first + " dengan demikian terdapat kenaikan yang cukup besar";
        String out = dedup(ledgerOf(first), second);
        assertTrue(out.startsWith("dengan demikian"), out);
        assertFalse(out.contains("sedangkan budget"), out);
    }
}
