package com.si.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LLM 集成层，用于调用 Qwen3-Max 进行印尼语翻译压缩。
 */
@Slf4j
@Component
public class LlmIntegration {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 印尼语压缩 prompt */
    private static final String COMPRESSION_SYSTEM_PROMPT_ID =
            "You are a simultaneous interpretation compression model.\n\n"
            + "Task: Compress the already-translated Indonesian text into a concise real-time interpretation version.\n\n"
            + "【Input Rules】\n"
            + "- The input text is already in Indonesian. Do NOT translate again.\n"
            + "- Preserve all proper nouns unchanged.\n\n"
            + "【Strict Constraints (Must Follow)】\n"
            + "1. Do NOT rephrase or rewrite the original meaning.\n"
            + "2. Do NOT add any information not present in the input.\n"
            + "3. Do NOT add explanations, summaries, or conclusions.\n"
            + "4. Do NOT add emotional or dramatic expressions.\n"
            + "5. The following words/phrases are strictly forbidden:\n"
            + "   - \"artinya\" (meaning)\n"
            + "   - \"intinya\" (in summary)\n"
            + "   - \"berarti\" (means)\n"
            + "   - \"ini menunjukkan\" (this shows)\n"
            + "   - \"jadi\" (so / therefore)\n"
            + "6. Do not output any podcast promotion, sponsorship, or call-to-action text.\n"
            + "   Specifically forbidden: \"dukung podcast\", \"jadilah sponsor\", \"jadi patron\",\n"
            + "   \"menjadi sponsor/patron\", \"di Patreon\", \"support this podcast\", \"don't forget to support\",\n"
            + "   \"Sampai jumpa\", \"Daaah\", \"subscribe\", \"rating di Spotify\".\n"
            + "   These phrases must be DELETED, never kept.\n"
            + "7. Do not change tone or expression style.\n\n"
            + "【Compression Rules】\n"
            + "7. Only deletion is allowed for:\n"
            + "   - Fillers\n"
            + "   - Repetition\n"
            + "   - Weak modifiers\n"
            + "   - Podcast/content promotion, sponsorship, and call-to-action sentences\n"
            + "     (e.g. \"dukung podcast ini dengan menjadi\", \"jangan lupa\",\n"
            + "      \"Sampai jumpa di episode berikutnya\", subscription/promotion reminders)\n"
            + "8. Preserve all facts, actions, and results.\n"
            + "9. Keep the original order.\n\n"
            + "【Length Control】\n"
            + "10. Target: 60%~70% of the original length.\n"
            + "11. Do NOT delete key actions or events.\n\n"
            + "【Style (Strict)】\n"
            + "12. Use very simple sentence structures.\n"
            + "13. One sentence, one action.\n"
            + "14. Recommended: ≤12 words per sentence.\n"
            + "15. Avoid descriptive or literary expressions.\n\n"
            + "【Output Requirements】\n"
            + "- Output must be objective, direct, and neutral.\n"
            + "- Do not use narrative style.\n"
            + "- Do not polish or enhance expressions.\n\n"
            + "Output only the compressed text, no explanation.";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmIntegration(ObjectMapper objectMapper) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 LLM 进行文本压缩（印尼语）。
     *
     * @param text    待压缩的印尼语文本
     * @param apiKey  DashScope API Key
     * @param baseUrl API Base URL
     * @param model   模型名称（如 qwen3-max）
     * @return 压缩后的文本
     */
    public String compress(String text, String apiKey, String baseUrl, String model) throws IOException {
        String endpoint = buildEndpoint(baseUrl, "chat/completions");
        log.info("[LlmIntegration] compress start, model={}, textLen={}", model, text.length());

        var root = objectMapper.createObjectNode();
        root.put("model", model);
        root.putPOJO("messages", List.of(
                objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", COMPRESSION_SYSTEM_PROMPT_ID),
                objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", text)
        ));

        String result = doCall(endpoint, apiKey, root);
        log.info("[LlmIntegration] compress end, originalLen={}, compressedLen={}", text.length(), result.length());
        return result;
    }

    private String buildEndpoint(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl + path;
        }
        return baseUrl + "/" + path;
    }

    private String doCall(String endpoint, String apiKey, com.fasterxml.jackson.databind.node.ObjectNode body) throws IOException {
        String requestBody = objectMapper.writeValueAsString(body);
        log.debug("[LlmIntegration] POST {}, bodyLen={}", endpoint, requestBody.length());

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("[LlmIntegration] HTTP {} error: {}", response.code(), responseBody);
                throw new IOException("LLM call failed with HTTP " + response.code());
            }

            return parseResponse(responseBody);
        }
    }

    private String parseResponse(String body) throws IOException {
        var root = objectMapper.readTree(body);

        if (root.has("error")) {
            throw new IOException("LLM API error: " + root.get("error").toString());
        }

        if (!root.has("choices") || root.get("choices").isEmpty()) {
            throw new IOException("No choices in LLM response");
        }

        var content = root.path("choices").get(0).path("message").path("content");
        if (content.isMissingNode()) {
            throw new IOException("Missing content field in LLM response");
        }

        return content.asText();
    }
}
