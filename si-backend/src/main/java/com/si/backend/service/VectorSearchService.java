package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final InterpretationEmbeddingMapper embeddingMapper;
    private final OpenAiProperties openAiProperties;

    public record SearchResult(
            String sessionId,
            String sessionTitle,
            String sessionDate,
            String speakerName,
            String sourceText,
            String translatedText,
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

    /**
     * Search for the most semantically similar embeddings for a given user.
     * Loads up to 2000 recent embeddings, computes cosine similarity in-memory.
     */
    public List<SearchResult> search(
            long userId,
            float[] queryVec,
            Long filterMeetingId,
            String filterSpeakerName,
            String filterSince,
            int topK) {

        if (queryVec == null || queryVec.length == 0) return List.of();
        int limit = topK > 0 ? topK : openAiProperties.getEmbeddingTopK();
        float minScore = openAiProperties.getEmbeddingMinScore();

        List<InterpretationEmbedding> candidates =
                embeddingMapper.findByUserId(userId, filterMeetingId, filterSpeakerName, filterSince);

        log.debug("[VectorSearchService] search userId={}, candidates={}, topK={}", userId, candidates.size(), limit);

        List<SearchResult> results = new ArrayList<>(candidates.size());
        for (InterpretationEmbedding emb : candidates) {
            float[] vec = toFloats(emb.getEmbedding());
            float score = cosine(queryVec, vec);
            if (score < minScore) continue;
            results.add(new SearchResult(
                    emb.getSessionId(),
                    emb.getSessionTitle(),
                    emb.getSessionDate() != null ? emb.getSessionDate().toString() : null,
                    emb.getSpeakerName(),
                    emb.getChunkText(),
                    emb.getTranslatedText(),
                    score));
        }

        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results.subList(0, Math.min(limit, results.size()));
    }
}
