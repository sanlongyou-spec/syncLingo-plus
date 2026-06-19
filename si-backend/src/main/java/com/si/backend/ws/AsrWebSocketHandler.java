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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
    private final ShareAudioWebSocketHandler shareAudioWebSocketHandler;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;
    private final UserWebSocketRegistry userWebSocketRegistry;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLangMap = new ConcurrentHashMap<>();
    /** 每个会话当前检测到的源语言，用于把原始麦克风音频路由给“选了源语言”的分享听众 */
    private final Map<String, String> sessionSourceLangMap = new ConcurrentHashMap<>();
    /** 会话配置的源语言(handleStart 传入)：为具体语言时原声按它路由，避免随每句检测漂移导致串台 */
    private final Map<String, String> sessionConfiguredSourceLangMap = new ConcurrentHashMap<>();
    /** 上次原声路由到的语言：仅在变化时打一条诊断日志，不逐包刷屏 */
    private final Map<String, String> sessionAudioRouteLangMap = new ConcurrentHashMap<>();
    private final Map<String, String> webSocketSessionBizSessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> bizSessionWebSocketMap = new ConcurrentHashMap<>();
    private final Set<String> stoppedWebSocketSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, OutboundMessageSender> outboundSenderMap = new ConcurrentHashMap<>();

    private static final int TEXT_QUEUE_CAPACITY = 1000;
    private static final int AUDIO_QUEUE_CAPACITY = 400;
    private static final ExecutorService OUTBOUND_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("asr-ws-outbound-" + thread.getId());
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
        sessionConfiguredSourceLangMap.put(sessionId, sourceLang == null ? "" : sourceLang);
        realtimeFacade.startInterpretation(
                sessionId,
                sourceLang,
                targetLang,
                msg.getVoiceId(),
                // onRecognizing
                (text, language, speakerId) -> {
                    if (language != null && !language.isBlank()) {
                        sessionSourceLangMap.put(sessionId, language);
                    }
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
                    if (language != null && !language.isBlank()) {
                        sessionSourceLangMap.put(sessionId, language);
                    }
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
                // onTtsAudio：将 TTS PCM 编码为 Opus，按目标语言扇出给分享页听众（不再发宿主/VoiceMeeter）
                (pcmData, tLang, ttsTaskId, ttsSequence, chunkIndex, speechStartAtMs) -> {
                    if (chunkIndex == 0) {
                        // 该句首音：发标记，携带"开始收音→首音发出"的服务端耗时，供前端合成真实出声延迟
                        int captureMs = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - speechStartAtMs);
                        shareAudioWebSocketHandler.sendMarker(sessionId, tLang, captureMs);
                    }
                    shareAudioWebSocketHandler.broadcastPcm(sessionId, tLang, pcmData, Constants.DEFAULT_SAMPLE_RATE_TTS);
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
        realtimeFacade.pushAudio(sessionId, pcm);
        // 原始麦克风(16kHz)转发给“听该语言原声”的分享听众。会议指定了具体源语言时按它路由
        // (避免随每句检测漂移/误判导致串台，如印尼语原声漏到中文频道)；仅 auto 时才用动态检测的源语言。
        String configuredSource = sessionConfiguredSourceLangMap.get(sessionId);
        String routeLang = isConcreteLang(configuredSource) ? configuredSource : sessionSourceLangMap.get(sessionId);
        if (routeLang != null && !routeLang.isBlank()) {
            String prev = sessionAudioRouteLangMap.put(sessionId, routeLang);
            if (!routeLang.equals(prev)) {
                log.info("[AsrWebSocketHandler] original-audio route lang, sessionId={}, routeLang={}, configured={}, detected={}",
                        sessionId, routeLang, configuredSource, sessionSourceLangMap.get(sessionId));
            }
            shareAudioWebSocketHandler.broadcastPcm(sessionId, routeLang, pcm, Constants.DEFAULT_SAMPLE_RATE_ASR);
        }
    }

    private void handleStop(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        if (!requireBoundSession(session, sessionId, true)) {
            return;
        }
        stoppedWebSocketSessionIds.add(session.getId());
        sessionLangMap.remove(sessionId);
        sessionSourceLangMap.remove(sessionId);
        sessionConfiguredSourceLangMap.remove(sessionId);
        sessionAudioRouteLangMap.remove(sessionId);
        realtimeFacade.stopInterpretation(sessionId);
        shareAudioWebSocketHandler.closeSession(sessionId);

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
            sessionSourceLangMap.remove(bizSessionId);
            sessionConfiguredSourceLangMap.remove(bizSessionId);
            sessionAudioRouteLangMap.remove(bizSessionId);
            if (!stoppedWebSocketSessionIds.remove(session.getId())) {
                realtimeFacade.cleanupSession(bizSessionId);
            }
            shareAudioWebSocketHandler.closeSession(bizSessionId);
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
        webSocketSessionBizSessionMap.put(session.getId(), businessSessionId);
        return true;
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

    /** 是否为具体语言(非空、非 auto)。用于决定原声是否按配置源语言固定路由。 */
    private boolean isConcreteLang(String lang) {
        if (lang == null) {
            return false;
        }
        String l = lang.trim().toLowerCase();
        return !l.isEmpty() && !"auto".equals(l);
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
            boolean queued = message.audio() ? audioQueue.offer(message) : textQueue.offer(message);
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
