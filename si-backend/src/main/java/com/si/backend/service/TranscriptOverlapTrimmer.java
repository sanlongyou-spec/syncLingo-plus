package com.si.backend.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes repeated text where the start of the current ASR segment overlaps the end of the
 * immediately preceding segment.
 */
final class TranscriptOverlapTrimmer {

    private static final int MIN_PARTIAL_OVERLAP_CHARS = 2;
    private static final int MIN_FULL_REPEAT_CHARS = 2;

    private TranscriptOverlapTrimmer() {
    }

    static TrimResult trim(String previousText, String currentText) {
        if (currentText == null || currentText.isBlank()) {
            return new TrimResult("", 0);
        }
        if (previousText == null || previousText.isBlank()) {
            return new TrimResult(currentText.trim(), 0);
        }

        ComparisonText previous = normalize(previousText);
        String remaining = currentText.trim();
        int totalOverlap = 0;
        while (!remaining.isBlank()) {
            ComparisonText current = normalize(remaining);
            int overlap = longestSuffixPrefixOverlap(previous.codePoints(), current.codePoints());
            boolean fullRepeat = overlap == current.codePoints().length;
            int requiredOverlap = fullRepeat ? MIN_FULL_REPEAT_CHARS : MIN_PARTIAL_OVERLAP_CHARS;
            if (overlap < requiredOverlap) {
                break;
            }
            totalOverlap += overlap;
            if (fullRepeat) {
                remaining = "";
                break;
            }
            int sourceCutOffset = current.sourceEndOffsets()[overlap - 1];
            remaining = trimLeadingSeparators(remaining.substring(sourceCutOffset));
        }
        return new TrimResult(remaining, totalOverlap);
    }

    private static ComparisonText normalize(String text) {
        List<Integer> codePoints = new ArrayList<>();
        List<Integer> sourceEndOffsets = new ArrayList<>();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                codePoints.add(Character.toLowerCase(codePoint));
                sourceEndOffsets.add(nextOffset);
            }
            offset = nextOffset;
        }
        return new ComparisonText(
                codePoints.stream().mapToInt(Integer::intValue).toArray(),
                sourceEndOffsets.stream().mapToInt(Integer::intValue).toArray()
        );
    }

    private static int longestSuffixPrefixOverlap(int[] previous, int[] current) {
        for (int length = Math.min(previous.length, current.length); length > 0; length--) {
            int previousStart = previous.length - length;
            boolean matches = true;
            for (int i = 0; i < length; i++) {
                if (previous[previousStart + i] != current[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return length;
            }
        }
        return 0;
    }

    private static String trimLeadingSeparators(String text) {
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        return text.substring(offset).trim();
    }

    record TrimResult(String text, int overlapChars) {
    }

    private record ComparisonText(int[] codePoints, int[] sourceEndOffsets) {
    }
}
