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
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLangMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[AsrWebSocketHandler] connection established, sessionId={}", session.getId());
        sessions.put(session.getId(), session);
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

        sessionLangMap.put(sessionId, sourceLang + ":" + targetLang);

        realtimeFacade.startInterpretation(
                sessionId,
                sourceLang,
                targetLang,
                msg.getVoiceId(),
                // onRecognizing
                (text, language) -> {
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_RECOGNIZING);
                    out.setSessionId(sessionId);
                    out.setText(text);
                    out.setLanguage(language);
                    sendMessage(session, out);
                },
                // onRecognized：仅推送 WebSocket 消息，翻译/TTS 由 facade 内部管道处理
                (text, language) -> {
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_RECOGNIZED);
                    out.setSessionId(sessionId);
                    out.setText(text);
                    out.setLanguage(language);
                    sendMessage(session, out);
                },
                // onTranslated：将译文推送给前端展示
                (originalText, translatedText, tLang) -> {
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_TRANSLATED);
                    out.setSessionId(sessionId);
                    out.setText(originalText);
                    out.setTranslatedText(translatedText);
                    out.setTargetLanguage(tLang);
                    sendMessage(session, out);
                },
                // onTtsAudio：将 TTS PCM 数据推送给前端
                (pcmData, tLang) -> {
                    String audioBase64 = java.util.Base64.getEncoder().encodeToString(pcmData);
                    WsMessage out = new WsMessage();
                    out.setType(Constants.WS_MSG_TYPE_TTS_AUDIO);
                    out.setSessionId(sessionId);
                    out.setAudioBase64(audioBase64);
                    out.setTargetLanguage(tLang);
                    sendMessage(session, out);
                },
                // onError
                errorMessage -> sendError(session, sessionId, Constants.WS_ERROR_ASR_ERROR, errorMessage)
        );

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_STARTED);
        reply.setSessionId(sessionId);
        sendMessage(session, reply);
    }

    private void handleAudio(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        String data = msg.getAudioBase64();
        if (data == null || data.isBlank()) {
            return;
        }
        byte[] pcm = java.util.Base64.getDecoder().decode(data);
        realtimeFacade.pushAudio(sessionId, pcm);
    }

    private void handleStop(WebSocketSession session, WsMessage msg) {
        String sessionId = msg.getSessionId();
        sessionLangMap.remove(sessionId);
        realtimeFacade.stopInterpretation(sessionId);

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_STOPPED);
        reply.setSessionId(sessionId);
        sendMessage(session, reply);
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
        String sessionIdToRemove = sessions.entrySet().stream()
                .filter(e -> e.getValue() == session)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(session.getId());
        sessions.remove(sessionIdToRemove);
        realtimeFacade.cleanupSession(sessionIdToRemove);
    }

    /**
     * 发送 WebSocket 文本消息。
     * 注意：Spring WebSocket Session 本身非线程安全，sendMessage 必须在持有 session 锁的情况下执行，
     * 且只捕获 IOException（线程安全相关的 IllegalStateException 由调用方处理）。
     */
    private void sendMessage(WebSocketSession session, WsMessage msg) {
        String type = msg.getType();
        boolean isTtsAudio = Constants.WS_MSG_TYPE_TTS_AUDIO.equals(type);
        boolean isOpen = false;
        synchronized (session) {
            isOpen = session.isOpen();
        }
        if (!isOpen) {
            log.warn("[AsrWebSocketHandler] session closed, skip send, sessionId={}, type={}",
                    session.getId(), type);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            if (isTtsAudio) {
                log.info("[AsrWebSocketHandler] sent tts_audio, sessionId={}, jsonLen={}",
                        session.getId(), json.length());
            }
        } catch (IOException e) {
            log.error("[AsrWebSocketHandler] send IO error, sessionId={}, type={}", session.getId(), type, e);
        } catch (IllegalStateException e) {
            log.error("[AsrWebSocketHandler] send state error (concurrent access?), sessionId={}, type={}",
                    session.getId(), type, e);
        }
    }

    private void sendError(WebSocketSession session, String sessionId, String code, String errorMessage) {
        WsMessage msg = new WsMessage();
        msg.setType(Constants.WS_MSG_TYPE_ERROR);
        msg.setSessionId(sessionId);
        msg.setCode(code);
        msg.setMessage(errorMessage);
        sendMessage(session, msg);
    }
}
