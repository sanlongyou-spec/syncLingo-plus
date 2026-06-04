package com.si.backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for Phase-2A hybrid retrieval building blocks: tri-lingual tokenizer, BM25, RRF fusion. */
class HybridRetrievalTest {

    // ── Tokenizer ────────────────────────────────────────────────────────────

    @Test
    void cjkRunBecomesBigrams() {
        assertEquals(List.of("柴油", "油价", "价格"), LexicalScorer.tokenize("柴油价格"));
    }

    @Test
    void singleCjkCharKeptAsIs() {
        assertEquals(List.of("油"), LexicalScorer.tokenize("油"));
    }

    @Test
    void latinAndDigitsAreLowercasedWordTokens() {
        assertEquals(List.of("logistik", "naik", "30"), LexicalScorer.tokenize("Logistik naik 30%"));
    }

    @Test
    void mixedTrilingualTokenizes() {
        // "柴油 solar 94" -> bigram(s) for CJK + latin/number words
        List<String> t = LexicalScorer.tokenize("柴油 solar 94");
        assertTrue(t.contains("柴油"));
        assertTrue(t.contains("solar"));
        assertTrue(t.contains("94"));
    }

    // ── BM25 ─────────────────────────────────────────────────────────────────

    @Test
    void bm25RanksTheKeywordDocHighest() {
        // Doc 1 mentions the queried number; others don't.
        List<List<String>> docs = List.of(
                LexicalScorer.tokenize("公司本季度销量64673吨"),
                LexicalScorer.tokenize("化肥运输耗时较长"),
                LexicalScorer.tokenize("物流成本上涨"));
        double[] scores = LexicalScorer.bm25(docs, LexicalScorer.tokenize("销量64673吨"), 1.2, 0.75);
        assertTrue(scores[0] > scores[1], "the doc containing the keyword must score higher");
        assertTrue(scores[0] > scores[2]);
    }

    @Test
    void bm25ZeroWhenNoOverlap() {
        List<List<String>> docs = List.of(LexicalScorer.tokenize("完全无关的内容"));
        double[] scores = LexicalScorer.bm25(docs, LexicalScorer.tokenize("柴油价格"), 1.2, 0.75);
        assertEquals(0.0, scores[0], 1e-9);
    }

    @Test
    void bm25EmptyInputsSafe() {
        assertEquals(0, LexicalScorer.bm25(List.of(), List.of("x"), 1.2, 0.75).length);
        double[] s = LexicalScorer.bm25(List.of(LexicalScorer.tokenize("abc")), List.of(), 1.2, 0.75);
        assertEquals(1, s.length);
        assertEquals(0.0, s[0], 1e-9);
    }

    // ── RRF fusion ───────────────────────────────────────────────────────────

    @Test
    void rrfRescuesKeywordStrongButDenseWeakCandidate() {
        // Candidate 2 has the WORST dense score but the BEST keyword score — hybrid must rank it up.
        double[] dense  = {0.9, 0.8, 0.1};   // idx2 worst on dense
        double[] sparse = {0.0, 0.0, 5.0};   // idx2 best on sparse
        double[] fused = HybridFusion.rrfFuse(dense, sparse, 60);
        // idx2 should now beat idx1 (which had middling dense, zero sparse)
        assertTrue(fused[2] > fused[1], "keyword-strong candidate should be rescued by RRF");
    }

    @Test
    void rrfTopStaysTopWhenBothAgree() {
        double[] dense  = {0.9, 0.5, 0.2};
        double[] sparse = {3.0, 1.0, 0.0};
        double[] fused = HybridFusion.rrfFuse(dense, sparse, 60);
        int best = 0;
        for (int i = 1; i < fused.length; i++) if (fused[i] > fused[best]) best = i;
        assertEquals(0, best, "candidate strong on both signals should rank first");
    }

    @Test
    void rrfEmptySafe() {
        assertEquals(0, HybridFusion.rrfFuse(new double[0], new double[0], 60).length);
    }
}
