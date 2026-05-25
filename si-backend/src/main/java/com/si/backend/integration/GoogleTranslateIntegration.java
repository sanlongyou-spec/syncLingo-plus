package com.si.backend.integration;

import com.google.cloud.translate.Detection;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateException;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.GoogleTranslateProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Google Cloud Translation v2 integration implemented with the official Java SDK.
 */
@Slf4j
@Component
public class GoogleTranslateIntegration {

    private final GoogleTranslateProperties properties;
    private volatile Translate translateClient;

    public GoogleTranslateIntegration(GoogleTranslateProperties properties) {
        this.properties = properties;
    }

    public String detectLanguage(String text) {
        log.info("[GoogleTranslateIntegration] detectLanguage start, textLen={}",
                text != null ? text.length() : 0);

        if (text == null || text.isBlank()) {
            return Constants.LANG_UNDEFINED;
        }

        long start = System.currentTimeMillis();
        try {
            List<Detection> detections = translateClient().detect(List.of(text));
            long cost = System.currentTimeMillis() - start;
            if (detections == null || detections.isEmpty()) {
                throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google language detection result is empty");
            }
            String detectedLang = detections.get(0).getLanguage();
            log.info("[GoogleTranslateIntegration] detectLanguage end, textLen={}, detectedLang={}, costMs={}",
                    text.length(), detectedLang, cost);
            return detectedLang;
        } catch (BizException e) {
            throw e;
        } catch (TranslateException e) {
            log.error("[GoogleTranslateIntegration] detectLanguage error, textLen={}",
                    text != null ? text.length() : 0, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR,
                    "Google language detection service error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[GoogleTranslateIntegration] detectLanguage unexpected error, textLen={}",
                    text != null ? text.length() : 0, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR,
                    "Google language detection service error: " + e.getMessage());
        }
    }

    public String translate(String text, String sourceLang, String targetLang, Long userId) {
        log.info("[GoogleTranslateIntegration] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);

        if (text == null || text.isBlank()) {
            return "";
        }

        long start = System.currentTimeMillis();
        try {
            Translation translation = translateClient().translate(text, buildTranslateOptions(sourceLang, targetLang));
            long cost = System.currentTimeMillis() - start;
            if (translation == null) {
                throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google translation result is empty");
            }
            String result = translation.getTranslatedText();
            log.info("[GoogleTranslateIntegration] translate end, textLen={}, sourceLang={}, targetLang={}, costMs={}, resultLen={}",
                    text.length(), sourceLang, targetLang, cost, result != null ? result.length() : 0);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (TranslateException e) {
            log.error("[GoogleTranslateIntegration] translate error, textLen={}, source={}, target={}",
                    text != null ? text.length() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google translation service error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[GoogleTranslateIntegration] translate unexpected error, textLen={}, source={}, target={}",
                    text != null ? text.length() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google translation service error: " + e.getMessage());
        }
    }

    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang, Long userId) {
        log.info("[GoogleTranslateIntegration] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);

        if (texts == null || texts.isEmpty()) {
            log.info("[GoogleTranslateIntegration] translateBatch end, texts is empty, returning empty list");
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            List<Translation> translations = translateClient()
                    .translate(texts, buildTranslateOptions(sourceLang, targetLang));
            long cost = System.currentTimeMillis() - start;
            List<String> results = translations.stream()
                    .map(Translation::getTranslatedText)
                    .collect(Collectors.toList());
            log.info("[GoogleTranslateIntegration] translateBatch end, count={}, sourceLang={}, targetLang={}, costMs={}",
                    results.size(), sourceLang, targetLang, cost);
            return results;
        } catch (BizException e) {
            throw e;
        } catch (TranslateException e) {
            log.error("[GoogleTranslateIntegration] translateBatch error, count={}, source={}, target={}",
                    texts != null ? texts.size() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google batch translation error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[GoogleTranslateIntegration] translateBatch unexpected error, count={}, source={}, target={}",
                    texts != null ? texts.size() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google batch translation error: " + e.getMessage());
        }
    }

    private Translate translateClient() {
        Translate currentClient = translateClient;
        if (currentClient != null) {
            return currentClient;
        }
        synchronized (this) {
            if (translateClient == null) {
                if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google Translate API key is blank");
                }
                translateClient = TranslateOptions.newBuilder()
                        .setApiKey(properties.getApiKey())
                        .build()
                        .getService();
                log.info("[GoogleTranslateIntegration] Google Cloud Translate client initialized");
            }
            return translateClient;
        }
    }

    private Translate.TranslateOption[] buildTranslateOptions(String sourceLang, String targetLang) {
        List<Translate.TranslateOption> options = new ArrayList<>();
        options.add(Translate.TranslateOption.targetLanguage(normalizeLang(targetLang)));
        options.add(Translate.TranslateOption.format("text"));
        if (!isAutoDetect(sourceLang)) {
            options.add(Translate.TranslateOption.sourceLanguage(normalizeLang(sourceLang)));
        }
        return options.toArray(Translate.TranslateOption[]::new);
    }

    private boolean isAutoDetect(String sourceLang) {
        return sourceLang == null || sourceLang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(sourceLang);
    }

    private String normalizeLang(String lang) {
        if (lang == null) return Constants.LANG_ZH_CN;
        return switch (lang.toLowerCase()) {
            case "zh-cn", "zh-hans" -> Constants.LANG_ZH_CN;
            case "id", "id-id", "in" -> Constants.LANG_ID_SHORT;
            case "en", "en-us", "en-gb" -> Constants.LANG_EN_SHORT;
            default -> lang;
        };
    }
}
