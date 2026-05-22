package com.si.backend.integration;

import com.google.cloud.translate.Detection;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateException;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextGlossaryConfig;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.GoogleTranslateProperties;
import com.si.backend.service.UserGlossaryConfigService;
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

    private static final String GOOGLE_TRANSLATE_MIME_TYPE_TEXT = "text/plain";

    private final GoogleTranslateProperties properties;
    private final UserGlossaryConfigService glossaryConfigService;
    private volatile Translate translateClient;
    private volatile TranslationServiceClient advancedTranslateClient;

    public GoogleTranslateIntegration(GoogleTranslateProperties properties, UserGlossaryConfigService glossaryConfigService) {
        this.properties = properties;
        this.glossaryConfigService = glossaryConfigService;
    }

    /**
     * Detects the language of a text.
     *
     * @param text text to detect
     * @return BCP-47 language code
     */
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

    /**
     * Translates one text.
     *
     * @param text       source text
     * @param sourceLang source language, or auto
     * @param targetLang target language
     * @return translated text
     */
    public String translate(String text, String sourceLang, String targetLang, Long userId) {
        log.info("[GoogleTranslateIntegration] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);

        if (text == null || text.isBlank()) {
            return "";
        }

        long start = System.currentTimeMillis();
        try {
            if (isGlossaryEnabled(userId, sourceLang, targetLang)) {
                return translateWithGlossary(text, sourceLang, targetLang, userId, start);
            }
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

    /**
     * Translates texts in batch.
     *
     * @param texts      source texts
     * @param sourceLang source language
     * @param targetLang target language
     * @return translated texts
     */
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang, Long userId) {
        log.info("[GoogleTranslateIntegration] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);

        if (texts == null || texts.isEmpty()) {
            log.info("[GoogleTranslateIntegration] translateBatch end, texts is empty, returning empty list");
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            if (isGlossaryEnabled(userId, sourceLang, targetLang)) {
                List<String> results = translateBatchWithGlossary(texts, sourceLang, targetLang, userId, start);
                log.info("[GoogleTranslateIntegration] translateBatch end, count={}, sourceLang={}, targetLang={}, costMs={}, glossary=true",
                        results.size(), sourceLang, targetLang, System.currentTimeMillis() - start);
                return results;
            }
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

    /**
     * Returns whether the current request should use Google Translation Advanced Glossary.
     *
     * @param sourceLang source language
     * @return true when glossary translation is configured and applicable
     */
    public boolean isGlossaryAvailable(Long userId, String sourceLang, String targetLang) {
        return isGlossaryEnabled(userId, sourceLang, targetLang);
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
                log.info("[GoogleTranslateIntegration] official Google Cloud Translate client initialized");
            }
            return translateClient;
        }
    }

    private String translateWithGlossary(String text, String sourceLang, String targetLang, Long userId, long start) {
        try {
            List<String> results = translateBatchWithGlossary(List.of(text), sourceLang, targetLang, userId, start);
            String result = results.isEmpty() ? "" : results.get(0);
            log.info("[GoogleTranslateIntegration] translate end, textLen={}, sourceLang={}, targetLang={}, costMs={}, resultLen={}, glossary=true",
                    text.length(), sourceLang, targetLang, System.currentTimeMillis() - start, result.length());
            return result;
        } catch (Exception e) {
            if (Boolean.TRUE.equals(properties.getGlossaryFallbackEnabled())) {
                log.warn("[GoogleTranslateIntegration] glossary translate failed, fallback basic translate, textLen={}, sourceLang={}, targetLang={}, reason={}",
                        text.length(), sourceLang, targetLang, sanitizeErrorMessage(e.getMessage()));
                Translation translation = translateClient().translate(text, buildTranslateOptions(sourceLang, targetLang));
                if (translation == null) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google translation result is empty");
                }
                String result = translation.getTranslatedText();
                log.info("[GoogleTranslateIntegration] translate end, textLen={}, sourceLang={}, targetLang={}, costMs={}, resultLen={}, glossary=false, fallback=true",
                        text.length(), sourceLang, targetLang, System.currentTimeMillis() - start,
                        result != null ? result.length() : 0);
                return result;
            }
            throw e;
        }
    }

    private List<String> translateBatchWithGlossary(
            List<String> texts,
            String sourceLang,
            String targetLang,
            Long userId,
            long start
    ) {
        try {
            TranslateTextRequest.Builder requestBuilder = TranslateTextRequest.newBuilder()
                    .setParent(LocationName.of(properties.getProjectId(), properties.getLocation()).toString())
                    .setMimeType(GOOGLE_TRANSLATE_MIME_TYPE_TEXT)
                    .setTargetLanguageCode(normalizeLang(targetLang))
                    .setGlossaryConfig(TranslateTextGlossaryConfig.newBuilder()
                            .setGlossary(glossaryName(userId, sourceLang, targetLang))
                            .setIgnoreCase(Boolean.TRUE.equals(properties.getGlossaryIgnoreCase()))
                            .build())
                    .addAllContents(texts);
            if (!isAutoDetect(sourceLang)) {
                requestBuilder.setSourceLanguageCode(normalizeLang(sourceLang));
            }
            TranslateTextResponse response = advancedTranslateClient().translateText(requestBuilder.build());
            List<String> results = extractGlossaryTranslations(response);
            log.info("[GoogleTranslateIntegration] glossary translate done, count={}, sourceLang={}, targetLang={}, costMs={}",
                    results.size(), sourceLang, targetLang, System.currentTimeMillis() - start);
            return results;
        } catch (Exception e) {
            if (Boolean.TRUE.equals(properties.getGlossaryFallbackEnabled())) {
                log.warn("[GoogleTranslateIntegration] glossary batch translate failed, fallback basic translate, count={}, sourceLang={}, targetLang={}, reason={}",
                        texts.size(), sourceLang, targetLang, sanitizeErrorMessage(e.getMessage()));
                List<Translation> translations = translateClient()
                        .translate(texts, buildTranslateOptions(sourceLang, targetLang));
                return translations.stream()
                        .map(Translation::getTranslatedText)
                        .collect(Collectors.toList());
            }
            throw BizException.of(ErrorCode.TRANSLATE_ERROR,
                    "Google glossary translation service error: " + e.getMessage());
        }
    }

    private TranslationServiceClient advancedTranslateClient() {
        TranslationServiceClient currentClient = advancedTranslateClient;
        if (currentClient != null) {
            return currentClient;
        }
        synchronized (this) {
            if (advancedTranslateClient == null) {
                try {
                    advancedTranslateClient = TranslationServiceClient.create();
                    log.info("[GoogleTranslateIntegration] official Google Cloud Translation Advanced client initialized, projectId={}, location={}, glossaryId={}",
                            properties.getProjectId(), properties.getLocation(), properties.getGlossaryId());
                } catch (Exception e) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR,
                            "Google Translation Advanced client init failed: " + e.getMessage());
                }
            }
            return advancedTranslateClient;
        }
    }

    private List<String> extractGlossaryTranslations(TranslateTextResponse response) {
        if (response.getGlossaryTranslationsCount() > 0) {
            return response.getGlossaryTranslationsList().stream()
                    .map(com.google.cloud.translate.v3.Translation::getTranslatedText)
                    .collect(Collectors.toList());
        }
        return response.getTranslationsList().stream()
                .map(com.google.cloud.translate.v3.Translation::getTranslatedText)
                .collect(Collectors.toList());
    }

    private boolean isGlossaryEnabled(Long userId, String sourceLang, String targetLang) {
        if (!Boolean.TRUE.equals(properties.getGlossaryEnabled())) {
            return false;
        }
        if (isAutoDetect(sourceLang)) {
            log.debug("[GoogleTranslateIntegration] glossary skipped for auto source language");
            return false;
        }
        return properties.getProjectId() != null
                && !properties.getProjectId().isBlank()
                && properties.getLocation() != null
                && !properties.getLocation().isBlank()
                && !resolveGlossaryId(userId, sourceLang, targetLang).isBlank();
    }

    private String glossaryName(Long userId, String sourceLang, String targetLang) {
        return "projects/" + properties.getProjectId()
                + "/locations/" + properties.getLocation()
                + "/glossaries/" + resolveGlossaryId(userId, sourceLang, targetLang);
    }

    private String resolveGlossaryId(Long userId, String sourceLang, String targetLang) {
        String userGlossaryId = glossaryConfigService.resolveGlossaryId(userId, normalizeLang(sourceLang), normalizeLang(targetLang));
        if (userGlossaryId != null && !userGlossaryId.isBlank()) {
            return userGlossaryId;
        }
        String source = normalizeLang(sourceLang);
        String target = targetLang == null ? "" : normalizeLang(targetLang);
        String directional = switch (source + "->" + target) {
            case "zh-CN->id" -> properties.getGlossaryZhToIdId();
            case "id->zh-CN" -> properties.getGlossaryIdToZhId();
            case "zh-CN->en" -> properties.getGlossaryZhToEnId();
            case "en->zh-CN" -> properties.getGlossaryEnToZhId();
            case "id->en" -> properties.getGlossaryIdToEnId();
            case "en->id" -> properties.getGlossaryEnToIdId();
            default -> null;
        };
        if (directional != null && !directional.isBlank()) {
            return directional;
        }
        return properties.getGlossaryId() == null ? "" : properties.getGlossaryId();
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.replaceAll("[\\r\\n\\t]+", " ").trim();
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
