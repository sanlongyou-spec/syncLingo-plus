package com.si.backend.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.dto.WsMessage;
import com.si.backend.facade.RealtimeInterpretationFacade;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.ResourceOwnershipPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ASR WebSocket 处理器，负责接收客户端音频数据并推送识别/翻译结果。
 * 所有业务逻辑委托给 RealtimeInterpretationFacade，禁止直接调用 Integration 层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RealtimeInterpretationFacade realtimeFacade;
    private final ShareWebSocketHandler shareWebSocketHandler;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;
    private final UserWebSocketRegistry userWebSocketRegistry;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLangMap = new ConcurrentHashMap<>();
    private final Map<String, String> webSocketSessionBizSessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> bizSessionWebSocketMap = new ConcurrentHashMap<>();
    private final Set<String> stoppedWebSocketSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, OutboundMessageSender> outboundSenderMap = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingTransientCleanupTasks = new ConcurrentHashMap<>();

    private static final int TEXT_QUEUE_CAPACITY = 1000;
    /** 出站音频队列容量：加大以吸收链路抖动/瞬时写阻塞，避免句尾块被丢弃("读一半")。 */
    private static final int AUDIO_QUEUE_CAPACITY = 1200;
    /** 音频队列满时的背压等待上限：先给发送线程一点时间排空，超时仍满才丢最旧块。 */
    private static final long AUDIO_OFFER_BACKPRESSURE_MS = 300L;
    private static final int TRANSIENT_DISCONNECT_GRACE_SECONDS = 600;
    private static final ExecutorService OUTBOUND_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("asr-ws-outbound-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService CLEANUP_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("asr-ws-transient-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[AsrWebSocketHandler] connection established, sessionId={}", session.getId());
        sessions.put(session.getId(), session);
        outboundSenderMap.put(session.getId(), new OutboundMessageSender(session));
        AuthenticatedActor actor = resolveActor(session);
        if (actor != null) {
            userWebSocketRegistry.register(actor.userId(), session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("[AsrWebSocketHandler] message received, sessionId={}, payloadLen={}",
                session.getId(), message.getPayloadLength());
        try {
            WsMessage msg = objectMapper.readValue(message.getPayload(), WsMessage.class);
            String type = msg.getType();

            switch (type) {
                case Constants.WS_MSG_TYPE_START -> handleStart(session, msg);
                case Constants.WS_MSG_TYPE_AUDIO -> handleAudio(session, msg);
                case Constants.WS_MSG_TYPE_SET_VOICE -> handleSetVoice(session, msg);
                case Constants.WS_MSG_TYPE_STOP -> handleStop(session, msg);
                case Constants.WS_MSG_TYPE_TRANSLATE_TEXT -> handleTranslate(session, msg);
                case Constants.WS_MSG_TYPE_TTS_PLAYBACK_LOG -> handleTtsPlaybackLog(session, msg);
                default -> sendError(session, msg.getSessionId(), Constants.WS_ERROR_UNKNOWN_MESSAGE_TYPE,
                        "未知的消息类型: " + type);
            }
        } catch (Exception e) {
            log.error("[AsrWebSocketHandler] handle message error, sessionId={}", session.getId(), e);
            sendError(session, null, Constants.WS_ERROR_PARSE_ERROR, "消息解析失败: " + e.getMessage());
        }
    }

    private void handleStart(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        if (!bindOwnedSession(session, sessionId)) {
            return;
        }
        String sourceLang = msg.getSourceLang();
        String targetLang = msg.getTargetLang();
        log.info("[AsrWebSocketHandler] handleStart, sessionId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, sourceLang, targetLang, msg.getVoiceId());

        sessionLangMap.put(sessionId, sourceLang + ":" + targetLang);
        realtimeFacade.startInterpretation(
                sessionId,
                sourceLang,
                targetLang,
                msg.getVoiceId(),
                // onRecognizing
                (text, language, speakerId) -> {
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_RECOGNIZING);
                    out.setSessionId(sessionId);
                    out.setText(text);
                    out.setLanguage(language);
                    out.setSpeakerId(speakerId);
                    out.setSpeakerName(speakerId);
                    sendMessage(session, out);
                    shareWebSocketHandler.broadcast(sessionId, out);
                },
                // onRecognized：仅推送 WebSocket 消息，翻译/TTS 由 facade 内部管道处理
                (text, language, speakerId) -> {
                    log.info("[AsrWebSocketHandler] recognized, sessionId={}, speakerId={}, lang={}, textLen={}",
                            sessionId, speakerId, language, text != null ? text.length() : 0);
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_RECOGNIZED);
                    out.setSessionId(sessionId);
                    out.setText(text);
                    out.setLanguage(language);
                    out.setSpeakerId(speakerId);
                    out.setSpeakerName(speakerId);
                    sendMessage(session, out);
                    shareWebSocketHandler.broadcast(sessionId, out);
                },
                // onTranslated：将译文推送给前端展示
                (originalText, translatedText, sLang, tLang, speakerId, speakerName) -> {
                    log.info("[AsrWebSocketHandler] translated, sessionId={}, {}→{}, origLen={}, transLen={}",
                            sessionId, sLang, tLang,
                            originalText != null ? originalText.length() : 0,
                            translatedText != null ? translatedText.length() : 0);
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_TRANSLATED);
                    out.setSessionId(sessionId);
                    out.setText(originalText);
                    out.setSourceLang(sLang);
                    out.setTranslatedText(translatedText);
                    out.setTargetLanguage(tLang);
                    out.setSpeakerId(speakerId);
                    out.setSpeakerName(speakerName);
                    sendMessage(session, out);
                    shareWebSocketHandler.broadcast(sessionId, out);
                },
                // onTtsAudio：将译文 TTS PCM 回推宿主前端，由前端按目标语言 setSinkId 路由到 VoiceMeeter
                // （中文→Input/B1、印尼→Aux/B2、英语→VAIO3/B3）。原始麦克风音频绝不回推，不进 VoiceMeeter。
                (pcmData, tLang, ttsTaskId, ttsSequence, chunkIndex, speechStartAtMs) -> {
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_TTS_AUDIO);
                    out.setSessionId(sessionId);
                    out.setAudioBase64(Base64.getEncoder().encodeToString(pcmData));
                    out.setTargetLanguage(tLang);
                    out.setTtsTaskId(ttsTaskId);
                    out.setTtsSequence(ttsSequence);
                    out.setChunkIndex(chunkIndex);
                    sendMessage(session, out);
                },
                // onError
                errorMessage -> sendError(session, sessionId, Constants.WS_ERROR_ASR_ERROR, errorMessage)
        );

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_STARTED);
        reply.setSessionId(sessionId);
        sendMessage(session, reply);
        shareWebSocketHandler.broadcast(sessionId, reply);
    }

    private void handleSetVoice(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        if (!requireBoundSession(session, sessionId, true)) {
            return;
        }
        log.info("[AsrWebSocketHandler] handleSetVoice, sessionId={}, speakerId={}, hasVoice={}",
                sessionId, msg.getSpeakerId(), msg.getVoiceId() != null && !msg.getVoiceId().isBlank());
        try {
            realtimeFacade.setManualVoice(sessionId, msg.getSpeakerId(), msg.getVoiceId());
        } catch (Exception e) {
            log.warn("[AsrWebSocketHandler] handleSetVoice failed, sessionId={}, speakerId={}",
                    sessionId, msg.getSpeakerId(), e);
            sendError(session, sessionId, Constants.WS_ERROR_INVALID_STATE, e.getMessage());
        }
    }

    private void handleAudio(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        if (!requireBoundSession(session, sessionId, true)) {
            return;
        }
        String data = msg.getAudioBase64();
        if (data == null || data.isBlank()) {
            return;
        }
        byte[] pcm = java.util.Base64.getDecoder().decode(data);
        // 原始麦克风音频只进 ASR，绝不转发到任何音频出口(不进 VoiceMeeter、不发听众)。
        realtimeFacade.pushAudio(sessionId, pcm);
    }

    private void handleStop(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        if (!requireBoundSession(session, sessionId, true)) {
            return;
        }
        stoppedWebSocketSessionIds.add(session.getId());
        cancelTransientCleanup(sessionId);
        sessionLangMap.remove(sessionId);
        realtimeFacade.stopInterpretation(sessionId);

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_STOPPED);
        reply.setSessionId(sessionId);
        sendMessage(session, reply);
        shareWebSocketHandler.broadcast(sessionId, reply);
    }

    private void handleTranslate(WebSocketSession session, WsMessage msg) {
        if (!requireBoundSession(session, msg.getSessionId(), true)) {
            return;
        }
        String text = msg.getText();
        String targetLang = msg.getTargetLanguage();
        if (text == null || targetLang == null) {
            return;
        }
        log.info("[AsrWebSocketHandler] translate_text, sessionId={}, textLen={}, targetLang={}",
                msg.getSessionId(), text.length(), targetLang);
        String translated = realtimeFacade.translateText(text, targetLang);

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_TRANSLATED);
        reply.setText(text);
        reply.setTranslatedText(translated);
        reply.setTargetLanguage(targetLang);
        sendMessage(session, reply);
    }

    private void handleTtsPlaybackLog(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        if (!requireBoundSession(session, sessionId, true)) {
            return;
        }
        String event = safeLogText(msg.getEvent(), 64);
        String reason = safeLogText(msg.getReason(), 96);
        String detail = safeLogText(msg.getDetail(), 240);
        boolean warning = isPlaybackWarning(event, reason);
        if (warning) {
            log.warn("[AsrWebSocketHandler] tts-playback-client, sessionId={}, wsSessionId={}, event={}, reason={}, taskId={}, sequence={}, chunkIndex={}, targetLang={}, playbackLang={}, durationMs={}, scheduledAheadMs={}, pendingCount={}, contextState={}, audioPaused={}, sinkReady={}, sampleRate={}, detail='{}'",
                    sessionId, session.getId(), event, reason, msg.getTtsTaskId(), msg.getTtsSequence(),
                    msg.getChunkIndex(), msg.getTargetLanguage(), msg.getPlaybackLang(), msg.getDurationMs(),
                    msg.getScheduledAheadMs(), msg.getPendingCount(), msg.getContextState(),
                    msg.getAudioPaused(), msg.getSinkReady(), msg.getSampleRate(), detail);
            return;
        }
        log.info("[AsrWebSocketHandler] tts-playback-client, sessionId={}, wsSessionId={}, event={}, reason={}, taskId={}, sequence={}, chunkIndex={}, targetLang={}, playbackLang={}, durationMs={}, scheduledAheadMs={}, pendingCount={}, contextState={}, audioPaused={}, sinkReady={}, sampleRate={}, detail='{}'",
                sessionId, session.getId(), event, reason, msg.getTtsTaskId(), msg.getTtsSequence(),
                msg.getChunkIndex(), msg.getTargetLanguage(), msg.getPlaybackLang(), msg.getDurationMs(),
                msg.getScheduledAheadMs(), msg.getPendingCount(), msg.getContextState(),
                msg.getAudioPaused(), msg.getSinkReady(), msg.getSampleRate(), detail);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[AsrWebSocketHandler] connection closed, sessionId={}, status={}", session.getId(), status);
        AuthenticatedActor actor = resolveActor(session);
        if (actor != null) {
            userWebSocketRegistry.unregister(actor.userId(), session);
        }
        sessions.remove(session.getId());
        OutboundMessageSender sender = outboundSenderMap.remove(session.getId());
        if (sender != null) {
            sender.stop();
        }
        String bizSessionId = webSocketSessionBizSessionMap.remove(session.getId());
        if (bizSessionId != null) {
            bizSessionWebSocketMap.remove(bizSessionId, session.getId());
            sessionLangMap.remove(bizSessionId);
            if (!stoppedWebSocketSessionIds.remove(session.getId())) {
                scheduleTransientCleanup(bizSessionId);
            }
        } else {
            log.info("[AsrWebSocketHandler] no business session bound, skip realtime cleanup, wsSessionId={}", session.getId());
        }
    }

    /**
     * 发送 WebSocket 文本消息。
     * 注意：Spring WebSocket Session 本身非线程安全，sendMessage 必须在持有 session 锁的情况下执行，
     * 且只捕获 IOException（线程安全相关的 IllegalStateException 由调用方处理）。
     */
    private void sendMessage(WebSocketSession session, WsMessage msg) {
        String type = msg.getType();
        boolean isTtsAudio = Constants.WS_MSG_TYPE_TTS_AUDIO.equals(type);
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("[AsrWebSocketHandler] serialize error, sessionId={}, type={}", session.getId(), type, e);
            return;
        }
        OutboundMessageSender sender = outboundSenderMap.computeIfAbsent(
                session.getId(),
                ignored -> new OutboundMessageSender(session)
        );
        boolean queued = sender.offer(new OutboundMessage(json, msg, isTtsAudio));
        if (!queued) {
            log.warn("[AsrWebSocketHandler] outbound queue full, drop message, sessionId={}, type={}, ttsTaskId={}, chunkIndex={}",
                    session.getId(), type, msg.getTtsTaskId(), msg.getChunkIndex());
        }
    }

    private void sendError(WebSocketSession session, String sessionId, String code, String errorMessage) {
        WsMessage msg = new WsMessage();
        msg.setType(Constants.WS_MSG_TYPE_ERROR);
        msg.setSessionId(sessionId);
        msg.setCode(code);
        msg.setMessage(errorMessage);
        sendMessage(session, msg);
        if (sessionId != null) {
            shareWebSocketHandler.broadcast(sessionId, msg);
        }
    }

    private static boolean isPlaybackWarning(String event, String reason) {
        String joined = ((event == null ? "" : event) + " " + (reason == null ? "" : reason)).toLowerCase();
        return joined.contains("fail")
                || joined.contains("error")
                || joined.contains("drop")
                || joined.contains("missing")
                || joined.contains("duplicate")
                || joined.contains("pause")
                || joined.contains("ended")
                || joined.contains("stop");
    }

    private static String safeLogText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private boolean bindOwnedSession(WebSocketSession session, String businessSessionId) {
        if (businessSessionId == null || businessSessionId.isBlank()) {
            rejectAndClose(session, null, Constants.WS_ERROR_INVALID_STATE, "Missing sessionId");
            return false;
        }
        String boundSessionId = webSocketSessionBizSessionMap.get(session.getId());
        if (boundSessionId != null) {
            if (boundSessionId.equals(businessSessionId)) {
                sendError(session, businessSessionId, Constants.WS_ERROR_INVALID_STATE, "Session already started");
            } else {
                rejectAndClose(session, businessSessionId, Constants.WS_ERROR_INVALID_STATE,
                        "Cannot switch sessions on one connection");
            }
            return false;
        }
        AuthenticatedActor actor = resolveActor(session);
        if (actor == null) {
            rejectAndClose(session, businessSessionId, Constants.WS_ERROR_UNAUTHORIZED, "Unauthenticated");
            return false;
        }
        try {
            resourceOwnershipPolicy.requireOwnedSession(actor, businessSessionId);
        } catch (RuntimeException error) {
            log.warn("[AsrWebSocketHandler] session bind denied, wsSessionId={}, businessSessionId={}, userId={}",
                    session.getId(), businessSessionId, actor.userId());
            rejectAndClose(session, businessSessionId, Constants.WS_ERROR_UNAUTHORIZED, "Session unavailable");
            return false;
        }
        String controllingConnection = bizSessionWebSocketMap.putIfAbsent(businessSessionId, session.getId());
        if (controllingConnection != null && !controllingConnection.equals(session.getId())) {
            rejectAndClose(session, businessSessionId, Constants.WS_ERROR_SESSION_CONFLICT,
                    "Session already controlled by another connection");
            return false;
        }
        cancelTransientCleanup(businessSessionId);
        webSocketSessionBizSessionMap.put(session.getId(), businessSessionId);
        return true;
    }

    private void scheduleTransientCleanup(String businessSessionId) {
        ScheduledFuture<?> previous = pendingTransientCleanupTasks.remove(businessSessionId);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> task = CLEANUP_SCHEDULER.schedule(() -> {
            if (bizSessionWebSocketMap.containsKey(businessSessionId)) {
                log.info("[AsrWebSocketHandler] transient cleanup skipped, session rebound, sessionId={}", businessSessionId);
                return;
            }
            pendingTransientCleanupTasks.remove(businessSessionId);
            log.info("[AsrWebSocketHandler] transient cleanup firing, sessionId={}, graceSeconds={}",
                    businessSessionId, TRANSIENT_DISCONNECT_GRACE_SECONDS);
            realtimeFacade.cleanupSession(businessSessionId);
        }, TRANSIENT_DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        pendingTransientCleanupTasks.put(businessSessionId, task);
        log.info("[AsrWebSocketHandler] transient cleanup scheduled, sessionId={}, graceSeconds={}",
                businessSessionId, TRANSIENT_DISCONNECT_GRACE_SECONDS);
    }

    private void cancelTransientCleanup(String businessSessionId) {
        ScheduledFuture<?> task = pendingTransientCleanupTasks.remove(businessSessionId);
        if (task != null) {
            task.cancel(false);
            log.info("[AsrWebSocketHandler] transient cleanup canceled, sessionId={}", businessSessionId);
        }
    }

    private boolean requireBoundSession(
            WebSocketSession session,
            String requestedBusinessSessionId,
            boolean rejectStopped
    ) {
        String boundSessionId = webSocketSessionBizSessionMap.get(session.getId());
        if (boundSessionId == null
                || !boundSessionId.equals(requestedBusinessSessionId)
                || (rejectStopped && stoppedWebSocketSessionIds.contains(session.getId()))) {
            rejectAndClose(session, requestedBusinessSessionId, Constants.WS_ERROR_INVALID_STATE,
                    "Connection is not bound to this active session");
            return false;
        }
        return true;
    }

    private AuthenticatedActor resolveActor(WebSocketSession session) {
        Object value = session.getAttributes().get(JwtHandshakeInterceptor.ATTRIBUTE_AUTHENTICATED_USER_ID);
        return value instanceof Long userId ? new AuthenticatedActor(userId) : null;
    }

    private void rejectAndClose(WebSocketSession session, String sessionId, String code, String message) {
        sendError(session, sessionId, code, message);
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException closeError) {
            log.debug("[AsrWebSocketHandler] failed to close rejected connection, wsSessionId={}",
                    session.getId(), closeError);
        }
    }

    private record OutboundMessage(String json, WsMessage source, boolean audio) {
    }

    private final class OutboundMessageSender implements Runnable {
        private final WebSocketSession session;
        private final BlockingQueue<OutboundMessage> textQueue = new LinkedBlockingQueue<>(TEXT_QUEUE_CAPACITY);
        private final BlockingQueue<OutboundMessage> audioQueue = new LinkedBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
        private final Semaphore availableMessages = new Semaphore(0);
        private final AtomicBoolean running = new AtomicBoolean(true);

        private OutboundMessageSender(WebSocketSession session) {
            this.session = session;
            OUTBOUND_EXECUTOR.execute(this);
        }

        private boolean offer(OutboundMessage message) {
            if (!running.get()) {
                return false;
            }
            if (!message.audio()) {
                boolean queued = textQueue.offer(message);
                if (queued) {
                    availableMessages.release();
                }
                return queued;
            }
            return offerAudio(message);
        }

        /**
         * 音频出站三档策略，避免丢句尾导致"读一半"：
         * ① 直接入队；② 满则短暂背压等待发送线程排空；③ 仍满则丢【队首最旧】块、保住当前句尾。
         */
        private boolean offerAudio(OutboundMessage message) {
            if (audioQueue.offer(message)) {
                availableMessages.release();
                return true;
            }
            try {
                if (audioQueue.offer(message, AUDIO_OFFER_BACKPRESSURE_MS, TimeUnit.MILLISECONDS)) {
                    availableMessages.release();
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            // 背压超时仍满：丢最旧块(它的信号量许可复用给新块)，保住正在播放的句尾，把"读一半"降级为"丢中间一小段"。
            OutboundMessage dropped = audioQueue.poll();
            if (dropped != null) {
                availableMessages.tryAcquire();
                log.warn("[AsrWebSocketHandler] audio queue full, drop-oldest, sessionId={}, droppedTask={}, droppedChunk={}, keepTask={}, keepChunk={}",
                        session.getId(), dropped.source().getTtsTaskId(), dropped.source().getChunkIndex(),
                        message.source().getTtsTaskId(), message.source().getChunkIndex());
            }
            boolean queued = audioQueue.offer(message);
            if (queued) {
                availableMessages.release();
            }
            return queued;
        }

        private void stop() {
            running.set(false);
            textQueue.clear();
            audioQueue.clear();
            availableMessages.release();
        }

        @Override
        public void run() {
            log.info("[AsrWebSocketHandler] outbound sender start, sessionId={}", session.getId());
            while (running.get()) {
                try {
                    availableMessages.acquire();
                    if (!running.get()) {
                        break;
                    }
                    OutboundMessage next = textQueue.poll();
                    if (next == null) {
                        next = audioQueue.poll();
                    }
                    if (next == null) {
                        continue;
                    }
                    sendNow(next);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                } catch (Exception e) {
                    log.error("[AsrWebSocketHandler] outbound sender error, sessionId={}", session.getId(), e);
                }
            }
            log.info("[AsrWebSocketHandler] outbound sender stop, sessionId={}", session.getId());
        }

        private void sendNow(OutboundMessage message) {
            if (!session.isOpen()) {
                log.warn("[AsrWebSocketHandler] session closed, skip queued send, sessionId={}, type={}",
                        session.getId(), message.source().getType());
                return;
            }
            try {
                long sendStart = System.currentTimeMillis();
                session.sendMessage(new TextMessage(message.json()));
                if (message.audio()) {
                    WsMessage source = message.source();
                    log.info("[AsrWebSocketHandler] sent tts_audio, sessionId={}, taskId={}, sequence={}, chunkIndex={}, jsonLen={}, costMs={}",
                            session.getId(), source.getTtsTaskId(), source.getTtsSequence(), source.getChunkIndex(),
                            message.json().length(), System.currentTimeMillis() - sendStart);
                }
            } catch (IOException | IllegalStateException e) {
                // 客户端中途断开(刷新/关页)是常态：isOpen 检查与 sendMessage 之间存在竞态，
                // 连接此刻已关时只是良性丢弃，降级为 debug，避免污染 ERROR 监控；仍开着才是真异常。
                if (!session.isOpen()) {
                    log.debug("[AsrWebSocketHandler] drop send to closed session, sessionId={}, type={}",
                            session.getId(), message.source().getType());
                } else {
                    log.error("[AsrWebSocketHandler] send error on open session, sessionId={}, type={}",
                            session.getId(), message.source().getType(), e);
                }
            }
        }
    }
}
