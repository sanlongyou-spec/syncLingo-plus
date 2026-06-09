package com.si.backend.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.dto.WsMessage;
import com.si.backend.facade.RealtimeInterpretationFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
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

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLangMap = new ConcurrentHashMap<>();
    /** 每个会话当前检测到的源语言，用于把原始麦克风音频路由给“选了源语言”的分享听众 */
    private final Map<String, String> sessionSourceLangMap = new ConcurrentHashMap<>();
    private final Map<String, String> webSocketSessionBizSessionMap = new ConcurrentHashMap<>();
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
        String sourceLang = msg.getSourceLang();
        String targetLang = msg.getTargetLang();
        log.info("[AsrWebSocketHandler] handleStart, sessionId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, sourceLang, targetLang, msg.getVoiceId());

        sessionLangMap.put(sessionId, sourceLang + ":" + targetLang);
        webSocketSessionBizSessionMap.put(session.getId(), sessionId);

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
                    out.setSpeakerName(realtimeFacade.getCachedSpeakerName(sessionId, speakerId));
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
                    out.setSpeakerName(realtimeFacade.getCachedSpeakerName(sessionId, speakerId));
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
                // onSpeakerIdentity
                (speakerId, speakerName, speakerProfileId, cartesiaVoiceId, status, source) -> {
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_SPEAKER_IDENTITY);
                    out.setSessionId(sessionId);
                    out.setSpeakerId(speakerId);
                    out.setSpeakerName(speakerName);
                    out.setSpeakerProfileId(speakerProfileId);
                    out.setVoiceId(cartesiaVoiceId);
                    out.setSpeakerIdentityStatus(status);
                    out.setSpeakerIdentitySource(source);
                    sendMessage(session, out);
                    shareWebSocketHandler.broadcast(sessionId, out);
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

    private void handleAudio(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        String data = msg.getAudioBase64();
        if (data == null || data.isBlank()) {
            return;
        }
        byte[] pcm = java.util.Base64.getDecoder().decode(data);
        realtimeFacade.pushAudio(sessionId, pcm);
        // 把原始麦克风音频（16kHz）转发给“选了当前源语言”的分享听众（编码为 Opus，仅有订阅者时才编码）
        String sourceLang = sessionSourceLangMap.get(sessionId);
        if (sourceLang != null) {
            shareAudioWebSocketHandler.broadcastPcm(sessionId, sourceLang, pcm, Constants.DEFAULT_SAMPLE_RATE_ASR);
        }
    }

    private void handleStop(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        sessionLangMap.remove(sessionId);
        sessionSourceLangMap.remove(sessionId);
        webSocketSessionBizSessionMap.remove(session.getId());
        realtimeFacade.stopInterpretation(sessionId);
        shareAudioWebSocketHandler.closeSession(sessionId);

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_STOPPED);
        reply.setSessionId(sessionId);
        sendMessage(session, reply);
        shareWebSocketHandler.broadcast(sessionId, reply);
    }

    private void handleTranslate(WebSocketSession session, WsMessage msg) {
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
        sessions.remove(session.getId());
        OutboundMessageSender sender = outboundSenderMap.remove(session.getId());
        if (sender != null) {
            sender.stop();
        }
        String bizSessionId = webSocketSessionBizSessionMap.remove(session.getId());
        if (bizSessionId != null) {
            sessionLangMap.remove(bizSessionId);
            sessionSourceLangMap.remove(bizSessionId);
            realtimeFacade.cleanupSession(bizSessionId);
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
            } catch (IOException e) {
                log.error("[AsrWebSocketHandler] send IO error, sessionId={}, type={}",
                        session.getId(), message.source().getType(), e);
            } catch (IllegalStateException e) {
                log.error("[AsrWebSocketHandler] send state error, sessionId={}, type={}",
                        session.getId(), message.source().getType(), e);
            }
        }
    }
}
