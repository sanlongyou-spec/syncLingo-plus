package com.si.backend.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunksTest {

    @Test
    void shortTextSingleChunk() {
        List<String> chunks = TextChunks.split("hello world", 8000, 8);
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }

    @Test
    void longTextSplitIntoMultipleChunksCoveringAll() {
        String text = "a".repeat(25000);
        List<String> chunks = TextChunks.split(text, 10000, 8);
        assertEquals(3, chunks.size());
        // 全文被覆盖,不丢内容
        assertEquals(25000, chunks.stream().mapToInt(String::length).sum());
    }

    @Test
    void maxChunksCapsCost() {
        String text = "b".repeat(100000);
        List<String> chunks = TextChunks.split(text, 10000, 3);
        assertEquals(3, chunks.size()); // 超出部分丢弃
    }

    @Test
    void prefersWhitespaceBoundary() {
        // 10 个 "word " (每个 5 字符) = 50 字符;chunkSize=12 → 应在空格处切,不切断单词
        String text = "word word word word word word word word word word";
        List<String> chunks = TextChunks.split(text, 12, 8);
        for (String c : chunks) {
            assertTrue(c.equals(c.strip()), "chunk 不应以空白开头/结尾: '" + c + "'");
            // 不应出现被切断的半个 "word"(每块要么是完整 word 序列)
            for (String w : c.split("\\s+")) {
                assertEquals("word", w, "单词被切断: '" + w + "'");
            }
        }
    }

    @Test
    void nullAndBlankReturnEmpty() {
        assertTrue(TextChunks.split(null, 100, 5).isEmpty());
        assertTrue(TextChunks.split("   ", 100, 5).isEmpty());
        assertTrue(TextChunks.split("x", 0, 5).isEmpty());
        assertTrue(TextChunks.split("x", 100, 0).isEmpty());
    }
}
