package com.si.backend.service;

/**
 * Heuristic quality filter for ASR transcript text (Phase P0-1). Real-time ASR with continuous
 * language ID occasionally mis-detects the spoken language and emits garbage (e.g. Chinese speech
 * transcribed as random English words). Embedding that noise pollutes retrieval, so we skip the
 * worst offenders before they enter the vector store.
 *
 * <p>Deliberately conservative — only drops text that is almost certainly junk, so genuine content
 * (including legitimately short or foreign-language utterances) is kept.
 */
public final class TranscriptQuality {

    private TranscriptQuality() {}

    private static final int MIN_MEANINGFUL_CHARS = 2;

    /** True when the text is almost certainly ASR noise and should not be embedded. */
    public static boolean isLikelyNoise(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return true;

        int letters = 0, digits = 0, cjk = 0, other = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (isCjk(c)) cjk++;
            else if (Character.isLetter(c)) letters++;
            else if (Character.isDigit(c)) digits++;
            else other++;
        }
        int meaningful = letters + digits + cjk;
        if (meaningful < MIN_MEANINGFUL_CHARS) return true;

        // Mostly punctuation/symbols with little real content.
        if (other > meaningful) return true;

        return false;
    }

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
