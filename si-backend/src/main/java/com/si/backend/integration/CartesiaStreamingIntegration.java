package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.CartesiaProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTS 业务服务，管理 Cartesia WebSocket 连接池，按音色 ID 隔离连接池。
 * 提供流式合成接口，内部自动从池中借出/归还连接。
 */
@Slf4j
@Component
public class CartesiaStreamingIntegration {

    /** 全局共用连接池的 key（音色每句指定，连接不绑音色，所以只需一个池）。 */
    private static final String SHARED_POOL_KEY = "__cartesia_shared__";

    private final CartesiaProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, GenericObjectPool<CartesiaWsClient>> voicePools = new ConcurrentHashMap<>();

    public CartesiaStreamingIntegration(CartesiaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时异步预热默认音色连接池，避免阻塞应用启动流程。
     */
    /**
     * 流式合成语音，通过连接池管理 Cartesia WebSocket 连接。
     *
     * @param voiceId    音色 ID（也是连接池 key）
     * @param text       待合成文本
     * @param sampleRate 采样率
     * @param speed      语速倍率（1.0 为正常，1.25 为加速 25%）
     * @param onChunk    PCM 分块回调
     * @param onComplete 合成完成回调
     * @param onError    错误回调
     */
    public void synthesizeStream(
            String voiceId,
            String text,
            int sampleRate,
            double speed,
            String language,
            java.util.function.Consumer<byte[]> onChunk,
            Runnable onComplete,
            java.util.function.Consumer<String> onError
    ) {
        log.info("[CartesiaStreamingIntegration] synthesizeStream start, voiceId={}, textLen={}, sampleRate={}, speed={}, language={}",
                voiceId, text != null ? text.length() : 0, sampleRate, speed, language);

        GenericObjectPool<CartesiaWsClient> pool = getOrCreatePool();

        CartesiaWsClient borrowedClient = null;
        try {
            borrowedClient = pool.borrowObject();
            log.debug("[CartesiaStreamingIntegration] borrowed client, voiceId={}, active={}, idle={}",
                    voiceId, pool.getNumActive(), pool.getNumIdle());

            borrowedClient.setVoiceId(voiceId);

            // Use a holder array to allow lambda to reference the client
            // while still being able to check for null in catch block
            final CartesiaWsClient[] clientHolder = new CartesiaWsClient[] { borrowedClient };

            // streamSynthesize 是异步的（newWebSocket 非阻塞，立即返回）。
            // 必须在 onComplete / onError 里归还 client，不能在 finally 里立即归还。
            clientHolder[0].streamSynthesize(
                    text,
                    sampleRate,
                    speed,
                    language,
                    onChunk,
                    () -> {
                        returnClient(pool, clientHolder[0], voiceId);
                        onComplete.run();
                    },
                    err -> {
                        invalidateClient(pool, clientHolder[0], voiceId, err);
                        clientHolder[0] = null;
                        onError.accept(err);
                    }
            );

        } catch (Exception e) {
            log.error("[CartesiaStreamingIntegration] synthesizeStream borrow error, voiceId={}", voiceId, e);
            if (borrowedClient != null) {
                try {
                    pool.invalidateObject(borrowedClient);
                } catch (Exception invalidEx) {
                    log.warn("[CartesiaStreamingIntegration] invalidateObject error, voiceId={}", voiceId, invalidEx);
                }
            }
            onError.accept("TTS 合成失败: " + e.getMessage());
        }
        // 注意：不在 finally 归还 client。client 在 onComplete / onError 回调里归还。
    }

    private void returnClient(GenericObjectPool<CartesiaWsClient> pool, CartesiaWsClient client, String voiceId) {
        if (client == null) return;
        try {
            pool.returnObject(client);
            log.debug("[CartesiaStreamingIntegration] returned client, voiceId={}, active={}, idle={}",
                    voiceId, pool.getNumActive(), pool.getNumIdle());
        } catch (Exception returnEx) {
            log.warn("[CartesiaStreamingIntegration] returnObject error, voiceId={}", voiceId, returnEx);
        }
    }

    private void invalidateClient(GenericObjectPool<CartesiaWsClient> pool, CartesiaWsClient client, String voiceId, String reason) {
        if (client == null) return;
        try {
            pool.invalidateObject(client);
            log.warn("[CartesiaStreamingIntegration] invalidated failed client, voiceId={}, reason={}, active={}, idle={}",
                    voiceId, reason, pool.getNumActive(), pool.getNumIdle());
        } catch (Exception invalidEx) {
            log.warn("[CartesiaStreamingIntegration] invalidateObject error, voiceId={}, reason={}",
                    voiceId, reason, invalidEx);
        }
    }

    /**
     * 预热指定音色的连接池。
     *
     * @param voiceId 音色 ID
     * @param count   预热连接数
     */
    public void prewarmPool(String voiceId, int count) {
        log.info("[CartesiaStreamingIntegration] prewarmPool start, voiceId={}, count={}", voiceId, count);
        GenericObjectPool<CartesiaWsClient> pool = getOrCreatePool();
        int warmed = 0;
        for (int i = 0; i < count; i++) {
            CartesiaWsClient client = null;
            try {
                client = pool.borrowObject();
                pool.returnObject(client);
                warmed++;
            } catch (Exception e) {
                log.warn("[CartesiaStreamingIntegration] prewarmPool failed at index={}, voiceId={}", i, voiceId, e);
                break;
            }
        }
        log.info("[CartesiaStreamingIntegration] prewarmPool end, voiceId={}, warmed={}, active={}, idle={}",
                voiceId, warmed, pool.getNumActive(), pool.getNumIdle());
    }

    /**
     * 获取/创建<b>全局共用</b>的连接池。
     * <p>音色不绑定连接：每次合成时按 {@link CartesiaWsClient#setVoiceId} 即时指定，请求体里带 voice。
     * 所以无需按音色分池——单个共用池的上限就是<b>全局 TTS 并发上限</b>，正好贴合 Cartesia 账户额度，
     * 避免"音色越多、连接越多"撑爆并发。
     */
    private GenericObjectPool<CartesiaWsClient> getOrCreatePool() {
        return voicePools.computeIfAbsent(SHARED_POOL_KEY, id -> {
            GenericObjectPoolConfig<CartesiaWsClient> config = new GenericObjectPoolConfig<>();
            config.setMaxTotal(properties.getPool().getMaxTotalPerVoice());   // 现为全局并发上限
            config.setMinIdle(properties.getPool().getMinIdlePerVoice());
            config.setMaxWait(java.time.Duration.ofMillis(properties.getPool().getMaxWaitMillis()));

            CartesiaPooledObjectFactory factory = new CartesiaPooledObjectFactory(properties, objectMapper);
            GenericObjectPool<CartesiaWsClient> pool = new GenericObjectPool<>(factory, config);

            log.info("[CartesiaStreamingIntegration] shared TTS pool created, maxTotal={}, minIdle={}",
                    config.getMaxTotal(), config.getMinIdle());
            return pool;
        });
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 连接池对象工厂，每个 voiceId 一个池，工厂持有 voiceId。
     */
    private static class CartesiaPooledObjectFactory extends BasePooledObjectFactory<CartesiaWsClient> {

        private final CartesiaProperties properties;
        private final ObjectMapper objectMapper;

        CartesiaPooledObjectFactory(CartesiaProperties properties, ObjectMapper objectMapper) {
            this.properties = properties;
            this.objectMapper = objectMapper;
        }

        @Override
        public CartesiaWsClient create() {
            return new CartesiaWsClient(properties, objectMapper);
        }

        @Override
        public PooledObject<CartesiaWsClient> wrap(CartesiaWsClient client) {
            return new org.apache.commons.pool2.impl.DefaultPooledObject<>(client);
        }

        @Override
        public boolean validateObject(PooledObject<CartesiaWsClient> p) {
            // 客户端对象本身始终有效，WebSocket 连接由 streamSynthesize 内部按需建立
            return p.getObject() != null;
        }

        @Override
        public void destroyObject(PooledObject<CartesiaWsClient> p) {
            p.getObject().close();
        }
    }

    // ─────────────────────────────────────────────────────────

    /**
     * Cartesia WebSocket 客户端，可被连接池复用。
     * 每次借出后通过 {@link #setVoiceId(String)} 设置当前音色 ID。
     */
    public static class CartesiaWsClient {

        private static final int WS_WRITE_TIMEOUT_SECONDS = 30;
        private static final int WS_PING_INTERVAL_SECONDS = 20;
        /**
         * 空闲超过该毫秒数的连接不再复用，强制重建。取值小于 ping 间隔的 1 个周期，
         * 这样在 okhttp ping 还来不及发现“静默死连接”之前，我们已主动弃用它，
         * 避免把 TTS 请求 send 进一个表面 open 实际已死的 socket（send 会成功入队但永无响应）。
         */
        private static final long WS_IDLE_STALE_MS = 15_000L;
        /** 单次合成的空闲看门狗：该时间内无连接活动才判定失效；持续收音频时不会硬切长句。 */
        private static final int WS_GENERATION_IDLE_TIMEOUT_SECONDS = 30;

        /** 所有 CartesiaWsClient 共享同一 OkHttpClient，复用连接池，避免每次合成重建 TCP 连接 */
        private static final okhttp3.OkHttpClient WS_HTTP_CLIENT = new okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(WS_WRITE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .pingInterval(WS_PING_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        /** 合成看门狗调度器（守护线程），全 client 共享 */
        private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
                java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "cartesia-watchdog");
                    t.setDaemon(true);
                    return t;
                });

        private final CartesiaProperties properties;
        private final ObjectMapper objectMapper;
        private volatile String voiceId;
        private okhttp3.WebSocket webSocket;
        private volatile boolean open = false;

        // 当前在途生成的回调与待发负载（连接池保证一个 client 同时只服务一次合成，故单组即可）。
        private volatile java.util.function.Consumer<byte[]> curOnChunk;
        private volatile Runnable curOnComplete;
        private volatile java.util.function.Consumer<String> curOnError;
        private volatile String pendingPayload;
        private volatile boolean generationActive = false;
        /** 最近一次连接活动（onOpen / 收到音频块 / DONE）时间，用于判断空闲死连接 */
        private volatile long lastActivityMs = 0L;
        /** 当前在途合成的 Cartesia context_id，用于忽略复用连接上的迟到帧。 */
        private volatile String currentContextId;
        /** 当前合成的看门狗任务句柄，DONE/ERROR 时取消 */
        private volatile java.util.concurrent.ScheduledFuture<?> watchdogTask;
        /**
         * 物理连接代号，每次 openAndSend 新建连接自增。监听器捕获自己的代号，回调时与当前代号比对：
         * 旧连接被关闭后其 onClosed/onFailure 不再误伤新一代合成（修复"重连回调把刚开始的句子判失败"）。
         */
        private volatile long connEpoch = 0L;

        // 当前合成的延迟分析字段
        private volatile long synthStartMs;
        private volatile int chunkCount;
        private volatile long totalPcmBytes;

        public CartesiaWsClient(CartesiaProperties properties, ObjectMapper objectMapper) {
            this.properties = properties;
            this.objectMapper = objectMapper;
        }

        public void setVoiceId(String voiceId) {
            this.voiceId = voiceId;
        }

        /**
         * 流式合成。<b>复用长连接</b>（Cartesia 官方推荐：一条连接用多个 context 连续多次生成）：
         * 连接已开就直接在老连接上发新请求（新 context_id）；否则新建连接并在 onOpen 时发送。
         * 合成完成<b>不关连接</b>，留给下次复用，省掉每句的 TCP/TLS/WS 握手延迟。
         */
        public void streamSynthesize(
                String text,
                int sampleRate,
                double speed,
                String language,
                java.util.function.Consumer<byte[]> onChunk,
                Runnable onComplete,
                java.util.function.Consumer<String> onError
        ) {
            this.curOnChunk = onChunk;
            this.curOnComplete = onComplete;
            this.curOnError = onError;
            this.generationActive = true;
            this.synthStartMs = System.currentTimeMillis();
            this.chunkCount = 0;
            this.totalPcmBytes = 0;

            String currentVoiceId = (voiceId != null && !voiceId.isBlank()) ? voiceId : Constants.VOICE_ID_DEFAULT;
            String contextId = java.util.UUID.randomUUID().toString();
            this.currentContextId = contextId;
            String payload = toJson(buildTtsRequest(text, sampleRate, speed, language, currentVoiceId, contextId));

            // 健康检查：空闲过久的连接可能已静默死亡（send 仍会成功入队但永无响应），强制重建
            boolean stale = lastActivityMs > 0L && (System.currentTimeMillis() - lastActivityMs) > WS_IDLE_STALE_MS;
            markGenerationActivity();
            okhttp3.WebSocket ws = this.webSocket;
            boolean reused = !stale && open && ws != null && safeSend(ws, payload);
            if (stale && open) {
                log.debug("[CartesiaWsClient] idle connection stale (>{}ms), forcing reconnect, voiceId={}",
                        WS_IDLE_STALE_MS, voiceId);
            }
            if (!reused) {
                openAndSend(payload);
            }
        }

        /** 记录合成活动并重置空闲看门狗，避免长句在持续出块时被固定总时长截断。 */
        private void markGenerationActivity() {
            lastActivityMs = System.currentTimeMillis();
            scheduleIdleWatchdog();
        }

        /** 启动单次合成空闲看门狗：长时间无任何连接活动才判定连接失效。 */
        private synchronized void scheduleIdleWatchdog() {
            if (!generationActive) return;
            cancelWatchdog();
            watchdogTask = WATCHDOG.schedule(() -> {
                if (!generationActive) return;
                long idleMs = System.currentTimeMillis() - lastActivityMs;
                log.error("[CartesiaWsClient] generation idle watchdog timeout after {}s, idleMs={}, invalidating connection, voiceId={}, contextId={}",
                        WS_GENERATION_IDLE_TIMEOUT_SECONDS, idleMs, voiceId, currentContextId);
                okhttp3.WebSocket dead;
                synchronized (this) {
                    open = false;
                    dead = this.webSocket;
                    this.webSocket = null;
                }
                if (dead != null) {
                    try { dead.cancel(); } catch (Exception ignored) { /* 忽略取消异常 */ }
                }
                completeOnce(false, "TTS 合成空闲超时");
            }, WS_GENERATION_IDLE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        }

        private synchronized void cancelWatchdog() {
            java.util.concurrent.ScheduledFuture<?> t = watchdogTask;
            if (t != null) {
                t.cancel(false);
                watchdogTask = null;
            }
        }

        private boolean safeSend(okhttp3.WebSocket ws, String payload) {
            try {
                return ws.send(payload);
            } catch (Exception e) {
                return false;
            }
        }

        /** 新建（或重建）连接，onOpen 后发送待发负载。 */
        private void openAndSend(String payload) {
            final long myEpoch;
            okhttp3.WebSocket oldWebSocket;
            synchronized (this) {
                myEpoch = ++connEpoch;       // 本次新连接的代号；旧连接代号已过期
                oldWebSocket = this.webSocket;
                this.webSocket = null;
                this.open = false;
            }
            if (oldWebSocket != null) {
                try {
                    // 旧连接的 onClosed 会带 reason="reuse connection" 异步回来，但其 myEpoch 已过期，
                    // 经下方 epoch 校验会被忽略，不会误把新一代合成判为失败。
                    oldWebSocket.close(Constants.CARTESIA_CLOSE_NORMAL, Constants.CARTESIA_CLOSE_REASON_REUSE);
                } catch (Exception ignored) {
                    // 忽略旧连接关闭异常
                }
            }
            this.pendingPayload = payload;

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(properties.getApiUrl() + "/tts/websocket")
                    .addHeader("Authorization", "Bearer " + properties.getApiKey())
                    .addHeader("Cartesia-Version", Constants.CARTESIA_VERSION_HEADER)
                    .build();

            okhttp3.WebSocket newWebSocket = WS_HTTP_CLIENT.newWebSocket(request, new okhttp3.WebSocketListener() {
                @Override
                public void onOpen(okhttp3.WebSocket ws, okhttp3.Response response) {
                    if (myEpoch != connEpoch) { ws.cancel(); return; }   // 已被更新的连接取代
                    open = true;
                    markGenerationActivity();
                    String p = pendingPayload;
                    pendingPayload = null;
                    if (p != null) {
                        ws.send(p);
                    }
                }

                @Override
                public void onMessage(okhttp3.WebSocket ws, String msg) {
                    if (myEpoch != connEpoch) return;   // 过期连接的迟到消息，忽略
                    handleTextMessage(ws, msg);
                }

                // 二进制帧：Cartesia 部分版本直接发原始 PCM 而非 base64 JSON
                @Override
                public void onMessage(okhttp3.WebSocket ws, okio.ByteString bytes) {
                    if (myEpoch != connEpoch) return;   // 过期连接的迟到音频，忽略
                    byte[] pcm = bytes.toByteArray();
                    markGenerationActivity();
                    int idx = ++chunkCount;
                    totalPcmBytes += pcm.length;
                    if (idx == 1) {
                        log.debug("[CartesiaWsClient] first-chunk(bin) voiceId={} firstChunkMs={} bytes={}",
                                voiceId, System.currentTimeMillis() - synthStartMs, pcm.length);
                    }
                    java.util.function.Consumer<byte[]> cb = curOnChunk;
                    if (cb != null) cb.accept(pcm);
                }

                @Override
                public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response response) {
                    if (myEpoch != connEpoch) return;   // 过期连接失败与当前合成无关，忽略
                    synchronized (CartesiaWsClient.this) {
                        open = false;
                        if (webSocket == ws) {
                            webSocket = null;
                        }
                    }
                    String errMsg = t != null ? t.getMessage() : Constants.TTS_ERROR_UNKNOWN;
                    log.error("[CartesiaWsClient] WebSocket failure, error={}", errMsg, t);
                    completeOnce(false, errMsg);
                }

                @Override
                public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                    // 关键修复：旧连接为复用而被主动关闭时 epoch 已过期，绝不能据此把新句子判失败。
                    if (myEpoch != connEpoch) return;
                    synchronized (CartesiaWsClient.this) {
                        open = false;
                        if (webSocket == ws) {
                            webSocket = null;
                        }
                    }
                    // 若当前连接在生成途中被关（如服务端超时），兜底回调，避免借出的连接永不归还。
                    completeOnce(false, "WebSocket closed: " + reason);
                }
            });
            synchronized (this) {
                this.webSocket = newWebSocket;
            }
        }

        /** 处理文本帧，分发给当前在途生成的回调。DONE 不关连接（留作复用），ERROR 才关。 */
        private void handleTextMessage(okhttp3.WebSocket ws, String msg) {
            try {
                JsonNode node = objectMapper.readTree(msg);
                if (!messageContextMatches(node, currentContextId)) {
                    log.debug("[CartesiaWsClient] ignore stale context message, currentContextId={}, messageContextId={}",
                            currentContextId, node.path(Constants.CARTESIA_FIELD_CONTEXT_ID).asText(""));
                    return;
                }
                String type = node.path(Constants.CARTESIA_FIELD_TYPE).asText();
                switch (type) {
                    case Constants.CARTESIA_MSG_TYPE_CHUNK -> {
                        String audioData = node.path(Constants.CARTESIA_FIELD_AUDIO).asText();
                        if (!audioData.isBlank()) {
                            markGenerationActivity();
                            byte[] pcm = java.util.Base64.getDecoder().decode(audioData);
                            int idx = ++chunkCount;
                            totalPcmBytes += pcm.length;
                            if (idx == 1) {
                                log.debug("[CartesiaWsClient] first-chunk voiceId={} firstChunkMs={} bytes={}",
                                        voiceId, System.currentTimeMillis() - synthStartMs, pcm.length);
                            } else {
                                log.debug("[CartesiaWsClient] chunk#{} voiceId={} bytes={} totalBytes={}",
                                        idx, voiceId, pcm.length, totalPcmBytes);
                            }
                            java.util.function.Consumer<byte[]> cb = curOnChunk;
                            if (cb != null) cb.accept(pcm);
                        }
                    }
                    case Constants.CARTESIA_MSG_TYPE_DONE -> {
                        markGenerationActivity();
                        log.debug("[CartesiaWsClient] done voiceId={} chunks={} totalBytes={} totalMs={}",
                                voiceId, chunkCount, totalPcmBytes, System.currentTimeMillis() - synthStartMs);
                        // 不关连接：保留长连接给下一句复用（Cartesia 推荐）。
                        completeOnce(true, null);
                    }
                    case Constants.CARTESIA_MSG_TYPE_FLUSH_DONE -> log.debug("[CartesiaWsClient] flush done");
                    case Constants.CARTESIA_MSG_TYPE_ERROR -> {
                        String errMsg = node.path(Constants.CARTESIA_FIELD_MESSAGE).asText(Constants.TTS_ERROR_UNKNOWN);
                        log.error("[CartesiaWsClient] TTS error: {}", errMsg);
                        synchronized (this) { open = false; this.webSocket = null; }
                        try {
                            ws.close(Constants.CARTESIA_CLOSE_SERVER_ERROR, Constants.CARTESIA_CLOSE_REASON_REUSE);
                        } catch (Exception ignored) {
                            // 忽略关闭异常
                        }
                        completeOnce(false, errMsg);
                    }
                    default -> log.warn("[CartesiaWsClient] unknown message type={}", type);
                }
            } catch (Exception e) {
                log.warn("[CartesiaWsClient] failed to parse message: {}", e.getMessage());
            }
        }

        static boolean messageContextMatches(JsonNode node, String currentContextId) {
            if (currentContextId == null || currentContextId.isBlank()) {
                return true;
            }
            String messageContextId = node.path(Constants.CARTESIA_FIELD_CONTEXT_ID).asText("");
            return messageContextId.isBlank() || currentContextId.equals(messageContextId);
        }

        /** 终态回调恰好触发一次（DONE→onComplete，其余→onError），避免重复归还或漏归还连接池连接。 */
        private void completeOnce(boolean success, String err) {
            boolean fire;
            Runnable completeCallback;
            java.util.function.Consumer<String> errorCallback;
            synchronized (this) {
                fire = generationActive;
                generationActive = false;
                completeCallback = curOnComplete;
                errorCallback = curOnError;
                curOnChunk = null;
                curOnComplete = null;
                curOnError = null;
                pendingPayload = null;
                currentContextId = null;
            }
            if (!fire) return;
            cancelWatchdog();
            if (success) {
                if (completeCallback != null) completeCallback.run();
            } else {
                if (errorCallback != null) errorCallback.accept(err);
            }
        }

        private Map<String, Object> buildTtsRequest(String text, int sampleRate, double speed, String language,
                                                    String currentVoiceId, String contextId) {
            Map<String, Object> ttsMsg = new java.util.LinkedHashMap<>();
            ttsMsg.put(Constants.CARTESIA_FIELD_TYPE, Constants.CARTESIA_MSG_TYPE_TTS_REQUEST);
            ttsMsg.put(Constants.CARTESIA_FIELD_MODEL_ID, properties.getTts().getModelId());
            // 指定目标语种, 否则多语模型对数字等"语言无关"写法会按音色默认语言(常是中文)发音
            if (language != null && !language.isBlank()) {
                ttsMsg.put("language", language);
            }
            ttsMsg.put(Constants.CARTESIA_FIELD_TRANSCRIPT, text);
            ttsMsg.put(Constants.CARTESIA_FIELD_VOICE, java.util.Map.of("id", currentVoiceId));
            ttsMsg.put(Constants.CARTESIA_FIELD_OUTPUT_FORMAT, java.util.Map.of(
                    Constants.CARTESIA_FIELD_CONTAINER, Constants.CARTESIA_CONTAINER,
                    Constants.CARTESIA_FIELD_ENCODING, Constants.CARTESIA_ENCODING_PCM_S16LE,
                    Constants.CARTESIA_FIELD_SAMPLE_RATE, sampleRate
            ));
            ttsMsg.put("generation_config", java.util.Map.of(Constants.CARTESIA_FIELD_SPEED, speed));
            ttsMsg.put(Constants.CARTESIA_FIELD_CONTEXT_ID, contextId);
            ttsMsg.put(Constants.CARTESIA_FIELD_CONTINUE, false);
            ttsMsg.put(Constants.CARTESIA_FIELD_MAX_BUFFER_DELAY_MS, properties.getTts().getMaxBufferDelayMs());
            return ttsMsg;
        }

        private String toJson(Map<String, Object> map) {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (Exception e) {
                throw BizException.of(ErrorCode.SYSTEM_ERROR, "JSON 序列化失败: " + e.getMessage());
            }
        }

        public synchronized void close() {
            if (webSocket != null) {
                try {
                    webSocket.close(Constants.CARTESIA_CLOSE_NORMAL, Constants.CARTESIA_CLOSE_REASON_CLIENT_CLOSED);
                } catch (Exception ignored) {
                    // 忽略关闭时的异常
                }
                webSocket = null;
            }
            open = false;
        }

        public boolean isOpen() {
            return open;
        }
    }
}
