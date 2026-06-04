package com.si.backend.service;

import java.util.Arrays;

/**
 * Reciprocal Rank Fusion (RRF) for hybrid retrieval (Phase 2A).
 *
 * <p>Fuses two rankings (dense cosine + sparse BM25) over the SAME candidate set by summing
 * {@code 1 / (k + rank)} from each list. Rank fusion (not score fusion) is robust to the very
 * different score scales of cosine vs BM25 — the industry-standard way to combine them.
 *
 * <p>Every candidate gets a dense rank (cosine is always defined); only candidates with a positive
 * BM25 score contribute a sparse term, so a chunk that vectors dilute but keywords hit gets rescued.
 */
public final class HybridFusion {

    private HybridFusion() {}

    public static double[] rrfFuse(double[] dense, double[] sparse, int k) {
        int n = dense.length;
        double[] fused = new double[n];
        if (n == 0) return fused;

        Integer[] byDense = indicesSortedDesc(dense, n, false);
        for (int rank = 0; rank < byDense.length; rank++) {
            fused[byDense[rank]] += 1.0 / (k + rank + 1);
        }
        Integer[] bySparse = indicesSortedDesc(sparse, n, true);   // positive-only
        for (int rank = 0; rank < bySparse.length; rank++) {
            fused[bySparse[rank]] += 1.0 / (k + rank + 1);
        }
        return fused;
    }

    /** Indices sorted by score descending; when {@code positiveOnly}, drop entries with score &lt;= 0. */
    private static Integer[] indicesSortedDesc(double[] scores, int n, boolean positiveOnly) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(scores[b], scores[a]));
        if (!positiveOnly) return idx;
        int count = 0;
        while (count < n && scores[idx[count]] > 0) count++;
        return Arrays.copyOf(idx, count);
    }
}
