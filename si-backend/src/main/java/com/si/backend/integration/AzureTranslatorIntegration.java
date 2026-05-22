package com.si.backend.integration;

import com.azure.ai.translation.text.TextTranslationClient;
import com.azure.ai.translation.text.TextTranslationClientBuilder;
import com.azure.ai.translation.text.models.TranslateOptions;
import com.azure.ai.translation.text.models.TranslatedTextItem;
import com.azure.ai.translation.text.models.TranslationText;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.AzureTranslatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Azure Translator integration implemented with the official Azure Text Translation SDK.
 */
@Slf4j
@Component
public class AzureTranslatorIntegration {

    private final AzureTranslatorProperties properties;
    private volatile TextTranslationClient translationClient;

    public AzureTranslatorIntegration(AzureTranslatorProperties properties) {
        this.properties = properties;
    }

    /**
     * Translates one text.
     *
     * @param text       source text
     * @param sourceLang source language, or auto
     * @param targetLang target language
     * @return translated text
     */
    public String translate(String text, String sourceLang, String targetLang) {
        log.info("[AzureTranslatorIntegration] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);

        if (text == null || text.isBlank()) {
            return "";
        }

        long start = System.currentTimeMillis();
        try {
            TranslatedTextItem translation = translationClient()
                    .translate(text, buildTranslateOptions(sourceLang, targetLang));
            String result = firstTranslatedText(translation);
            log.info("[AzureTranslatorIntegration] translate end, textLen={}, targetLang={}, costMs={}, resultLen={}",
                    text.length(), targetLang, System.currentTimeMillis() - start,
                    result != null ? result.length() : 0);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (HttpResponseException e) {
            log.error("[AzureTranslatorIntegration] translate error, textLen={}, source={}, target={}",
                    text != null ? text.length() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure translation service error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[AzureTranslatorIntegration] translate unexpected error, textLen={}, source={}, target={}",
                    text != null ? text.length() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure translation service error: " + e.getMessage());
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
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        log.info("[AzureTranslatorIntegration] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);

        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            List<String> results = texts.stream()
                    .map(text -> translate(text, sourceLang, targetLang))
                    .collect(Collectors.toList());
            log.info("[AzureTranslatorIntegration] translateBatch end, count={}, targetLang={}, costMs={}",
                    results.size(), targetLang, System.currentTimeMillis() - start);
            return results;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AzureTranslatorIntegration] translateBatch error, count={}, source={}, target={}",
                    texts != null ? texts.size() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure batch translation error: " + e.getMessage());
        }
    }

    private TextTranslationClient translationClient() {
        TextTranslationClient currentClient = translationClient;
        if (currentClient != null) {
            return currentClient;
        }
        synchronized (this) {
            if (translationClient == null) {
                if (properties.getKey() == null || properties.getKey().isBlank()) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure Translator key is blank");
                }
                TextTranslationClientBuilder builder = new TextTranslationClientBuilder()
                        .credential(new AzureKeyCredential(properties.getKey()));
                if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
                    builder.endpoint(properties.getEndpoint());
                }
                if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
                    builder.region(properties.getRegion());
                }
                translationClient = builder.buildClient();
                log.info("[AzureTranslatorIntegration] official Azure Text Translation client initialized");
            }
            return translationClient;
        }
    }

    private TranslateOptions buildTranslateOptions(String sourceLang, String targetLang) {
        TranslateOptions options = new TranslateOptions()
                .addTargetLanguage(normalizeLang(targetLang));
        if (!isAutoDetect(sourceLang)) {
            options.setSourceLanguage(normalizeLang(sourceLang));
        }
        return options;
    }

    private String firstTranslatedText(TranslatedTextItem translation) {
        if (translation == null || translation.getTranslations() == null || translation.getTranslations().isEmpty()) {
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure translation result is empty");
        }
        TranslationText translationText = translation.getTranslations().get(0);
        if (translationText == null || translationText.getText() == null) {
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure translation text is empty");
        }
        return translationText.getText();
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
