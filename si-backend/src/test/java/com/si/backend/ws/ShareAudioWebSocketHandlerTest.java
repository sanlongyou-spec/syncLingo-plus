package com.si.backend.ws;

import com.si.backend.service.ShareWsTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShareAudioWebSocketHandlerTest {

    @Test
    void languageSwitchClearsSubscriberQueueAndSendsResetEpoch() throws Exception {
        ShareWsTicketService ticketService = mock(ShareWsTicketService.class);
        ShareAudioWebSocketHandler handler = new ShareAudioWebSocketHandler(ticketService);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("share-audio-1");
        when(session.getUri()).thenReturn(URI.create("wss://example/ws/share-audio?ticket=ticket-1"));
        when(session.isOpen()).thenReturn(true);
        when(ticketService.consume("ticket-1")).thenReturn(new ShareWsTicketService.Entry(
                "business-session", "en", Instant.now().plusSeconds(60).getEpochSecond()));

        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<byte[]> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            BinaryMessage message = invocation.getArgument(0);
            byte[] bytes = new byte[message.getPayloadLength()];
            message.getPayload().get(bytes);
            payload.set(bytes);
            sent.countDown();
            return null;
        }).when(session).sendMessage(any(BinaryMessage.class));

        handler.afterConnectionEstablished(session);
        handler.resetTtsAudio("business-session", 7L);

        assertTrue(sent.await(5, TimeUnit.SECONDS));
        assertEquals(0x04, payload.get()[0]);
        assertEquals(7L, java.nio.ByteBuffer.wrap(payload.get(), 1, Long.BYTES).getLong());
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    }
}
