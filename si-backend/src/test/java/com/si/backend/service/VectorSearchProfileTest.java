package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSearchProfileTest {

    @Test
    void searchPassesActiveProfileAndCandidateLimitToMapper() {
        InterpretationEmbeddingMapper mapper = mock(InterpretationEmbeddingMapper.class);
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEmbeddingProfile("qa-v2");
        properties.setEmbeddingCandidateLimit(123);
        properties.setEmbeddingTopK(5);
        properties.setEmbeddingMinScore(0f);
        VectorSearchService service = new VectorSearchService(mapper, properties);

        InterpretationEmbedding row = row(1L, "qa-v2", new float[]{1f, 0f}, "current profile text");
        when(mapper.findByUserId(7L, null, null, null, "qa-v2", 123)).thenReturn(List.of(row));

        List<VectorSearchService.SearchResult> results =
                service.search(7L, "profile text", new float[]{1f, 0f}, null, null, null, 5);

        assertEquals(1, results.size());
        verify(mapper).findByUserId(eq(7L), eq(null), eq(null), eq(null), eq("qa-v2"), eq(123));
    }

    @Test
    void searchKeepsCurrentProfileWhenLegacyFallbackHasSameSource() {
        InterpretationEmbeddingMapper mapper = mock(InterpretationEmbeddingMapper.class);
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEmbeddingProfile("qa-v2");
        properties.setEmbeddingCandidateLimit(2000);
        properties.setEmbeddingTopK(5);
        properties.setEmbeddingMinScore(0f);
        VectorSearchService service = new VectorSearchService(mapper, properties);

        InterpretationEmbedding current = row(42L, "qa-v2", new float[]{1f, 0f}, "current profile");
        InterpretationEmbedding legacy = row(42L, "default", new float[]{1f, 0f}, "legacy profile");
        when(mapper.findByUserId(7L, null, null, null, "qa-v2", 2000)).thenReturn(List.of(current, legacy));

        List<VectorSearchService.SearchResult> results =
                service.search(7L, "profile", new float[]{1f, 0f}, null, null, null, 5);

        assertEquals(1, results.size());
        assertEquals("current profile", results.getFirst().sourceText());
    }

    private static InterpretationEmbedding row(Long sourceId, String profile, float[] vector, String text) {
        InterpretationEmbedding row = new InterpretationEmbedding();
        row.setId(sourceId);
        row.setSourceType(ContentEmbeddingService.TYPE_FILE_CONTENT);
        row.setSourceId(sourceId);
        row.setEmbeddingProfile(profile);
        row.setChunkText(text);
        row.setEmbedding(VectorSearchService.toBytes(vector));
        return row;
    }
}
