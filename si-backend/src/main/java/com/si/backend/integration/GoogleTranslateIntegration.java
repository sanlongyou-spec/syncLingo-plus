package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.GoogleTranslateProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Google Cloud Translation API v2 集成层。
 *
 * <p>参考官方文档：
 * <ul>
 *   <li>translate: POST https://translation.googleapis.com/language/translate/v2</li>
 *   <li>detect: POST https://translation.googleapis.com/language/translate/v2/detect</li>
 * </ul>
 *
 * <p>Authentication: API Key（v2 Basic 支持 API Key；v3 Advanced 需 Service Account，当前项目使用 v2）。
 */
@Slf4j
@Component
public class GoogleTranslateIntegration {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final GoogleTranslateProperties properties;

    public GoogleTranslateIntegration(
            OkHttpClient httpClient,
            GoogleTranslateProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * 检测文本语种。
     *
     * @param text 待检测文本
     * @return BCP-47 语种代码（如 "zh"、"id" 等）
     */
    public String detectLanguage(String text) {
        log.info("[GoogleTranslateIntegration] detectLanguage start, textLen={}",
                text != null ? text.length() : 0);

        if (text == null || text.isBlank()) {
            return Constants.LANG_UNDEFINED;
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("q", text);

            RequestBody body = RequestBody.create(
                    OBJECT_MAPPER.writeValueAsString(requestBody),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(Constants.TRANSLATION_DETECT_ENDPOINT + "?key=" + properties.getApiKey())
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                long cost = System.currentTimeMillis() - start;

                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected response: " + response);
                }

                String responseBody = response.body().string();
                JsonNode root = OBJECT_MAPPER.readTree(responseBody);
                JsonNode detections = root.path("data").path("detections");

                if (detections.isMissingNode() || !detections.isArray()
                        || detections.isEmpty()) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google 语种检测结果为空");
                }

                String detectedLang = detections.get(0).path("language").asText();
                log.info("[GoogleTranslateIntegration] detectLanguage end, textLen={}, "
                                + "detectedLang={}, costMs={}",
                        text.length(), detectedLang, cost);
                return detectedLang;
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GoogleTranslateIntegration] detectLanguage error, textLen={}",
                    text != null ? text.length() : 0, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR,
                    "语种检测服务异常: " + e.getMessage());
        }
    }

    /**
     * 翻译单条文本。
     *
     * @param text       原文
     * @param sourceLang 源语言（传 "auto" 启用自动检测）
     * @param targetLang 目标语言
     * @return 译文
     */
    public String translate(String text, String sourceLang, String targetLang) {
        log.info("[GoogleTranslateIntegration] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);

        if (text == null || text.isBlank()) {
            return "";
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("q", text);
            requestBody.put("target", normalizeLang(targetLang));
            requestBody.put("format", "text");
            if (!isAutoDetect(sourceLang)) {
                requestBody.put("source", normalizeLang(sourceLang));
            }

            RequestBody body = RequestBody.create(
                    OBJECT_MAPPER.writeValueAsString(requestBody),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(Constants.TRANSLATION_API_ENDPOINT + "?key=" + properties.getApiKey())
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                long cost = System.currentTimeMillis() - start;

                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected response: " + response);
                }

                String responseBody = response.body().string();
                JsonNode root = OBJECT_MAPPER.readTree(responseBody);
                JsonNode translations = root.path("data").path("translations");

                if (translations.isMissingNode() || !translations.isArray()
                        || translations.isEmpty()) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Google 翻译结果为空");
                }

                String result = translations.get(0).path("translatedText").asText();
                log.info("[GoogleTranslateIntegration] translate end, textLen={}, "
                                + "sourceLang={}, targetLang={}, costMs={}, resultLen={}",
                        text.length(), sourceLang, targetLang, cost,
                        result != null ? result.length() : 0);
                return result;
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GoogleTranslateIntegration] translate error, textLen={}, source={}, target={}",
                    text != null ? text.length() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "翻译服务异常: " + e.getMessage());
        }
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
        log.info("[GoogleTranslateIntegration] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);

        if (texts == null || texts.isEmpty()) {
            log.info("[GoogleTranslateIntegration] translateBatch end, texts is empty, returning empty list");
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("q", texts);
            requestBody.put("target", normalizeLang(targetLang));
            requestBody.put("format", "text");
            if (!isAutoDetect(sourceLang)) {
                requestBody.put("source", normalizeLang(sourceLang));
            }

            RequestBody body = RequestBody.create(
                    OBJECT_MAPPER.writeValueAsString(requestBody),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(Constants.TRANSLATION_API_ENDPOINT + "?key=" + properties.getApiKey())
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                long cost = System.currentTimeMillis() - start;

                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected response: " + response);
                }

                String responseBody = response.body().string();
                JsonNode root = OBJECT_MAPPER.readTree(responseBody);
                JsonNode translations = root.path("data").path("translations");

                List<String> results = StreamSupport.stream(translations.spliterator(), false)
                        .map(t -> t.path("translatedText").asText())
                        .collect(Collectors.toList());

                log.info("[GoogleTranslateIntegration] translateBatch end, count={}, "
                                + "sourceLang={}, targetLang={}, costMs={}",
                        results.size(), sourceLang, targetLang, cost);
                return results;
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GoogleTranslateIntegration] translateBatch error, count={}, source={}, target={}",
                    texts != null ? texts.size() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "批量翻译异常: " + e.getMessage());
        }
    }

    private boolean isAutoDetect(String sourceLang) {
        return sourceLang == null || sourceLang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(sourceLang);
    }

    /**
     * 将内部语种代码归一化为 Google Translation v2 API 接受的 BCP-47 格式。
     */
    private String normalizeLang(String lang) {
        if (lang == null) return Constants.LANG_ZH_CN;
        return switch (lang.toLowerCase()) {
            case "zh-cn", "zh-hans" -> Constants.LANG_ZH_CN;
            case "id", "id-id", "in" -> Constants.LANG_ID_SHORT;
            default -> lang;
        };
    }
}
