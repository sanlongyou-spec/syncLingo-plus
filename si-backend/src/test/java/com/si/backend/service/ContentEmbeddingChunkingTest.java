package com.si.backend.service;

import com.si.backend.service.ContentEmbeddingService.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase-1 sentence-aware chunking + overlap (semantic-dilution fix) and the offset
 * bookkeeping that powers Small-to-Big window expansion.
 */
class ContentEmbeddingChunkingTest {

    // ── splitSentences ───────────────────────────────────────────────────────

    @Test
    void splitSentencesTilesTheWholeTextWithoutGaps() {
        String text = "柴油价格暴涨94%。物流成本上涨30%-45%！市场价格剧烈波动？公司依然完成交付。";
        List<int[]> spans = ContentEmbeddingService.splitSentences(text);
        assertFalse(spans.isEmpty());
        // spans must be contiguous and cover [0, length)
        assertEquals(0, spans.get(0)[0]);
        assertEquals(text.length(), spans.get(spans.size() - 1)[1]);
        for (int i = 1; i < spans.size(); i++) {
            assertEquals(spans.get(i - 1)[1], spans.get(i)[0], "spans must be contiguous (no gap/overlap)");
        }
    }

    @Test
    void splitSentencesDoesNotBreakDecimalNumbers() {
        // The "." in 94.5 must NOT split; the sentence-final 。 must.
        String text = "柴油涨了94.5个百分点。结束。";
        List<int[]> spans = ContentEmbeddingService.splitSentences(text);
        assertEquals(2, spans.size(), "decimal should stay in one sentence");
        assertEquals("柴油涨了94.5个百分点。", text.substring(spans.get(0)[0], spans.get(0)[1]));
    }

    @Test
    void splitSentencesHandlesIndonesianPeriods() {
        String text = "Harga solar naik. Biaya logistik naik. Perusahaan tetap mampu.";
        List<int[]> spans = ContentEmbeddingService.splitSentences(text);
        assertEquals(3, spans.size());
    }

    // ── chunkSmart: offsets ──────────────────────────────────────────────────

    @Test
    void everyChunkStartOffsetMatchesOriginalText() {
        String text = "句子一。句子二！句子三？Kalimat empat. Kalimat lima. 最后一句。";
        List<Chunk> chunks = ContentEmbeddingService.chunkSmart(text, 20, 6);
        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            assertTrue(c.start() >= 0 && c.start() + c.text().length() <= text.length());
            // The recorded offset must point at exactly this chunk's text in the original.
            assertEquals(c.text(), text.substring(c.start(), c.start() + c.text().length()),
                    "chunk_start must index the chunk text in the original (needed for window expansion)");
        }
    }

    @Test
    void adjacentChunksOverlap() {
        // Sentences are ~22 chars; target 50 fits ~2 sentences/chunk so overlap (re-including the
        // last sentence) is possible. (Single-sentence chunks legitimately cannot overlap.)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append("这是第").append(i).append("个比较长一点的句子用于测试切块逻辑。");
        String text = sb.toString();
        List<Chunk> chunks = ContentEmbeddingService.chunkSmart(text, 50, 12);
        assertTrue(chunks.size() >= 2, "should produce multiple chunks");
        for (int i = 1; i < chunks.size(); i++) {
            int prevEnd = chunks.get(i - 1).start() + chunks.get(i - 1).text().length();
            assertTrue(chunks.get(i).start() < prevEnd,
                    "next chunk should start before the previous chunk ends (overlap)");
        }
    }

    @Test
    void chunksRespectTargetSizeExceptUnavoidablyLongSentences() {
        String text = "短句一。短句二。短句三。短句四。短句五。短句六。";
        int target = 12;
        List<Chunk> chunks = ContentEmbeddingService.chunkSmart(text, target, 0);
        for (Chunk c : chunks) {
            // A chunk may exceed target only when it is a single sentence longer than target.
            assertTrue(c.text().length() <= target || isSingleSentence(c.text()),
                    "chunk exceeded target without being a single long sentence: " + c.text());
        }
    }

    @Test
    void singleSentenceLongerThanTargetIsNotDropped() {
        String text = "这是一句没有任何标点的超长句子比目标长度长很多但必须作为一整块保留下来不能丢";
        List<Chunk> chunks = ContentEmbeddingService.chunkSmart(text, 10, 3);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0).text());
        assertEquals(0, chunks.get(0).start());
    }

    @Test
    void emptyAndBlankAreHandled() {
        assertTrue(ContentEmbeddingService.chunkSmart("", 100, 10).isEmpty());
    }

    @Test
    void chunkingTerminatesAndCoversContent() {
        // Guards against infinite loops in the overlap step-back logic.
        String text = "A。B。C。D。E。F。G。H。I。J。";
        List<Chunk> chunks = ContentEmbeddingService.chunkSmart(text, 4, 2);
        assertFalse(chunks.isEmpty());
        // first chunk starts at 0, last chunk reaches the end
        assertEquals(0, chunks.get(0).start());
        Chunk last = chunks.get(chunks.size() - 1);
        assertEquals(text.length(), last.start() + last.text().length());
    }

    private static boolean isSingleSentence(String s) {
        return ContentEmbeddingService.splitSentences(s).size() <= 1;
    }
}
