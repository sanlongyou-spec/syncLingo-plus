package com.si.backend.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.dto.WsMessage;
import com.si.backend.service.ShareWsTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareWebSocketHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void broadcastBlocksTtsAudioMessages() throws Exception {
        ShareWebSocketHandler handler = new ShareWebSocketHandler(
                new ObjectMapper(),
                mock(ShareWsTicketService.class)
        );
        WebSocketSession subscriber = mock(WebSocketSession.class);
        when(subscriber.getId()).thenReturn("share-ws-1");
        when(subscriber.isOpen()).thenReturn(true);
        Map<String, Set<WebSocketSession>> subscribers =
                (Map<String, Set<WebSocketSession>>) ReflectionTestUtils.getField(handler, "sessionSubscribers");
        subscribers.put("session-1", ConcurrentHashMap.newKeySet());
        subscribers.get("session-1").add(subscriber);

        WsMessage msg = new WsMessage();
        msg.setType(Constants.WS_MSG_TYPE_TTS_AUDIO);
        msg.setAudioBase64("AA==");
        handler.broadcast("session-1", msg);

        verify(subscriber, never()).sendMessage(any(TextMessage.class));
    }
}
