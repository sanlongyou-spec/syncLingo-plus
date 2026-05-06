package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.CartesiaProperties;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTS 业务服务，管理 Cartesia WebSocket 连接池，按音色 ID 隔离连接池。
 * 提供流式合成接口，内部自动从池中借出/归还连接。
 */
@Slf4j
@Service
public class TtsService {

    private final CartesiaProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, GenericObjectPool<CartesiaWsClient>> voicePools = new ConcurrentHashMap<>();

    public TtsService(CartesiaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时异步预热默认音色连接池，避免阻塞应用启动流程。
     */
    @PostConstruct
    public void init() {
        log.info("[TtsService] init, prewarming default voice pools async");
        int warmCount = properties.getPool().getMinIdlePerVoice();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            prewarmPool(properties.getDefaultVoiceIdChinese(), warmCount);
            prewarmPool(properties.getDefaultVoiceIdIndonesian(), warmCount);
            log.info("[TtsService] init done");
        });
    }

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
            java.util.function.Consumer<byte[]> onChunk,
            Runnable onComplete,
            java.util.function.Consumer<String> onError
    ) {
        log.info("[TtsService] synthesizeStream start, voiceId={}, textLen={}, sampleRate={}, speed={}",
                voiceId, text != null ? text.length() : 0, sampleRate, speed);

        GenericObjectPool<CartesiaWsClient> pool = getOrCreatePool(voiceId);

        CartesiaWsClient borrowedClient = null;
        try {
            borrowedClient = pool.borrowObject();
            log.debug("[TtsService] borrowed client, voiceId={}, active={}, idle={}",
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
                    onChunk,
                    () -> {
                        returnClient(pool, clientHolder[0], voiceId);
                        onComplete.run();
                    },
                    err -> {
                        returnClient(pool, clientHolder[0], voiceId);
                        onError.accept(err);
                    }
            );

        } catch (Exception e) {
            log.error("[TtsService] synthesizeStream borrow error, voiceId={}", voiceId, e);
            if (borrowedClient != null) {
                try {
                    pool.invalidateObject(borrowedClient);
                } catch (Exception invalidEx) {
                    log.warn("[TtsService] invalidateObject error, voiceId={}", voiceId, invalidEx);
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
            log.debug("[TtsService] returned client, voiceId={}, active={}, idle={}",
                    voiceId, pool.getNumActive(), pool.getNumIdle());
        } catch (Exception returnEx) {
            log.warn("[TtsService] returnObject error, voiceId={}", voiceId, returnEx);
        }
    }

    /**
     * 预热指定音色的连接池。
     *
     * @param voiceId 音色 ID
     * @param count   预热连接数
     */
    public void prewarmPool(String voiceId, int count) {
        log.info("[TtsService] prewarmPool start, voiceId={}, count={}", voiceId, count);
        GenericObjectPool<CartesiaWsClient> pool = getOrCreatePool(voiceId);
        int warmed = 0;
        for (int i = 0; i < count; i++) {
            CartesiaWsClient client = null;
            try {
                client = pool.borrowObject();
                pool.returnObject(client);
                warmed++;
            } catch (Exception e) {
                log.warn("[TtsService] prewarmPool failed at index={}, voiceId={}", i, voiceId, e);
                break;
            }
        }
        log.info("[TtsService] prewarmPool end, voiceId={}, warmed={}, active={}, idle={}",
                voiceId, warmed, pool.getNumActive(), pool.getNumIdle());
    }

    /**
     * 按音色 ID 获取或创建连接池。
     */
    private GenericObjectPool<CartesiaWsClient> getOrCreatePool(String voiceId) {
        return voicePools.computeIfAbsent(voiceId, id -> {
            log.info("[TtsService] creating new pool, voiceId={}", id);

            GenericObjectPoolConfig<CartesiaWsClient> config = new GenericObjectPoolConfig<>();
            config.setMaxTotal(properties.getPool().getMaxTotalPerVoice());
            config.setMinIdle(properties.getPool().getMinIdlePerVoice());
            config.setMaxWait(java.time.Duration.ofMillis(properties.getPool().getMaxWaitMillis()));

            CartesiaPooledObjectFactory factory = new CartesiaPooledObjectFactory(properties, objectMapper);
            GenericObjectPool<CartesiaWsClient> pool = new GenericObjectPool<>(factory, config);

            log.info("[TtsService] pool created, voiceId={}, maxTotal={}, minIdle={}",
                    id, config.getMaxTotal(), config.getMinIdle());
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

        /** 所有 CartesiaWsClient 共享同一 OkHttpClient，复用连接池，避免每次合成重建 TCP 连接 */
        private static final okhttp3.OkHttpClient WS_HTTP_CLIENT = new okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(WS_WRITE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .pingInterval(WS_PING_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        private final CartesiaProperties properties;
        private final ObjectMapper objectMapper;
        private volatile String voiceId;
        private okhttp3.WebSocket webSocket;
        private volatile boolean open = false;

        public CartesiaWsClient(CartesiaProperties properties, ObjectMapper objectMapper) {
            this.properties = properties;
            this.objectMapper = objectMapper;
        }

        public void setVoiceId(String voiceId) {
            this.voiceId = voiceId;
        }

        public void streamSynthesize(
                String text,
                int sampleRate,
                double speed,
                java.util.function.Consumer<byte[]> onChunk,
                Runnable onComplete,
                java.util.function.Consumer<String> onError
        ) {
            // 仅持锁期间交换 WebSocket 引用，避免锁持有期间执行 I/O
            okhttp3.WebSocket oldWebSocket;
            synchronized (this) {
                oldWebSocket = this.webSocket;
                this.webSocket = null;
                this.open = false;
            }
            if (oldWebSocket != null) {
                oldWebSocket.close(Constants.CARTESIA_CLOSE_NORMAL, Constants.CARTESIA_CLOSE_REASON_REUSE);
            }

            String currentVoiceId = (voiceId != null && !voiceId.isBlank()) ? voiceId : Constants.VOICE_ID_DEFAULT;

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(properties.getApiUrl() + "/tts/websocket")
                    .addHeader("Authorization", "Bearer " + properties.getApiKey())
                    .addHeader("Cartesia-Version", Constants.CARTESIA_VERSION_HEADER)
                    .build();

            okhttp3.WebSocket newWebSocket = WS_HTTP_CLIENT.newWebSocket(request, new okhttp3.WebSocketListener() {

                @Override
                public void onOpen(okhttp3.WebSocket ws, okhttp3.Response response) {
                    open = true;
                    log.info("[CartesiaWsClient] WebSocket opened, voiceId={}", currentVoiceId);

                    String contextId = java.util.UUID.randomUUID().toString();
                    Map<String, Object> ttsMsg = new java.util.LinkedHashMap<>();
                    ttsMsg.put(Constants.CARTESIA_FIELD_TYPE, Constants.CARTESIA_MSG_TYPE_TTS_REQUEST);
                    ttsMsg.put(Constants.CARTESIA_FIELD_MODEL_ID, properties.getTts().getModelId());
                    ttsMsg.put(Constants.CARTESIA_FIELD_TRANSCRIPT, text);
                    ttsMsg.put(Constants.CARTESIA_FIELD_VOICE, java.util.Map.of("id", currentVoiceId));
                    ttsMsg.put(Constants.CARTESIA_FIELD_OUTPUT_FORMAT, java.util.Map.of(
                            Constants.CARTESIA_FIELD_CONTAINER, Constants.CARTESIA_CONTAINER,
                            Constants.CARTESIA_FIELD_ENCODING, Constants.CARTESIA_ENCODING_PCM_S16LE,
                            Constants.CARTESIA_FIELD_SAMPLE_RATE, sampleRate
                    ));
                    ttsMsg.put("generation_config", java.util.Map.of(Constants.CARTESIA_FIELD_SPEED, speed));
                    ttsMsg.put(Constants.CARTESIA_FIELD_CONTEXT_ID, contextId);
                    ws.send(toJson(ttsMsg));
                }

                @Override
                public void onMessage(okhttp3.WebSocket ws, String msg) {
                    log.info("[CartesiaWsClient] text message, len={}, preview={}", msg.length(),
                            msg.length() > 120 ? msg.substring(0, 120) : msg);
                    try {
                        JsonNode node = objectMapper.readTree(msg);
                        String type = node.path(Constants.CARTESIA_FIELD_TYPE).asText();
                        switch (type) {
                            case Constants.CARTESIA_MSG_TYPE_CHUNK -> {
                                String audioData = node.path(Constants.CARTESIA_FIELD_AUDIO).asText();
                                if (!audioData.isBlank()) {
                                    byte[] pcm = java.util.Base64.getDecoder().decode(audioData);
                                    log.info("[CartesiaWsClient] chunk received, bytes={}", pcm.length);
                                    onChunk.accept(pcm);
                                }
                            }
                            case Constants.CARTESIA_MSG_TYPE_DONE -> {
                                log.info("[CartesiaWsClient] TTS synthesis done");
                                onComplete.run();
                                ws.close(Constants.CARTESIA_CLOSE_NORMAL, Constants.CARTESIA_CLOSE_REASON_DONE);
                            }
                            case Constants.CARTESIA_MSG_TYPE_ERROR -> {
                                String errMsg = node.path(Constants.CARTESIA_FIELD_MESSAGE).asText(Constants.TTS_ERROR_UNKNOWN);
                                log.error("[CartesiaWsClient] TTS error: {}", errMsg);
                                onError.accept(errMsg);
                                ws.close(Constants.CARTESIA_CLOSE_SERVER_ERROR, Constants.CARTESIA_CLOSE_REASON_REUSE);
                            }
                            default -> log.warn("[CartesiaWsClient] unknown message type={}", type);
                        }
                    } catch (Exception e) {
                        log.warn("[CartesiaWsClient] failed to parse message: {}", e.getMessage());
                    }
                }

                // 二进制帧：Cartesia 部分版本直接发原始 PCM 而非 base64 JSON
                @Override
                public void onMessage(okhttp3.WebSocket ws, okio.ByteString bytes) {
                    log.info("[CartesiaWsClient] binary frame received, bytes={}", bytes.size());
                    onChunk.accept(bytes.toByteArray());
                }

                @Override
                public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response response) {
                    open = false;
                    String errMsg = t != null ? t.getMessage() : Constants.TTS_ERROR_UNKNOWN;
                    log.error("[CartesiaWsClient] WebSocket failure, error={}", errMsg, t);
                    onError.accept(errMsg);
                }

                @Override
                public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                    open = false;
                    log.warn("[CartesiaWsClient] WebSocket closed, code={}, reason={}", code, reason);
                }
            });
            synchronized (this) {
                this.webSocket = newWebSocket;
            }
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
