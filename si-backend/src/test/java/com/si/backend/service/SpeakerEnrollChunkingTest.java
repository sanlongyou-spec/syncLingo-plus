package com.si.backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 证明"多样本注册"的核心：把累计 PCM 切成多条样本。
 * 16kHz mono 16-bit → 每秒 32000 字节；chunkSeconds=10 → 每条 320000 字节；最多 maxChunks 条。
 */
class SpeakerEnrollChunkingTest {

    private static final int BYTES_PER_SEC = 16000 * 2; // 32000

    private static byte[] pcmOf(int seconds) {
        return new byte[seconds * BYTES_PER_SEC];
    }

    @Test
    void shortAudioGivesSingleSample() {
        // 8s ≤ 10s → 1 条
        assertEquals(1, SpeakerIdentityService.splitPcmIntoChunks(pcmOf(8), 10, 4).size());
    }

    @Test
    void thirtySecondsGivesThreeSamples() {
        // 30s = 3×10s 整除 → 3 条
        List<byte[]> chunks = SpeakerIdentityService.splitPcmIntoChunks(pcmOf(30), 10, 4);
        assertEquals(3, chunks.size());
        chunks.forEach(c -> assertEquals(10 * BYTES_PER_SEC, c.length));
    }

    @Test
    void twentyFiveSecondsGivesThreeSamples() {
        // 25s → 10+10+5，尾部 5s = 半条 → 单独成第 3 条
        assertEquals(3, SpeakerIdentityService.splitPcmIntoChunks(pcmOf(25), 10, 4).size());
    }

    @Test
    void cappedAtMaxChunks() {
        // 43s 本应 4+ 条，上限 4 → 4 条（尾料并入最后一条）
        assertEquals(4, SpeakerIdentityService.splitPcmIntoChunks(pcmOf(43), 10, 4).size());
        // 50s 也被限制为 4 条
        assertEquals(4, SpeakerIdentityService.splitPcmIntoChunks(pcmOf(50), 10, 4).size());
    }

    @Test
    void emptyGivesNothingUsable() {
        assertEquals(1, SpeakerIdentityService.splitPcmIntoChunks(pcmOf(3), 10, 4).size());
    }
}
