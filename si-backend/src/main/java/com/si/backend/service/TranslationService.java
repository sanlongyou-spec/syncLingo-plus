package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.GoogleTranslateProperties;
import com.si.backend.integration.GoogleTranslateIntegration;
import com.si.backend.integration.LlmIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;

/**
 * 翻译服务，封装翻译业务逻辑与语种校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private final GoogleTranslateIntegration translator;
    private final LlmIntegration llmIntegration;
    private final GoogleTranslateProperties googleProperties;

    /**
     * 检测文本语种（使用 Google Translate 自动检测）。
     *
     * @param text 待检测文本
     * @return BCP-47 语种代码
     */
    public String detectLanguage(String text) {
        log.info("[TranslationService] detectLanguage start, textLen={}",
                text != null ? text.length() : 0);
        if (text == null || text.isBlank()) {
            return Constants.LANG_UNDEFINED;
        }
        long start = System.currentTimeMillis();
        String detectedLang = translator.detectLanguage(text);
        long cost = System.currentTimeMillis() - start;
        log.info("[TranslationService] detectLanguage end, textLen={}, detectedLang={}, costMs={}",
                text.length(), detectedLang, cost);
        return detectedLang;
    }

    /**
     * 翻译单条文本。
     *
     * @param text       原文
     * @param sourceLang 源语言（传 null / "auto" 启用自动检测）
     * @param targetLang 目标语言
     * @return 译文
     */
    public String translate(String text, String sourceLang, String targetLang) {
        log.info("[TranslationService] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);
        if (text == null || text.isBlank()) {
            log.debug("[TranslationService] translate skip, empty text");
            return "";
        }

        if (!isSupportedLang(sourceLang) && !isAutoDetect(sourceLang)) {
            log.warn("[TranslationService] unsupported source lang: {}", sourceLang);
            throw BizException.of(ErrorCode.UNSUPPORTED_LANGUAGE,
                    "不支持的源语言: " + sourceLang);
        }
        if (!isSupportedLang(targetLang)) {
            log.warn("[TranslationService] unsupported target lang: {}", targetLang);
            throw BizException.of(ErrorCode.UNSUPPORTED_LANGUAGE,
                    "不支持的目标语言: " + targetLang);
        }

        long start = System.currentTimeMillis();
        String result = translator.translate(text, sourceLang, targetLang);
        long cost = System.currentTimeMillis() - start;

        // 中文 → 印尼语：压缩
        if (result != null && !result.isBlank()
                && Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang)
                && Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang)) {
            log.info("[TranslationService] compress start, textLen={}", result.length());
            try {
                String compressed = llmIntegration.compress(
                        result,
                        googleProperties.getDashscopeApiKey(),
                        googleProperties.getBaseUrl(),
                        googleProperties.getCompressionModel()
                );
                if (compressed != null && !compressed.isBlank()) {
                    long compMs = System.currentTimeMillis() - start;
                    log.info("[TranslationService] compress end, originalLen={}, compressedLen={}, ratio={}%, totalMs={}",
                            result.length(), compressed.length(),
                            String.format("%.1f", (double) compressed.length() / result.length() * 100),
                            compMs);
                    result = compressed.trim();
                }
            } catch (IOException e) {
                log.warn("[TranslationService] compress failed, use original translation: {}", e.getMessage());
            }
        }

        log.info("[TranslationService] translate end, textLen={}, targetLang={}, costMs={}, resultLen={}",
                text.length(), targetLang, cost, result != null ? result.length() : 0);
        return result;
    }

    /**
     * 批量翻译文本。
     *
     * @param texts      原文列表
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @return 译文列表
     */
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        log.info("[TranslationService] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);
        long start = System.currentTimeMillis();
        List<String> results = translator.translateBatch(texts, sourceLang, targetLang);
        long cost = System.currentTimeMillis() - start;
        log.info("[TranslationService] translateBatch end, count={}, targetLang={}, costMs={}",
                results.size(), targetLang, cost);
        return results;
    }

    private boolean isAutoDetect(String lang) {
        return lang == null || lang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(lang);
    }

    private boolean isSupportedLang(String lang) {
        if (lang == null || lang.isBlank()) return false;
        String lower = lang.toLowerCase();
        return lower.startsWith("zh") || lower.startsWith("id");
    }
}
