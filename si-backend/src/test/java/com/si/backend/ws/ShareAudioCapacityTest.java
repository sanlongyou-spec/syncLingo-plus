package com.si.backend.ws;

import com.si.backend.common.Constants;
import com.si.backend.service.ShareWsTicketService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验分享音频 WS 的并发上限：达到上限后新连接被以 {@link Constants#WS_CLOSE_SHARE_FULL} 关闭。
 */
class ShareAudioCapacityTest {

    private WebSocketSession sessionWithTicket(String id, String ticket) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(new URI("ws://localhost/ws/share-audio?ticket=" + ticket));
        return session;
    }

    @Test
    void rejectsConnectionsBeyondMax() throws Exception {
        ShareWsTicketService ticketService = mock(ShareWsTicketService.class);
        when(ticketService.consume(anyString()))
                .thenReturn(new ShareWsTicketService.Entry("session-1", "zh",
                        System.currentTimeMillis() / 1000 + 60));

        ShareAudioWebSocketHandler handler = new ShareAudioWebSocketHandler(ticketService);
        ReflectionTestUtils.setField(handler, "maxAudioConnections", 2);

        WebSocketSession s1 = sessionWithTicket("c1", "t1");
        WebSocketSession s2 = sessionWithTicket("c2", "t2");
        WebSocketSession s3 = sessionWithTicket("c3", "t3");

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);
        handler.afterConnectionEstablished(s3); // 超过上限 → 拒绝

        // 前两个不被因满而关闭
        verify(s1, never()).close(any(CloseStatus.class));
        verify(s2, never()).close(any(CloseStatus.class));

        // 第三个被以"人数已满"关闭码关闭
        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(s3, times(1)).close(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                Constants.WS_CLOSE_SHARE_FULL, captor.getValue().getCode());

        handler.afterConnectionClosed(s1, CloseStatus.NORMAL);
        handler.afterConnectionClosed(s2, CloseStatus.NORMAL);
    }
}
