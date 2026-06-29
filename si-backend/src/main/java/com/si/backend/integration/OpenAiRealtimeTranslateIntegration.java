package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.si.backend.common.Constants;
import com.si.backend.config.OpenAiRealtimeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiRealtimeTranslateIntegration {

    private static final String EVENT_SESSION_UPDATE = "session.update";
    private static final String EVENT_AUDIO_APPEND = "session.input_audio_buffer.append";
    private static final String EVENT_SESSION_CLOSE = "session.close";
    private static final String EVENT_INPUT_TRANSCRIPT_DELTA = "session.input_transcript.delta";
    private static final String EVENT_OUTPUT_TRANSCRIPT_DELTA = "session.output_transcript.delta";
    private static final String EVENT_OUTPUT_AUDIO_DELTA = "session.output_audio.delta";
    private static final String EVENT_SESSION_CLOSED = "session.closed";
    private static final String EVENT_ERROR = "error";
    private static final String MODEL_QUERY_PARAM = "model=";
    private static final int WS_NORMAL_CLOSE = 1000;

    private static final ScheduledExecutorService RECONNECT_EXECUTOR = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("openai-realtime-reconnect-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });

    private final OpenAiRealtimeProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(Duration.ofSeconds(20))
            .readTimeout(Duration.ZERO)
            .build();

    public RealtimeTranslationSession openSession(
            String sessionId,
            String targetLanguage,
            RealtimeTranslationListener listener
    ) {
        OpenAiRealtimeSession session = new OpenAiRealtimeSession(
                sessionId,
                normalizeOutputLanguage(targetLanguage),
                listener
        );
        session.connect();
        return session;
    }

    public static byte[] resample16kTo24k(byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length < 2) {
            return new byte[0];
        }
        int safeLength = pcm16k.length - (pcm16k.length % 2);
        int sourceSamples = safeLength / 2;
        if (sourceSamples == 0) {
            return new byte[0];
        }
        double ratio = (double) Constants.DEFAULT_SAMPLE_RATE_TTS / Constants.DEFAULT_SAMPLE_RATE_ASR;
        int targetSamples = (int) Math.ceil(sourceSamples * ratio);
        byte[] output = new byte[targetSamples * 2];
        for (int i = 0; i < targetSamples; i++) {
            double sourcePosition = i / ratio;
            int sourceIndex = (int) Math.floor(sourcePosition);
            double fraction = sourcePosition - sourceIndex;
            short current = readLittleEndianPcm16(pcm16k, Math.min(sourceIndex, sourceSamples - 1));
            short next = readLittleEndianPcm16(pcm16k, Math.min(sourceIndex + 1, sourceSamples - 1));
            short interpolated = (short) Math.round(current * (1.0D - fraction) + next * fraction);
            writeLittleEndianPcm16(output, i, interpolated);
        }
        return output;
    }

    private static short readLittleEndianPcm16(byte[] bytes, int sampleIndex) {
        int offset = sampleIndex * 2;
        return (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
    }

    private static void writeLittleEndianPcm16(byte[] bytes, int sampleIndex, short value) {
        int offset = sampleIndex * 2;
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private String realtimeUrl() {
        String baseUrl = properties.getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "wss://api.openai.com/v1/realtime/translations";
        }
        String model = properties.getModel();
        if (model == null || model.isBlank() || baseUrl.contains(MODEL_QUERY_PARAM)) {
            return baseUrl;
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + MODEL_QUERY_PARAM + model.trim();
    }

    private static String normalizeOutputLanguage(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return "zh";
        }
        String lower = targetLanguage.toLowerCase(Locale.ROOT);
        if (lower.startsWith("en")) {
            return "en";
        }
        if (lower.startsWith("id") || lower.startsWith("in")) {
            return "id";
        }
        return "zh";
    }

    public interface RealtimeTranslationSession {
        String targetLanguage();

        void sendAudio(byte[] pcm16k);

        void close();
    }

    public interface RealtimeTranslationListener {
        void onReady(String targetLanguage);

        void onInputTranscriptDelta(String delta);

        void onOutputTranscriptDelta(String targetLanguage, String delta);

        void onOutputAudio(String targetLanguage, byte[] pcm24k);

        void onError(String targetLanguage, String message);

        void onClosed(String targetLanguage);
    }

    private final class OpenAiRealtimeSession extends WebSocketListener implements RealtimeTranslationSession {
        private final String sessionId;
        private final String targetLanguage;
        private final RealtimeTranslationListener listener;
        private final ConcurrentLinkedQueue<String> pendingAudio = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pendingAudioSize = new AtomicInteger();
        private final AtomicBoolean closedByUser = new AtomicBoolean(false);
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final Deque<Long> reconnectTimestamps = new ArrayDeque<>();
        private volatile WebSocket webSocket;
        private volatile boolean fatal;

        private OpenAiRealtimeSession(
                String sessionId,
                String targetLanguage,
                RealtimeTranslationListener listener
        ) {
            this.sessionId = sessionId;
            this.targetLanguage = targetLanguage;
            this.listener = listener;
        }

        private void connect() {
            if (!properties.isUsable()) {
                listener.onError(targetLanguage, "OpenAI Realtime is disabled or api key is empty");
                return;
            }
            Request request = new Request.Builder()
                    .url(realtimeUrl())
                    .addHeader("Authorization", "Bearer " + properties.getApiKey().trim())
                    .build();
            log.info("[OpenAiRealtimeTranslateIntegration] connect start, sessionId={}, targetLang={}, url={}",
                    sessionId, targetLanguage, realtimeUrl());
            webSocket = client.newWebSocket(request, this);
        }

        @Override
        public String targetLanguage() {
            return targetLanguage;
        }

        @Override
        public void sendAudio(byte[] pcm16k) {
            if (closedByUser.get() || pcm16k == null || pcm16k.length == 0) {
                return;
            }
            byte[] pcm24k = resample16kTo24k(pcm16k);
            if (pcm24k.length == 0) {
                return;
            }
            String encoded = Base64.getEncoder().encodeToString(pcm24k);
            if (!ready.get() || webSocket == null) {
                if (pendingAudioSize.get() < properties.getMaxPendingFrames()) {
                    pendingAudio.offer(encoded);
                    pendingAudioSize.incrementAndGet();
                }
                return;
            }
            sendAudioFrame(encoded);
        }

        @Override
        public void close() {
            closedByUser.set(true);
            ready.set(false);
            pendingAudio.clear();
            pendingAudioSize.set(0);
            WebSocket current = webSocket;
            if (current == null) {
                return;
            }
            sendJson(closeEvent());
            RECONNECT_EXECUTOR.schedule(
                    () -> current.close(WS_NORMAL_CLOSE, "client close"),
                    Math.max(0L, properties.getCloseGraceMs()),
                    TimeUnit.MILLISECONDS
            );
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            ready.set(true);
            reconnectTimestamps.clear();
            log.info("[OpenAiRealtimeTranslateIntegration] connect end, sessionId={}, targetLang={}",
                    sessionId, targetLanguage);
            sendJson(sessionUpdateEvent());
            flushPendingAudio();
            listener.onReady(targetLanguage);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleTextMessage(text);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            ready.set(false);
            String message = throwable != null ? throwable.getMessage() : "unknown OpenAI Realtime failure";
            if (isFatal(message) || (response != null && response.code() >= 400 && response.code() < 500)) {
                fatal = true;
            }
            log.warn("[OpenAiRealtimeTranslateIntegration] websocket failure, sessionId={}, targetLang={}, fatal={}, message={}",
                    sessionId, targetLanguage, fatal, message, throwable);
            listener.onError(targetLanguage, message);
            reconnectIfAllowed();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            ready.set(false);
            log.info("[OpenAiRealtimeTranslateIntegration] websocket closed, sessionId={}, targetLang={}, code={}, reason={}",
                    sessionId, targetLanguage, code, reason);
            listener.onClosed(targetLanguage);
            if (!closedByUser.get() && !fatal) {
                reconnectIfAllowed();
            }
        }

        private void handleTextMessage(String payload) {
            JsonNode root;
            try {
                root = objectMapper.readTree(payload);
            } catch (Exception e) {
                log.debug("[OpenAiRealtimeTranslateIntegration] ignore non-json message, sessionId={}, targetLang={}, bytes={}",
                        sessionId, targetLanguage, payload.getBytes(StandardCharsets.UTF_8).length);
                return;
            }
            String type = root.path("type").asText("");
            switch (type) {
                case EVENT_INPUT_TRANSCRIPT_DELTA -> {
                    String delta = root.path("delta").asText("");
                    if (!delta.isBlank()) {
                        listener.onInputTranscriptDelta(delta);
                    }
                }
                case EVENT_OUTPUT_TRANSCRIPT_DELTA -> {
                    String delta = root.path("delta").asText("");
                    if (!delta.isBlank()) {
                        listener.onOutputTranscriptDelta(targetLanguage, delta);
                    }
                }
                case EVENT_OUTPUT_AUDIO_DELTA -> {
                    String delta = root.path("delta").asText("");
                    if (!delta.isBlank()) {
                        listener.onOutputAudio(targetLanguage, Base64.getDecoder().decode(delta));
                    }
                }
                case EVENT_SESSION_CLOSED -> {
                    listener.onClosed(targetLanguage);
                    WebSocket current = webSocket;
                    if (current != null) {
                        current.close(WS_NORMAL_CLOSE, "session.closed");
                    }
                }
                case EVENT_ERROR -> {
                    String message = root.path("error").toString();
                    fatal = fatal || isFatal(message);
                    listener.onError(targetLanguage, message);
                }
                default -> {
                    if (log.isTraceEnabled()) {
                        log.trace("[OpenAiRealtimeTranslateIntegration] event ignored, sessionId={}, targetLang={}, type={}",
                                sessionId, targetLanguage, type);
                    }
                }
            }
        }

        private void flushPendingAudio() {
            int flushed = 0;
            String audio;
            while ((audio = pendingAudio.poll()) != null) {
                pendingAudioSize.decrementAndGet();
                sendAudioFrame(audio);
                flushed++;
            }
            if (flushed > 0) {
                log.info("[OpenAiRealtimeTranslateIntegration] flushed buffered audio, sessionId={}, targetLang={}, frames={}",
                        sessionId, targetLanguage, flushed);
            }
        }

        private void sendAudioFrame(String encodedAudio) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", EVENT_AUDIO_APPEND);
            root.put("audio", encodedAudio);
            sendJson(root);
        }

        private ObjectNode sessionUpdateEvent() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", EVENT_SESSION_UPDATE);
            ObjectNode session = root.putObject("session");
            ObjectNode audio = session.putObject("audio");
            ObjectNode input = audio.putObject("input");
            ObjectNode transcription = input.putObject("transcription");
            transcription.put("model", properties.getInputTranscriptionModel());
            ObjectNode output = audio.putObject("output");
            output.put("language", targetLanguage);
            return root;
        }

        private ObjectNode closeEvent() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", EVENT_SESSION_CLOSE);
            return root;
        }

        private void sendJson(JsonNode node) {
            WebSocket current = webSocket;
            if (current == null) {
                return;
            }
            try {
                current.send(objectMapper.writeValueAsString(node));
            } catch (Exception e) {
                log.warn("[OpenAiRealtimeTranslateIntegration] send json failed, sessionId={}, targetLang={}",
                        sessionId, targetLanguage, e);
            }
        }

        private void reconnectIfAllowed() {
            if (closedByUser.get() || fatal) {
                listener.onClosed(targetLanguage);
                return;
            }
            long now = System.currentTimeMillis();
            long windowMs = Math.max(1L, properties.getReconnectWindowMs());
            synchronized (reconnectTimestamps) {
                while (!reconnectTimestamps.isEmpty() && now - reconnectTimestamps.peekFirst() > windowMs) {
                    reconnectTimestamps.removeFirst();
                }
                if (reconnectTimestamps.size() >= properties.getMaxReconnectsPerWindow()) {
                    fatal = true;
                    listener.onError(targetLanguage, "OpenAI Realtime reconnect limit reached");
                    listener.onClosed(targetLanguage);
                    return;
                }
                reconnectTimestamps.addLast(now);
                int attempt = reconnectTimestamps.size();
                long delayMs = Math.min(
                        Math.max(properties.getReconnectBaseDelayMs(), properties.getReconnectBaseDelayMs() * attempt),
                        properties.getReconnectMaxDelayMs()
                );
                log.info("[OpenAiRealtimeTranslateIntegration] reconnect scheduled, sessionId={}, targetLang={}, attempt={}, delayMs={}",
                        sessionId, targetLanguage, attempt, delayMs);
                RECONNECT_EXECUTOR.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
            }
        }

        private boolean isFatal(String message) {
            if (message == null) {
                return false;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            return lower.contains("unauthor")
                    || lower.contains("forbidden")
                    || lower.contains("invalid_api_key")
                    || lower.contains("invalid api key")
                    || lower.contains("permission")
                    || lower.contains("401")
                    || lower.contains("403");
        }
    }
}
