package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VectorSearchServiceTest {

    // ── toBytes / toFloats round-trip ────────────────────────────────────────

    @Test
    void roundTripEmptyArray() {
        float[] original = new float[0];
        byte[] bytes = VectorSearchService.toBytes(original);
        float[] restored = VectorSearchService.toFloats(bytes);
        assertArrayEquals(original, restored, 0f);
    }

    @Test
    void roundTripSingleValue() {
        float[] original = {3.14f};
        float[] restored = VectorSearchService.toFloats(VectorSearchService.toBytes(original));
        assertEquals(1, restored.length);
        assertEquals(3.14f, restored[0], 1e-6f);
    }

    @Test
    void roundTripFullVector() {
        float[] original = {0.1f, -0.5f, 0.9f, Float.MAX_VALUE, Float.MIN_VALUE, 0f, -1f};
        float[] restored = VectorSearchService.toFloats(VectorSearchService.toBytes(original));
        assertArrayEquals(original, restored, 1e-6f);
    }

    @Test
    void toFloatsNullReturnsEmpty() {
        assertEquals(0, VectorSearchService.toFloats(null).length);
    }

    // ── cosine similarity ────────────────────────────────────────────────────

    @Test
    void cosineIdenticalVectorsIsOne() {
        float[] v = {1f, 2f, 3f};
        assertEquals(1.0f, VectorSearchService.cosine(v, v), 1e-6f);
    }

    @Test
    void cosineOrthogonalVectorsIsZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0f, VectorSearchService.cosine(a, b), 1e-6f);
    }

    @Test
    void cosineOppositeVectorsIsMinusOne() {
        float[] a = {1f, 0f};
        float[] b = {-1f, 0f};
        assertEquals(-1f, VectorSearchService.cosine(a, b), 1e-6f);
    }

    @Test
    void cosineEmptyVectorIsZero() {
        assertEquals(0f, VectorSearchService.cosine(new float[0], new float[0]), 0f);
    }

    @Test
    void cosineMismatchedLengthIsZero() {
        assertEquals(0f, VectorSearchService.cosine(new float[]{1f}, new float[]{1f, 2f}), 0f);
    }

    @Test
    void cosineKnownAngle45Degrees() {
        // Two unit vectors at 45°: (1,0) and (√2/2, √2/2)
        float sq = (float) Math.sqrt(2) / 2f;
        float[] a = {1f, 0f};
        float[] b = {sq, sq};
        float result = VectorSearchService.cosine(a, b);
        assertEquals(sq, result, 1e-5f);  // cos(45°) = √2/2 ≈ 0.7071
    }

    @Test
    void cosineZeroVectorIsZero() {
        float[] zero = {0f, 0f};
        float[] v = {1f, 2f};
        assertEquals(0f, VectorSearchService.cosine(zero, v), 0f);
    }

    // ── byte length consistency ───────────────────────────────────────────────

    @Test
    void toBytesLengthIsFourTimesFloatCount() {
        float[] v = new float[1536]; // typical embedding dimension
        for (int i = 0; i < v.length; i++) v[i] = (float) Math.random();
        byte[] bytes = VectorSearchService.toBytes(v);
        assertEquals(1536 * 4, bytes.length);
    }
}
