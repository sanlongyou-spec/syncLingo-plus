package com.si.backend.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes translated text into a safer form for TTS synthesis without changing stored subtitles.
 */
public final class TtsTextNormalizer {

    private static final int PCM_BYTES_PER_SAMPLE = 2;
    private static final long ZH_MIN_FORWARD_MS = 8_000L;
    private static final long ZH_MAX_FORWARD_MS = 30_000L;
    private static final long ZH_BASE_FORWARD_MS = 3_000L;
    private static final long ZH_MS_PER_CHAR = 350L;
    private static final long DEFAULT_MAX_FORWARD_MS = 45_000L;

    private static final Pattern YEAR_RANGE =
            Pattern.compile("(?<!\\d)(\\d{4})\\s*/\\s*(\\d{4})(年度|财年)?");
    private static final Pattern THOUSANDS_COMMA =
            Pattern.compile("(?<=\\d),(?=\\d{3}(\\D|$))");
    private static final Pattern KG_PER_POKOK =
            Pattern.compile("(?i)\\bkg\\s+per\\s+pokok\\b");
    private static final Pattern KG_PER_PLANT_SLASH =
            Pattern.compile("(?i)(公斤|千克|kg)\\s*/\\s*(株|棵|pokok|pohon)");
    private static final Pattern TON_PER_AREA_SLASH =
            Pattern.compile("(?i)(吨|ton|t)\\s*/\\s*(公顷|亩|ha|hektar)");
    private static final Pattern MONEY_PER_UNIT_SLASH =
            Pattern.compile("(?i)(元|人民币|美元|usd|rp)\\s*/\\s*(吨|公斤|千克|株|棵|公顷|亩)");
    private static final Pattern ACRONYM_BEFORE_NUMBER =
            Pattern.compile("\\b([A-Z]{2,6})\\s*(?=\\d)");
    private static final Pattern HORIZONTAL_SPACE =
            Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private TtsTextNormalizer() {
    }

    public static Result normalizeForTts(String text, String targetLang) {
        if (text == null) {
            return new Result(null, "", true);
        }
        String normalized = text;
        if (isChineseTarget(targetLang)) {
            normalized = normalizeChineseReadableText(normalized);
        }
        normalized = HORIZONTAL_SPACE.matcher(normalized).replaceAll(" ").trim();
        return new Result(text, normalized, !normalized.equals(text));
    }

    public static long maxForwardAudioMs(String ttsText, String targetLang) {
        if (ttsText == null || ttsText.isBlank()) {
            return 0L;
        }
        if (!isChineseTarget(targetLang)) {
            return DEFAULT_MAX_FORWARD_MS;
        }
        int codePoints = ttsText.codePointCount(0, ttsText.length());
        long estimated = ZH_BASE_FORWARD_MS + codePoints * ZH_MS_PER_CHAR;
        return Math.min(ZH_MAX_FORWARD_MS, Math.max(ZH_MIN_FORWARD_MS, estimated));
    }

    public static long pcmDurationMs(long pcmBytes, int sampleRate) {
        if (pcmBytes <= 0 || sampleRate <= 0) {
            return 0L;
        }
        return pcmBytes * 1_000L / ((long) sampleRate * PCM_BYTES_PER_SAMPLE);
    }

    private static String normalizeChineseReadableText(String text) {
        String normalized = YEAR_RANGE.matcher(text).replaceAll("$1至$2$3");
        normalized = THOUSANDS_COMMA.matcher(normalized).replaceAll("");
        normalized = KG_PER_POKOK.matcher(normalized).replaceAll("公斤每株");
        normalized = KG_PER_PLANT_SLASH.matcher(normalized).replaceAll("公斤每株");
        normalized = TON_PER_AREA_SLASH.matcher(normalized).replaceAll("吨每$2");
        normalized = MONEY_PER_UNIT_SLASH.matcher(normalized).replaceAll("$1每$2");
        normalized = spaceAcronymsBeforeNumbers(normalized);
        return normalized;
    }

    private static String spaceAcronymsBeforeNumbers(String text) {
        Matcher matcher = ACRONYM_BEFORE_NUMBER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(spaceLetters(matcher.group(1)) + " "));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String spaceLetters(String value) {
        StringBuilder out = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(value.charAt(i));
        }
        return out.toString();
    }

    private static boolean isChineseTarget(String targetLang) {
        return targetLang != null && targetLang.trim().toLowerCase().startsWith("zh");
    }

    public record Result(String originalText, String text, boolean changed) {
    }
}
