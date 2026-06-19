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

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 句边界检测服务客户端：调用 speaker-service 的 /segment-boundary 端点，
 * 使用 wtpsplit SaT 模型为印尼语等语言检测句子边界，供分段逻辑使用。
 *
 * <p>设计原则：
 * <ul>
 *   <li>超时 100ms（默认），超时返回 -1 → 调用方降级走词边界/字符数逻辑。</li>
 *   <li>不重试：重试会超出延迟预算。</li>
 *   <li>enabled=false 时调用直接返回 -1，零开销。</li>
 * </ul>
 */
@Slf4j
@Component
public class SegmentationServiceIntegration {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String url;
    private final int timeoutMs;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public SegmentationServiceIntegration(
            org.springframework.core.env.Environment env,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.enabled = Boolean.parseBoolean(
                env.getProperty("segmentation.service.enabled", "false"));
        this.url = env.getProperty("segmentation.service.url", "http://localhost:7000")
                + "/segment-boundary";
        this.timeoutMs = Integer.parseInt(
                env.getProperty("segmentation.service.timeout-ms", "100"));
        // 分句调用频繁，复用 keep-alive 连接易命中被 speaker(uvicorn 默认 5s keep-alive)
        // 关闭的陈旧连接而报 "unexpected end of stream"，导致分句失败、文本被切成碎片、
        // TTS 变成断续小片段。把空闲连接保活压到 1s 并开启失败自动重试以消除该问题。
        this.client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(timeoutMs + 10L, TimeUnit.MILLISECONDS)
                .writeTimeout(200, TimeUnit.MILLISECONDS)
                .connectionPool(new okhttp3.ConnectionPool(4, 1, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .build();
        log.info("[SegmentationService] enabled={}, url={}, timeoutMs={}", enabled, url, timeoutMs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 在 safeEnd 字符范围内查找第一个句子边界位置。
     * 返回 -1 表示未找到或服务不可用，调用方应降级。
     *
     * @param text    待检测文本（来自 Azure ASR 稳定前缀）
     * @param lang    语言代码，如 "id"
     * @param safeEnd 最大字符位置（含），只在此范围内查找边界
     * @return 边界字符位置，或 -1
     */
    public int findBoundary(String text, String lang, int safeEnd) {
        if (!enabled || text == null || text.isBlank() || safeEnd <= 0) return -1;

        long t0 = System.currentTimeMillis();
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("text", text, "lang", lang, "safe_end", safeEnd));
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                long cost = System.currentTimeMillis() - t0;
                if (!response.isSuccessful()) {
                    log.warn("[SegmentationService] HTTP {}, cost={}ms", response.code(), cost);
                    return -1;
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode node = objectMapper.readTree(responseBody);
                boolean modelAvailable = node.path("model_available").asBoolean(false);
                if (!modelAvailable) {
                    log.debug("[SegmentationService] model not available");
                    return -1;
                }
                int boundary = node.path("boundary").asInt(-1);
                double svcCostMs = node.path("latency_ms").asDouble(0);
                log.debug("[SegmentationService] ok cost={}ms svc={}ms lang={} safeEnd={} boundary={}",
                        cost, String.format("%.1f", svcCostMs), lang, safeEnd, boundary);
                return boundary;
            }
        } catch (java.io.InterruptedIOException e) {
            long cost = System.currentTimeMillis() - t0;
            log.warn("[SegmentationService] timeout after {}ms (budget={}ms)", cost, timeoutMs);
            return -1;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            log.warn("[SegmentationService] error after {}ms: {}", cost, e.getMessage());
            return -1;
        }
    }
}
