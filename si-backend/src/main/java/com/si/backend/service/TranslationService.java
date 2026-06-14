package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.OpenAiProperties;
import com.si.backend.integration.GoogleTranslateIntegration;
import com.si.backend.integration.LlmIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Translation business service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private static final String COMPRESSION_DIRECTION_ZH_TO_ID = "zh->id";
    private static final String COMPRESSION_DIRECTION_ZH_TO_EN = "zh->en";

    private final GoogleTranslateIntegration translator;
    private final LlmIntegration llmIntegration;
    private final OpenAiProperties openAiProperties;
    private final TerminologyService terminologyService;

    public String detectLanguage(String text) {
        log.info("[TranslationService] detectLanguage start, textLen={}", text != null ? text.length() : 0);
        if (text == null || text.isBlank()) {
            return Constants.LANG_UNDEFINED;
        }
        long start = System.currentTimeMillis();
        String detectedLang = translator.detectLanguage(text);
        log.info("[TranslationService] detectLanguage end, textLen={}, detectedLang={}, costMs={}",
                text.length(), detectedLang, System.currentTimeMillis() - start);
        return detectedLang;
    }

    public String translate(String text, String sourceLang, String targetLang) {
        return translate(text, sourceLang, targetLang, 1L);
    }

    public String translate(String text, String sourceLang, String targetLang, Long userId) {
        return translate(text, sourceLang, targetLang, userId, true);
    }

    /**
     * @param allowCompress 是否允许实时 LLM 压缩。false 时跳过压缩、直接返回 Google 译文(省 ~2s 首音)。
     *                      由调用方按"该语言通道是否有播放积压"决定(见 RealtimeInterpretationFacade 阶段1)。
     */
    public String translate(String text, String sourceLang, String targetLang, Long userId, boolean allowCompress) {
        log.info("[TranslationService] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);
        if (text == null || text.isBlank()) {
            log.info("[TranslationService] translate end, blankInput=true, targetLang={}", targetLang);
            return "";
        }
        validateLanguages(sourceLang, targetLang);

        long start = System.currentTimeMillis();
        TerminologyService.TerminologyProtection terminologyProtection =
                terminologyService.applyBeforeTranslate(userId, text, sourceLang, targetLang);
        String protectedText = terminologyProtection.getProtectedText();

        long mtStart = System.currentTimeMillis();
        String result = translator.translate(protectedText, sourceLang, targetLang, userId);
        log.info("[TranslationService] google translate done, sourceLang={}, targetLang={}, costMs={}",
                sourceLang, targetLang, System.currentTimeMillis() - mtStart);
        result = terminologyService.applyAfterTranslate(text, result, sourceLang, targetLang, terminologyProtection, userId);
        result = compressIfNeeded(text, sourceLang, targetLang, result, start, allowCompress);

        log.info("[TranslationService] translate end, textLen={}, targetLang={}, costMs={}, resultLen={}",
                text.length(), targetLang, System.currentTimeMillis() - start, result != null ? result.length() : 0);
        return result;
    }

    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        return translateBatch(texts, sourceLang, targetLang, 1L);
    }

    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang, Long userId) {
        log.info("[TranslationService] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);
        long start = System.currentTimeMillis();
        List<String> results = translator.translateBatch(texts, sourceLang, targetLang, userId);
        log.info("[TranslationService] translateBatch end, count={}, targetLang={}, costMs={}",
                results.size(), targetLang, System.currentTimeMillis() - start);
        return results;
    }

    private void validateLanguages(String sourceLang, String targetLang) {
        if (!isSupportedLang(sourceLang) && !isAutoDetect(sourceLang)) {
            log.warn("[TranslationService] unsupported source lang: {}", sourceLang);
            throw BizException.of(ErrorCode.UNSUPPORTED_LANGUAGE, "Unsupported source language: " + sourceLang);
        }
        if (!isSupportedLang(targetLang)) {
            log.warn("[TranslationService] unsupported target lang: {}", targetLang);
            throw BizException.of(ErrorCode.UNSUPPORTED_LANGUAGE, "Unsupported target language: " + targetLang);
        }
    }

    private String compressIfNeeded(
            String sourceText,
            String sourceLang,
            String targetLang,
            String translatedText,
            long requestStartMs,
            boolean allowCompress
    ) {
        if (!allowCompress) {
            log.debug("[TranslationService] compress skipped (no backlog / channel idle), targetLang={}, textLen={}",
                    targetLang, sourceText != null ? sourceText.length() : 0);
            return translatedText;
        }
        if (translatedText == null || translatedText.isBlank()
                || !openAiProperties.isCompressionEnabled()
                || !isCompressionDirection(sourceLang, targetLang)
                || sourceText.length() < openAiProperties.getCompressionMinTextLength()) {
            log.debug("[TranslationService] compress skipped, enabled={}, sourceLang={}, targetLang={}, textLen={}, threshold={}",
                    openAiProperties.isCompressionEnabled(), sourceLang, targetLang, sourceText.length(),
                    openAiProperties.getCompressionMinTextLength());
            return translatedText;
        }

        String direction = resolveCompressionDirection(targetLang);
        // 目标字数 = 原始中文字数：TTS 以 1.0x 自然语速合成，压缩后译文长度 ≤ 中文原文时
        // TTS 时长 ≈ 原声窗口，前端 ≤1.35x 追赶可消化所有剩余积压。
        int targetMaxChars = sourceText.length();
        log.info("[TranslationService] compress start, direction={}, sourceTextLen={}, translatedLen={}, targetMaxChars={}, model={}",
                direction, sourceText.length(), translatedText.length(), targetMaxChars, openAiProperties.getCompressionModel());
        try {
            String compressed = compressByDirection(translatedText, direction, targetMaxChars);
            if (compressed == null || compressed.isBlank()) {
                return translatedText;
            }
            log.info("[TranslationService] compress end, direction={}, originalLen={}, compressedLen={}, ratio={}%, totalMs={}",
                    direction,
                    translatedText.length(),
                    compressed.length(),
                    String.format("%.1f", (double) compressed.length() / translatedText.length() * 100),
                    System.currentTimeMillis() - requestStartMs);
            return compressed.trim();
        } catch (IOException e) {
            log.warn("[TranslationService] compress failed, direction={}, use original translation: {}",
                    direction, e.getMessage());
            return translatedText;
        }
    }

    private boolean isAutoDetect(String lang) {
        return lang == null || lang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(lang);
    }

    private boolean isSupportedLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return false;
        }
        String lower = lang.toLowerCase();
        return lower.startsWith("zh")
                || lower.startsWith("id")
                || lower.startsWith(Constants.LANG_EN_SHORT);
    }

    private boolean isCompressionDirection(String sourceLang, String targetLang) {
        if (!Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang)) {
            return false;
        }
        return isIndonesianTarget(targetLang)
                || (openAiProperties.isCompressionZhToEnEnabled() && isEnglishTarget(targetLang));
    }

    private String resolveCompressionDirection(String targetLang) {
        return isEnglishTarget(targetLang) ? COMPRESSION_DIRECTION_ZH_TO_EN : COMPRESSION_DIRECTION_ZH_TO_ID;
    }

    private String compressByDirection(String text, String direction, int targetMaxChars) throws IOException {
        if (COMPRESSION_DIRECTION_ZH_TO_EN.equals(direction)) {
            return llmIntegration.compressEnglish(text, targetMaxChars);
        }
        return llmIntegration.compressIndonesian(text, targetMaxChars);
    }

    private boolean isIndonesianTarget(String targetLang) {
        return Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_ID.equalsIgnoreCase(targetLang);
    }

    private boolean isEnglishTarget(String targetLang) {
        return Constants.LANG_EN_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_EN_US.equalsIgnoreCase(targetLang);
    }
}
