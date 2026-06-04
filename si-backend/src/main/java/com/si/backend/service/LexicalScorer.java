package com.si.backend.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight tri-lingual lexical (sparse) scoring for hybrid retrieval (Phase 2A).
 *
 * <p>Tokenization handles Chinese, Indonesian and English without a heavy segmenter:
 * <ul>
 *   <li>CJK (Han) runs → character bigrams (e.g. 「柴油价格」 → 柴油 / 油价 / 价格), the same idea as
 *       MySQL's ngram parser — good for matching Chinese terms that vectors dilute.</li>
 *   <li>Latin/digit runs → one lowercased word token (e.g. "logistik", "64", "673").</li>
 * </ul>
 * BM25 ranks documents by lexical overlap with the query, computed in-memory over the candidate set
 * already loaded for the vector search (no extra index / infrastructure needed).
 */
public final class LexicalScorer {

    private LexicalScorer() {}

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) return tokens;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                int j = i;
                while (j < n && isCjk(text.charAt(j))) j++;
                if (j - i == 1) {
                    tokens.add(text.substring(i, j));
                } else {
                    for (int k = i; k + 1 < j; k++) tokens.add(text.substring(k, k + 2));
                }
                i = j;
            } else if (isWordChar(c)) {
                int j = i;
                while (j < n && isWordChar(text.charAt(j))) j++;
                tokens.add(text.substring(i, j).toLowerCase(Locale.ROOT));
                i = j;
            } else {
                i++;
            }
        }
        return tokens;
    }

    static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) && !isCjk(c);
    }

    /**
     * BM25 scores for each document against the query, using the provided document set as the corpus
     * (for IDF / average length). Returns one score per document (0 = no lexical overlap).
     */
    public static double[] bm25(List<List<String>> docs, List<String> query, double k1, double b) {
        int N = docs.size();
        double[] scores = new double[N];
        if (N == 0 || query == null || query.isEmpty()) return scores;

        Set<String> qTerms = new HashSet<>(query);

        int[] docLen = new int[N];
        long totalLen = 0;
        List<Map<String, Integer>> tf = new ArrayList<>(N);
        Map<String, Integer> df = new HashMap<>();
        for (int i = 0; i < N; i++) {
            List<String> doc = docs.get(i);
            docLen[i] = doc.size();
            totalLen += doc.size();
            Map<String, Integer> freq = new HashMap<>();
            for (String t : doc) {
                if (qTerms.contains(t)) freq.merge(t, 1, Integer::sum);
            }
            tf.add(freq);
            for (String t : freq.keySet()) df.merge(t, 1, Integer::sum);
        }
        double avgdl = (double) totalLen / N;
        if (avgdl <= 0) return scores;

        Map<String, Double> idf = new HashMap<>();
        for (String t : qTerms) {
            int dfi = df.getOrDefault(t, 0);
            // BM25+ style idf, always positive
            idf.put(t, Math.log(1 + (N - dfi + 0.5) / (dfi + 0.5)));
        }

        for (int i = 0; i < N; i++) {
            double s = 0;
            int len = docLen[i];
            for (Map.Entry<String, Integer> e : tf.get(i).entrySet()) {
                double f = e.getValue();
                double num = f * (k1 + 1);
                double den = f + k1 * (1 - b + b * len / avgdl);
                s += idf.getOrDefault(e.getKey(), 0.0) * (num / den);
            }
            scores[i] = s;
        }
        return scores;
    }
}
