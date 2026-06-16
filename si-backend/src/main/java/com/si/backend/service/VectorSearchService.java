package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final InterpretationEmbeddingMapper embeddingMapper;
    private final OpenAiProperties openAiProperties;

    /** Hybrid retrieval (Phase 2A): fuse dense cosine with in-memory BM25 via RRF. */
    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;
    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;
    @Value("${rag.hybrid.bm25-k1:1.2}")
    private double bm25K1;
    @Value("${rag.hybrid.bm25-b:0.75}")
    private double bm25B;

    public record SearchResult(
            String sourceType,
            Long sourceId,
            Long refId,
            String sessionId,
            Long meetingId,
            String sessionTitle,
            String sessionDate,
            String speakerName,
            String sourceText,
            String translatedText,
            Integer chunkStart,
            float score) {}

    public static byte[] toBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) buf.putFloat(f);
        return buf.array();
    }

    public static float[] toFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new float[0];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) floats[i] = buf.getFloat();
        return floats;
    }

    public static float cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na  += (double) a[i] * a[i];
            nb  += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom < 1e-10 ? 0f : (float) (dot / denom);
    }

    /** Backward-compatible entry point (dense-only — no query text for BM25). */
    public List<SearchResult> search(
            long userId,
            float[] queryVec,
            Long filterMeetingId,
            String filterSpeakerName,
            String filterSince,
            int topK) {
        return search(userId, null, queryVec, filterMeetingId, filterSpeakerName, filterSince, topK);
    }

    /**
     * Search for the most relevant embeddings for a user. Loads candidates and scores in-memory.
     * When hybrid is enabled and {@code queryText} is provided, fuses dense cosine with in-memory
     * BM25 via RRF (Phase 2A) so keyword-strong matches that vectors dilute are still retrieved.
     */
    public List<SearchResult> search(
            long userId,
            String queryText,
            float[] queryVec,
            Long filterMeetingId,
            String filterSpeakerName,
            String filterSince,
            int topK) {

        if (queryVec == null || queryVec.length == 0) return List.of();
        int limit = topK > 0 ? topK : openAiProperties.getEmbeddingTopK();
        float minScore = openAiProperties.getEmbeddingMinScore();
        String profile = InterpretationResultService.currentEmbeddingProfile(openAiProperties);
        int candidateLimit = openAiProperties.getEmbeddingCandidateLimit() > 0
                ? openAiProperties.getEmbeddingCandidateLimit()
                : 2000;

        List<InterpretationEmbedding> candidates =
                deduplicateBySource(embeddingMapper.findByUserId(
                        userId, filterMeetingId, filterSpeakerName, filterSince, profile, candidateLimit));
        int m = candidates.size();
        if (m == 0) return List.of();

        double[] dense = new double[m];
        for (int i = 0; i < m; i++) {
            dense[i] = cosine(queryVec, toFloats(candidates.get(i).getEmbedding()));
        }

        boolean hybrid = hybridEnabled && queryText != null && !queryText.isBlank();
        double[] ranking;
        if (hybrid) {
            List<List<String>> docTokens = new ArrayList<>(m);
            for (InterpretationEmbedding emb : candidates) {
                docTokens.add(LexicalScorer.tokenize(emb.getChunkText()));
            }
            double[] bm25 = LexicalScorer.bm25(docTokens, LexicalScorer.tokenize(queryText), bm25K1, bm25B);
            ranking = HybridFusion.rrfFuse(dense, bm25, rrfK);
        } else {
            ranking = dense;
        }

        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        final double[] rank = ranking;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(rank[b], rank[a]));

        log.debug("[VectorSearchService] search userId={}, profile={}, candidates={}, hybrid={}, topK={}",
                userId, profile, m, hybrid, limit);

        List<SearchResult> results = new ArrayList<>(Math.min(limit, m));
        for (int idx : order) {
            // Dense-only mode keeps the cosine floor; hybrid relies on RRF (a low-cosine but
            // keyword-strong chunk must still surface), so no hard cosine cutoff there.
            if (!hybrid && dense[idx] < minScore) continue;
            InterpretationEmbedding emb = candidates.get(idx);
            results.add(new SearchResult(
                    emb.getSourceType(),
                    emb.getSourceId(),
                    emb.getRefId(),
                    emb.getSessionId(),
                    emb.getMeetingId(),
                    emb.getSessionTitle(),
                    emb.getSessionDate() != null ? emb.getSessionDate().toString() : null,
                    emb.getSpeakerName(),
                    emb.getChunkText(),
                    emb.getTranslatedText(),
                    emb.getChunkStart(),
                    (float) ranking[idx]));
            if (results.size() >= limit) break;
        }
        return results;
    }

    private static List<InterpretationEmbedding> deduplicateBySource(List<InterpretationEmbedding> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        LinkedHashMap<String, InterpretationEmbedding> deduped = new LinkedHashMap<>();
        for (InterpretationEmbedding row : rows) {
            deduped.putIfAbsent(sourceKey(row), row);
        }
        return new ArrayList<>(deduped.values());
    }

    private static String sourceKey(InterpretationEmbedding row) {
        if (row == null) return "null";
        if (row.getSourceType() != null && row.getSourceId() != null) {
            return row.getSourceType() + ":" + row.getSourceId();
        }
        if (row.getResultId() != null) {
            return "result:" + row.getResultId();
        }
        return "row:" + row.getId();
    }
}
