package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.AzureTranslatorProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Azure Translator API v3 集成层，使用 OkHttp REST 调用。
 *
 * <p>参考官方文档（REST API）：
 * <ul>
 *   <li>Endpoint: POST {Endpoint}/translate?api-version=3.0</li>
 *   <li>Headers: Ocp-Apim-Subscription-Key, Content-Type: application/json, Ocp-Apim-Subscription-Region</li>
 *   <li>Body: JSON 数组 [{"Text": "hello"}]</li>
 *   <li>Auto-detect: 不传 from 参数，API 自动检测并返回 detectedLanguage</li>
 * </ul>
 *
 * <p>说明：官方 Azure Translator Java SDK（azure-ai-translation-text）需单独引入，
 * 当前使用 OkHttp REST 与官方 API 直接对接，行为与 SDK 等价。
 *
 * @see <a href="https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/v3/reference">Azure Translator v3 REST API</a>
 */
@Slf4j
@Component
public class AzureTranslatorIntegration {

    private static final String TRANSLATE_API_PATH = "/translate?api-version=3.0";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AzureTranslatorProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public AzureTranslatorIntegration(
            AzureTranslatorProperties properties,
            ObjectMapper objectMapper,
            OkHttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 翻译单条文本。
     *
     * @param text       原文
     * @param sourceLang 源语言（传 null 或 "auto" 启用自动检测）
     * @param targetLang 目标语言
     * @return 译文
     */
    public String translate(String text, String sourceLang, String targetLang) {
        log.info("[AzureTranslatorIntegration] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);

        if (text == null || text.isBlank()) {
            return "";
        }

        long start = System.currentTimeMillis();
        try {
            String endpoint = buildEndpoint(targetLang, sourceLang);
            String jsonBody = objectMapper.writeValueAsString(List.of(new TextItem(text)));
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Content-Type", "application/json");

            addAuthHeaders(requestBuilder);

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                long cost = System.currentTimeMillis() - start;

                if (!response.isSuccessful()) {
                    throw new java.io.IOException("Unexpected response: " + response);
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);

                if (!root.isArray() || root.isEmpty()) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure 翻译结果为空");
                }

                JsonNode translations = root.get(0).path("translations");
                if (translations.isEmpty()) {
                    throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure 翻译结果为空");
                }

                String result = translations.get(0).path("text").asText();
                log.info("[AzureTranslatorIntegration] translate end, textLen={}, targetLang={}, costMs={}, resultLen={}",
                        text.length(), targetLang, cost, result != null ? result.length() : 0);
                return result;
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AzureTranslatorIntegration] translate error, textLen={}, source={}, target={}",
                    text != null ? text.length() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure 翻译服务异常: " + e.getMessage());
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
        log.info("[AzureTranslatorIntegration] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);

        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            String endpoint = buildEndpoint(targetLang, sourceLang);
            List<TextItem> items = texts.stream().map(TextItem::new).collect(Collectors.toList());
            String jsonBody = objectMapper.writeValueAsString(items);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Content-Type", "application/json");

            addAuthHeaders(requestBuilder);

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                long cost = System.currentTimeMillis() - start;

                if (!response.isSuccessful()) {
                    throw new java.io.IOException("Unexpected response: " + response);
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);

                List<String> results = StreamSupport.stream(root.spliterator(), false)
                        .map(item -> item.path("translations").get(0).path("text").asText())
                        .collect(Collectors.toList());

                log.info("[AzureTranslatorIntegration] translateBatch end, count={}, targetLang={}, costMs={}",
                        results.size(), targetLang, cost);
                return results;
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AzureTranslatorIntegration] translateBatch error, count={}, source={}, target={}",
                    texts != null ? texts.size() : 0, sourceLang, targetLang, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "Azure 批量翻译异常: " + e.getMessage());
        }
    }

    private String buildEndpoint(String targetLang, String sourceLang) {
        StringBuilder url = new StringBuilder();
        url.append(properties.getEndpoint());
        url.append(TRANSLATE_API_PATH);
        url.append("&to=").append(targetLang);
        if (!isAutoDetect(sourceLang)) {
            url.append("&from=").append(sourceLang);
        }
        return url.toString();
    }

    private void addAuthHeaders(Request.Builder builder) {
        if (properties.getKey() != null && !properties.getKey().isBlank()) {
            builder.header("Ocp-Apim-Subscription-Key", properties.getKey());
        }
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.header("Ocp-Apim-Subscription-Region", properties.getRegion());
        }
    }

    private boolean isAutoDetect(String sourceLang) {
        return sourceLang == null || sourceLang.isBlank() || "auto".equalsIgnoreCase(sourceLang);
    }

    /**
     * Azure Translator v3 API 请求体 JSON 对象。
     */
    private record TextItem(String Text) {}
}
