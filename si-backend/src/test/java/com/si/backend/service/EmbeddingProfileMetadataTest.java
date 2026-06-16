package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationEmbedding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EmbeddingProfileMetadataTest {

    @Test
    void currentEmbeddingProfileDefaultsToDefault() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEmbeddingProfile(" ");

        assertEquals("default", InterpretationResultService.currentEmbeddingProfile(properties));
    }

    @Test
    void currentEmbeddingProfileTrimsConfiguredValue() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEmbeddingProfile("  qa-v2  ");

        assertEquals("qa-v2", InterpretationResultService.currentEmbeddingProfile(properties));
    }

    @Test
    void contentHashIsStableAndContentSensitive() {
        String first = InterpretationResultService.contentHash("meeting answer");
        String second = InterpretationResultService.contentHash("meeting answer");
        String different = InterpretationResultService.contentHash("meeting answer changed");

        assertEquals(first, second);
        assertNotEquals(first, different);
        assertEquals(64, first.length());
    }

    @Test
    void applyEmbeddingMetadataStoresProfileModelDimHashAndVectorBytes() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEmbeddingModel("text-embedding-test");
        properties.setEmbeddingProfile("qa-final");
        float[] vector = new float[]{0.25f, -0.5f, 1.0f};

        InterpretationEmbedding embedding = new InterpretationEmbedding();
        InterpretationResultService.applyEmbeddingMetadata(embedding, vector, "source text", properties);

        assertEquals("text-embedding-test", embedding.getEmbeddingModel());
        assertEquals(3, embedding.getEmbeddingDim());
        assertEquals("qa-final", embedding.getEmbeddingProfile());
        assertEquals("READY", embedding.getIndexStatus());
        assertEquals(64, embedding.getContentHash().length());
        assertArrayEquals(vector, VectorSearchService.toFloats(embedding.getEmbedding()), 0.000001f);
    }
}
