package com.si.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maintains rolling TTS duration estimates by target language.
 */
@Slf4j
@Service
public class SpeechDurationCalibrationService {

    private static final int DEFAULT_MAX_SAMPLES = 30;
    private static final long ESTIMATE_BASE_MS = 200L;
    private static final double DEFAULT_ID_MS_PER_WORD = 300.0;
    private static final double DEFAULT_EN_MS_PER_WORD = 260.0;
    private static final double DEFAULT_WORD_MS = 300.0;
    private static final double DEFAULT_ZH_MS_PER_CHAR = 160.0;
    private static final double DEFAULT_CHAR_MS = 90.0;
    private static final double MIN_WORD_MS = 80.0;
    private static final double MAX_WORD_MS = 900.0;
    private static final double MIN_CHAR_MS = 20.0;
    private static final double MAX_CHAR_MS = 500.0;
    private static final long MIN_AUDIO_MS = 120L;
    private static final long MAX_AUDIO_MS = 60_000L;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private final int maxSamples;
    private final ConcurrentHashMap<String, CalibrationWindow> windows = new ConcurrentHashMap<>();

    public SpeechDurationCalibrationService() {
        this(DEFAULT_MAX_SAMPLES);
    }

    SpeechDurationCalibrationService(int maxSamples) {
        this.maxSamples = Math.max(1, maxSamples);
    }

    public Estimate estimate(String targetLang, String text) {
        String normalizedLang = normalizeLang(targetLang);
        int words = countWords(text);
        int visibleChars = visibleCharCount(text);
        CalibrationSnapshot snapshot = windows.computeIfAbsent(normalizedLang, ignored -> new CalibrationWindow())
                .snapshot(normalizedLang, defaultMsPerWord(normalizedLang), defaultMsPerChar(normalizedLang));
        boolean wordBased = isWordBasedTarget(normalizedLang) && words > 0;
        long estimatedMs;
        if (wordBased) {
            estimatedMs = Math.round(ESTIMATE_BASE_MS + snapshot.msPerWord() * words);
        } else if (visibleChars > 0) {
            estimatedMs = Math.round(ESTIMATE_BASE_MS + snapshot.msPerChar() * visibleChars);
        } else {
            estimatedMs = 0L;
        }
        Estimate estimate = new Estimate(
                targetLang,
                normalizedLang,
                words,
                visibleChars,
                estimatedMs,
                snapshot.msPerWord(),
                snapshot.msPerChar(),
                snapshot.wordSamples(),
                snapshot.charSamples()
        );
        log.info("[SpeechDurationCalibration] estimate lang={}, normalizedLang={}, words={}, visibleChars={}, estimateMs={}, avgMsPerWord={}, avgMsPerChar={}, wordSamples={}, charSamples={}",
                targetLang, normalizedLang, words, visibleChars, estimatedMs,
                round(snapshot.msPerWord()), round(snapshot.msPerChar()), snapshot.wordSamples(), snapshot.charSamples());
        return estimate;
    }

    public UpdateResult recordActualDuration(String targetLang, String text, long actualAudioMs, boolean truncated) {
        String normalizedLang = normalizeLang(targetLang);
        CalibrationWindow window = windows.computeIfAbsent(normalizedLang, ignored -> new CalibrationWindow());
        CalibrationSnapshot before = window.snapshot(normalizedLang, defaultMsPerWord(normalizedLang), defaultMsPerChar(normalizedLang));
        if (truncated) {
            log.debug("[SpeechDurationCalibration] update skipped, reason=truncated, lang={}, actualAudioMs={}",
                    targetLang, actualAudioMs);
            return new UpdateResult(false, "truncated", before);
        }
        if (text == null || text.isBlank()) {
            log.debug("[SpeechDurationCalibration] update skipped, reason=blankText, lang={}, actualAudioMs={}",
                    targetLang, actualAudioMs);
            return new UpdateResult(false, "blank_text", before);
        }
        if (actualAudioMs < MIN_AUDIO_MS || actualAudioMs > MAX_AUDIO_MS) {
            log.warn("[SpeechDurationCalibration] update skipped, reason=audioDurationOutOfRange, lang={}, actualAudioMs={}, minMs={}, maxMs={}",
                    targetLang, actualAudioMs, MIN_AUDIO_MS, MAX_AUDIO_MS);
            return new UpdateResult(false, "audio_duration_out_of_range", before);
        }
        int words = countWords(text);
        int visibleChars = visibleCharCount(text);
        if (words <= 0 && visibleChars <= 0) {
            log.debug("[SpeechDurationCalibration] update skipped, reason=noUnits, lang={}, actualAudioMs={}",
                    targetLang, actualAudioMs);
            return new UpdateResult(false, "no_units", before);
        }
        double measuredBodyMs = Math.max(1.0, actualAudioMs - ESTIMATE_BASE_MS);
        Double wordRate = null;
        if (words > 0) {
            wordRate = measuredBodyMs / words;
            if (wordRate < MIN_WORD_MS || wordRate > MAX_WORD_MS) {
                log.warn("[SpeechDurationCalibration] word sample ignored, lang={}, actualAudioMs={}, words={}, sampleMsPerWord={}, min={}, max={}",
                        targetLang, actualAudioMs, words, round(wordRate), MIN_WORD_MS, MAX_WORD_MS);
                wordRate = null;
            }
        }
        Double charRate = null;
        if (visibleChars > 0) {
            charRate = measuredBodyMs / visibleChars;
            if (charRate < MIN_CHAR_MS || charRate > MAX_CHAR_MS) {
                log.warn("[SpeechDurationCalibration] char sample ignored, lang={}, actualAudioMs={}, visibleChars={}, sampleMsPerChar={}, min={}, max={}",
                        targetLang, actualAudioMs, visibleChars, round(charRate), MIN_CHAR_MS, MAX_CHAR_MS);
                charRate = null;
            }
        }
        if (wordRate == null && charRate == null) {
            return new UpdateResult(false, "all_samples_out_of_range", before);
        }
        CalibrationSnapshot after = window.add(normalizedLang, wordRate, charRate,
                defaultMsPerWord(normalizedLang), defaultMsPerChar(normalizedLang), maxSamples);
        log.info("[SpeechDurationCalibration] update lang={}, normalizedLang={}, actualMs={}, words={}, visibleChars={}, sampleMsPerWord={}, sampleMsPerChar={}, avgMsPerWord={}, avgMsPerChar={}, wordSamples={}, charSamples={}",
                targetLang, normalizedLang, actualAudioMs, words, visibleChars,
                wordRate != null ? round(wordRate) : null,
                charRate != null ? round(charRate) : null,
                round(after.msPerWord()), round(after.msPerChar()), after.wordSamples(), after.charSamples());
        return new UpdateResult(true, "accepted", after);
    }

    private static String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "default";
        }
        String lower = lang.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) {
            return "zh";
        }
        if (lower.startsWith("id") || "in".equals(lower)) {
            return "id";
        }
        if (lower.startsWith("en")) {
            return "en";
        }
        int dash = lower.indexOf('-');
        return dash > 0 ? lower.substring(0, dash) : lower;
    }

    private static boolean isWordBasedTarget(String normalizedLang) {
        return !"zh".equals(normalizedLang);
    }

    private static double defaultMsPerWord(String normalizedLang) {
        return switch (normalizedLang) {
            case "id" -> DEFAULT_ID_MS_PER_WORD;
            case "en" -> DEFAULT_EN_MS_PER_WORD;
            default -> DEFAULT_WORD_MS;
        };
    }

    private static double defaultMsPerChar(String normalizedLang) {
        return "zh".equals(normalizedLang) ? DEFAULT_ZH_MS_PER_CHAR : DEFAULT_CHAR_MS;
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int visibleCharCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class CalibrationWindow {
        private final ArrayDeque<Double> wordRates = new ArrayDeque<>();
        private final ArrayDeque<Double> charRates = new ArrayDeque<>();
        private double wordTotal;
        private double charTotal;

        synchronized CalibrationSnapshot add(
                String normalizedLang,
                Double wordRate,
                Double charRate,
                double defaultWordMs,
                double defaultCharMs,
                int maxSamples) {
            if (wordRate != null) {
                wordRates.addLast(wordRate);
                wordTotal += wordRate;
                while (wordRates.size() > maxSamples) {
                    wordTotal -= wordRates.removeFirst();
                }
            }
            if (charRate != null) {
                charRates.addLast(charRate);
                charTotal += charRate;
                while (charRates.size() > maxSamples) {
                    charTotal -= charRates.removeFirst();
                }
            }
            return snapshot(normalizedLang, defaultWordMs, defaultCharMs);
        }

        synchronized CalibrationSnapshot snapshot(String normalizedLang, double defaultWordMs, double defaultCharMs) {
            double wordAvg = wordRates.isEmpty() ? defaultWordMs : wordTotal / wordRates.size();
            double charAvg = charRates.isEmpty() ? defaultCharMs : charTotal / charRates.size();
            return new CalibrationSnapshot(normalizedLang, wordAvg, charAvg, wordRates.size(), charRates.size());
        }
    }

    public record Estimate(
            String lang,
            String normalizedLang,
            int wordCount,
            int visibleCharCount,
            long estimateMs,
            double msPerWord,
            double msPerChar,
            int wordSamples,
            int charSamples) {
    }

    public record UpdateResult(boolean accepted, String reason, CalibrationSnapshot snapshot) {
    }

    public record CalibrationSnapshot(
            String normalizedLang,
            double msPerWord,
            double msPerChar,
            int wordSamples,
            int charSamples) {
    }
}
