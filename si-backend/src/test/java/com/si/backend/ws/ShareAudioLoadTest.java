package com.si.backend.ws;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 80 人会议音频分发的负载 / 带宽 / 正确性自动化测试。
 *
 * <p>用 80 个 mock WebSocket 连接（30 中 / 30 印 / 20 英）订阅同一会议，模拟一名中文发言人讲话：
 * 中文听众收原始音频(16k)、印尼/英语听众收 TTS(24k)。统计实际下发给 80 个连接的总字节，
 * 换算服务器出口带宽与人均下行；并验证“只把选中语言下发给对应听众”的语言隔离。
 */
class ShareAudioLoadTest {

    private static final String SESSION = "load-test-session";
    private final Map<String, AtomicLong> bytesByConnection = new ConcurrentHashMap<>();

    private static byte[] speechLikePcm(int sampleRate, double seconds) {
        int n = (int) (sampleRate * seconds);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = (double) i / sampleRate;
            double sample = (Math.sin(2 * Math.PI * 130 * t) + 0.5 * Math.sin(2 * Math.PI * 260 * t))
                    * 0.5 * (1 + Math.sin(2 * Math.PI * 4 * t));
            short s = (short) Math.max(-32768, Math.min(32767, sample * 0.5 * 32767));
            pcm[2 * i] = (byte) (s & 0xFF);
            pcm[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return pcm;
    }

    private WebSocketSession mockSession(String id, String lang) {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicLong counter = new AtomicLong(0);
        bytesByConnection.put(id, counter);
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(
                URI.create("ws://localhost/ws/share-audio?sessionId=" + SESSION + "&lang=" + lang));
        when(session.isOpen()).thenReturn(true);
        Answer<Void> capture = invocation -> {
            BinaryMessage msg = invocation.getArgument(0);
            counter.addAndGet(msg.getPayloadLength());
            return null;
        };
        try {
            doAnswer(capture).when(session).sendMessage(any());
        } catch (Exception ignored) {
            // mock 不会真的抛
        }
        return session;
    }

    private long sumBytes(List<String> ids) {
        long total = 0;
        for (String id : ids) {
            total += bytesByConnection.get(id).get();
        }
        return total;
    }

    @Test
    void eightyListenersBandwidthAndLanguageIsolation() throws Exception {
        ShareAudioWebSocketHandler handler = new ShareAudioWebSocketHandler();

        List<WebSocketSession> all = new ArrayList<>();
        List<String> zhIds = new ArrayList<>();
        List<String> idIds = new ArrayList<>();
        List<String> enIds = new ArrayList<>();
        for (int i = 0; i < 30; i++) { String id = "zh-" + i; zhIds.add(id); all.add(mockSession(id, "zh")); }
        for (int i = 0; i < 30; i++) { String id = "id-" + i; idIds.add(id); all.add(mockSession(id, "id")); }
        for (int i = 0; i < 20; i++) { String id = "en-" + i; enIds.add(id); all.add(mockSession(id, "en")); }
        all.forEach(handler::afterConnectionEstablished);

        byte[] oneSec16k = speechLikePcm(16000, 1.0);
        byte[] oneSec24k = speechLikePcm(24000, 1.0);

        // ── 1) 语言隔离：只广播中文原始音频 1 秒 ──
        long zhBefore = sumBytes(zhIds);
        long idBefore = sumBytes(idIds);
        long enBefore = sumBytes(enIds);
        handler.broadcastPcm(SESSION, "zh-CN", oneSec16k, 16000);
        Thread.sleep(400);
        long zhDelta = sumBytes(zhIds) - zhBefore;
        long idDelta = sumBytes(idIds) - idBefore;
        long enDelta = sumBytes(enIds) - enBefore;
        System.out.printf("[ShareAudioLoad] 隔离测试: 仅播中文 -> zh=%d 字节, id=%d, en=%d%n", zhDelta, idDelta, enDelta);
        assertTrue(zhDelta > 0, "中文听众应收到音频");
        assertEquals(0, idDelta, "中文发言时印尼语听众不应收到任何音频");
        assertEquals(0, enDelta, "中文发言时英语听众不应收到任何音频");

        // ── 2) 80 人会议带宽：一名中文发言人讲 D 秒，中文收原声 + 印/英收 TTS ──
        long baseline = sumBytes(zhIds) + sumBytes(idIds) + sumBytes(enIds);
        int seconds = 5;
        for (int i = 0; i < seconds; i++) {
            handler.broadcastPcm(SESSION, "zh-CN", oneSec16k, 16000); // 中文听众：原始音频
            handler.broadcastPcm(SESSION, "id", oneSec24k, 24000);    // 印尼语听众：TTS
            handler.broadcastPcm(SESSION, "en", oneSec24k, 24000);    // 英语听众：TTS
            Thread.sleep(120);
        }
        Thread.sleep(500);
        long totalEgress = sumBytes(zhIds) + sumBytes(idIds) + sumBytes(enIds) - baseline;

        double aggregateMbps = totalEgress * 8.0 / seconds / 1_000_000.0;
        double perListenerKbps = totalEgress * 8.0 / 80 / seconds / 1000.0;
        double gbPerHour = aggregateMbps / 8.0 * 3600.0 / 1000.0;

        System.out.printf("[ShareAudioLoad] 80 人(30中/30印/20英) %d 秒, 总下发=%d 字节%n", seconds, totalEgress);
        System.out.printf("[ShareAudioLoad] 服务器出口聚合带宽=%.2f Mbps, 人均下行=%.1f kbps, 流量=%.2f GB/小时%n",
                aggregateMbps, perListenerKbps, gbPerHour);

        assertTrue(totalEgress > 0, "应有音频下发");
        assertTrue(sumBytes(zhIds) > 0 && sumBytes(idIds) > 0 && sumBytes(enIds) > 0, "三种语言听众都应收到音频");
        assertTrue(perListenerKbps > 8 && perListenerKbps < 45, "人均下行应接近 24kbps, 实测=" + perListenerKbps);
        assertTrue(aggregateMbps < 5.0, "80 人出口聚合带宽应 < 5Mbps, 实测=" + aggregateMbps);

        // 清理：停止各连接的发送线程
        all.forEach(s -> handler.afterConnectionClosed(s, CloseStatus.NORMAL));
        handler.closeSession(SESSION);
    }
}
