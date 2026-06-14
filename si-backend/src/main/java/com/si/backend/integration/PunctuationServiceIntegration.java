package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 标点还原服务客户端：调用 speaker-service 的 /punctuate 端点，
 * 为 Azure zh-CN ASR（无标点输出）恢复句末/子句标点，供分段逻辑使用。
 *
 * <p>设计原则：
 * <ul>
 *   <li>超时 45ms（默认），超时直接返回 null → 调用方降级走原字符数逻辑。</li>
 *   <li>不重试：重试会超出延迟预算。</li>
 *   <li>专用 OkHttpClient：不与共享客户端共用，避免共享客户端的重试拦截器干扰。</li>
 *   <li>enabled=false 时调用直接返回 null，零开销。</li>
 * </ul>
 */
@Slf4j
@Component
public class PunctuationServiceIntegration {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String url;
    private final int timeoutMs;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public PunctuationServiceIntegration(
            org.springframework.core.env.Environment env,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.enabled = Boolean.parseBoolean(
                env.getProperty("punctuation.service.enabled", "false"));
        this.url = env.getProperty("punctuation.service.url", "http://localhost:7000")
                + "/punctuate";
        this.timeoutMs = Integer.parseInt(
                env.getProperty("punctuation.service.timeout-ms", "45"));
        this.client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(timeoutMs + 10L, TimeUnit.MILLISECONDS) // 比 timeout 多 10ms 余量
                .writeTimeout(200, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)  // 对 keep-alive 连接重置自动重试一次
                .build();
        log.info("[PunctuationService] enabled={}, url={}, timeoutMs={}", enabled, url, timeoutMs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 为 text 添加标点。返回 null 表示调用失败/超时/未启用，调用方应降级。
     *
     * @param text 未加标点的中文 ASR 文本（来自 Azure 稳定前缀）
     * @return 加了标点的文本，或 null
     */
    public String punctuate(String text) {
        if (!enabled || text == null || text.isBlank()) return null;

        long t0 = System.currentTimeMillis();
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("text", text));
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                long cost = System.currentTimeMillis() - t0;
                if (!response.isSuccessful()) {
                    log.warn("[PunctuationService] HTTP {}, cost={}ms, text='{}'",
                            response.code(), cost, abbrev(text));
                    return null;
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode node = objectMapper.readTree(responseBody);
                String punctuated = node.path("punctuated").asText(null);
                boolean modelAvailable = node.path("model_available").asBoolean(false);
                double serviceCostMs = node.path("latency_ms").asDouble(0);

                log.debug("[PunctuationService] ok cost={}ms svc={}ms model={} in='{}' out='{}'",
                        cost, String.format("%.1f", serviceCostMs), modelAvailable,
                        abbrev(text), abbrev(punctuated));

                if (!modelAvailable) {
                    // 服务运行但模型未加载，原文返回→等同未启用
                    log.debug("[PunctuationService] model not available, skipping");
                    return null;
                }
                if (punctuated == null || punctuated.isBlank()) return null;
                return punctuated;
            }
        } catch (java.io.InterruptedIOException e) {
            long cost = System.currentTimeMillis() - t0;
            log.warn("[PunctuationService] timeout after {}ms (budget={}ms), text='{}'",
                    cost, timeoutMs, abbrev(text));
            return null;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            log.warn("[PunctuationService] error after {}ms: {}, text='{}'",
                    cost, e.getMessage(), abbrev(text));
            return null;
        }
    }

    private static String abbrev(String s) {
        if (s == null) return null;
        return s.length() <= 30 ? s : s.substring(0, 27) + "...";
    }
}
