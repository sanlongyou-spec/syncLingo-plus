package com.si.backend.ws;

import com.si.backend.audio.OpusStreamEncoder;
import com.si.backend.common.Constants;
import com.si.backend.service.ShareWsTicketService;
import io.github.jaredmdobson.concentus.OpusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分享页音频分发 WebSocket（二进制 Opus 包）。
 *
 * <p>连接地址：{@code /ws/share-audio?sessionId=xxx&lang=zh|id|en}。
 * 服务器为每个 (sessionId, lang) 维护一个 {@link OpusStreamEncoder}，把 TTS / 原始麦克风 PCM
 * 编码成 48kHz/24kbps Opus 包，只扇出给“选了该语言”的订阅者。每包一条二进制帧（一个 20ms Opus 包）。
 *
 * <p>每个订阅连接有独立的<b>无界</b>发送队列 + 单独发送线程：严格不丢音频。Opus 包很小(~60B/20ms)，
 * 慢客户端只是在自己的队列里堆积（内存可忽略），不影响其他订阅者；真正掉线的连接其发送线程会在
 * sendMessage 抛 IOException 时自行退出、随后被清理。重连的听众直接接入实时流（同传不回放历史）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareAudioWebSocketHandler extends BinaryWebSocketHandler {

    /** 二进制帧首字节类型标记 */
    private static final byte FRAME_AUDIO = 0x01;   // 后跟 Opus 包
    private static final byte FRAME_MARKER = 0x02;  // 后跟 int32 captureMs（句首音标记）
    private static final byte FRAME_PING = 0x03;    // 心跳/RTT 测量，回显

    private static final ExecutorService SENDER_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("share-audio-sender-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });

    /** sessionId -> (lang -> 订阅者集合) */
    private final Map<String, Map<String, Set<AudioSubscriber>>> subscribers = new ConcurrentHashMap<>();
    /** wsConnectionId -> 订阅者 */
    private final Map<String, AudioSubscriber> connectionMap = new ConcurrentHashMap<>();
    /** sessionId::lang -> Opus 编码器 */
    private final Map<String, OpusStreamEncoder> encoders = new ConcurrentHashMap<>();
    private final ShareWsTicketService shareWsTicketService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String ticket = UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams()
                .getFirst(Constants.WS_QUERY_PARAM_TICKET);
        ShareWsTicketService.Entry entry = shareWsTicketService.consume(ticket);
        String sessionId = entry != null ? entry.sessionId() : null;
        String lang = entry != null ? normalizeLang(entry.lang()) : null;
        if (sessionId == null || sessionId.isBlank() || lang == null) {
            log.warn("[ShareAudioWebSocketHandler] missing/invalid share ticket, close, connectionId={}", session.getId());
            closeQuietly(session);
            return;
        }
        AudioSubscriber subscriber = new AudioSubscriber(session, sessionId, lang);
        connectionMap.put(session.getId(), subscriber);
        subscribers.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(lang, k -> ConcurrentHashMap.newKeySet())
                .add(subscriber);
        log.info("[ShareAudioWebSocketHandler] subscribed, sessionId={}, lang={}, connectionId={}",
                sessionId, lang, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AudioSubscriber subscriber = connectionMap.remove(session.getId());
        if (subscriber == null) {
            return;
        }
        subscriber.stop();
        Map<String, Set<AudioSubscriber>> byLang = subscribers.get(subscriber.sessionId);
        if (byLang != null) {
            Set<AudioSubscriber> set = byLang.get(subscriber.lang);
            if (set != null) {
                set.remove(subscriber);
                if (set.isEmpty()) {
                    byLang.remove(subscriber.lang);
                }
            }
            if (byLang.isEmpty()) {
                subscribers.remove(subscriber.sessionId);
            }
        }
        log.info("[ShareAudioWebSocketHandler] unsubscribed, sessionId={}, lang={}, connectionId={}, status={}",
                subscriber.sessionId, subscriber.lang, session.getId(), status);
    }

    /**
     * 把一段 PCM 编码为 Opus 并扇出给该 (sessionId, lang) 的订阅者。
     * 没有订阅者时跳过编码，避免无谓 CPU。
     *
     * @param inSampleRate 输入采样率（原始麦克风 16000 / TTS 24000）
     */
    public void broadcastPcm(String sessionId, String lang, byte[] pcm, int inSampleRate) {
        if (sessionId == null || pcm == null || pcm.length == 0) {
            return;
        }
        String canonical = normalizeLang(lang);
        if (canonical == null) {
            return;
        }
        Map<String, Set<AudioSubscriber>> byLang = subscribers.get(sessionId);
        if (byLang == null) {
            return;
        }
        Set<AudioSubscriber> targets = byLang.get(canonical);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        OpusStreamEncoder encoder = encoders.get(sessionId + "::" + canonical);
        if (encoder == null) {
            encoder = encoders.computeIfAbsent(sessionId + "::" + canonical, k -> createEncoder());
        }
        if (encoder == null) {
            return;
        }
        List<byte[]> packets = encoder.feed(pcm, inSampleRate);
        if (packets.isEmpty()) {
            return;
        }
        for (byte[] packet : packets) {
            byte[] framed = new byte[packet.length + 1];
            framed[0] = FRAME_AUDIO;
            System.arraycopy(packet, 0, framed, 1, packet.length);
            for (AudioSubscriber subscriber : targets) {
                subscriber.offer(framed);
            }
        }
    }

    /**
     * 发送"句首音标记"：携带服务端已耗时（开始收音→首音发出，ms），供前端合成真实出声延迟。
     */
    public void sendMarker(String sessionId, String lang, int captureMs) {
        Set<AudioSubscriber> targets = targetsOf(sessionId, lang);
        if (targets == null) {
            return;
        }
        byte[] m = new byte[5];
        m[0] = FRAME_MARKER;
        m[1] = (byte) (captureMs >>> 24);
        m[2] = (byte) (captureMs >>> 16);
        m[3] = (byte) (captureMs >>> 8);
        m[4] = (byte) captureMs;
        for (AudioSubscriber subscriber : targets) {
            subscriber.offer(m);
        }
    }

    private Set<AudioSubscriber> targetsOf(String sessionId, String lang) {
        if (sessionId == null) {
            return null;
        }
        String canonical = normalizeLang(lang);
        if (canonical == null) {
            return null;
        }
        Map<String, Set<AudioSubscriber>> byLang = subscribers.get(sessionId);
        if (byLang == null) {
            return null;
        }
        Set<AudioSubscriber> targets = byLang.get(canonical);
        return (targets == null || targets.isEmpty()) ? null : targets;
    }

    /** 客户端发 0x03 心跳帧用于 RTT 测量，原样回显。 */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, org.springframework.web.socket.BinaryMessage message) {
        java.nio.ByteBuffer buf = message.getPayload();
        if (buf.remaining() >= 1 && buf.get(buf.position()) == FRAME_PING) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new BinaryMessage(new byte[]{FRAME_PING}));
                    }
                }
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /** 会话结束时清理该会话的编码器（订阅连接保留，由客户端自行关闭）。 */
    public void closeSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        encoders.keySet().removeIf(key -> key.startsWith(sessionId + "::"));
        log.info("[ShareAudioWebSocketHandler] session encoders cleared, sessionId={}", sessionId);
    }

    private OpusStreamEncoder createEncoder() {
        try {
            return new OpusStreamEncoder();
        } catch (OpusException e) {
            log.error("[ShareAudioWebSocketHandler] create Opus encoder failed: {}", e.getMessage());
            return null;
        }
    }

    public static String normalizeLang(String lang) {
        if (lang == null) {
            return null;
        }
        String lower = lang.trim().toLowerCase();
        if (lower.isEmpty()) {
            return null;
        }
        if (lower.startsWith("zh")) {
            return "zh";
        }
        if (lower.startsWith("id")) {
            return "id";
        }
        if (lower.startsWith("en")) {
            return "en";
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.BAD_DATA);
        } catch (IOException ignored) {
            // ignore
        }
    }

    /** 单个订阅连接：有界队列（500帧≈10s音频）+ 独立发送线程，慢客户端队列满时断开连接。 */
    private static final int MAX_QUEUE_PACKETS = 500;

    private static final class AudioSubscriber implements Runnable {
        private final WebSocketSession session;
        private final String sessionId;
        private final String lang;
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE_PACKETS);
        private final AtomicBoolean running = new AtomicBoolean(true);

        private AudioSubscriber(WebSocketSession session, String sessionId, String lang) {
            this.session = session;
            this.sessionId = sessionId;
            this.lang = lang;
            SENDER_EXECUTOR.execute(this);
        }

        private void offer(byte[] packet) {
            if (!running.get()) return;
            if (!queue.offer(packet)) {
                // Queue full — slow client is too far behind; disconnect it to reclaim resources
                stop();
            }
        }

        private void stop() {
            running.set(false);
            queue.clear();
            // 投入一个空包唤醒线程退出
            queue.offer(new byte[0]);
        }

        @Override
        public void run() {
            while (running.get()) {
                try {
                    byte[] packet = queue.take();
                    if (!running.get() || packet.length == 0) {
                        continue;
                    }
                    synchronized (session) {
                        if (!session.isOpen()) {
                            return;
                        }
                        session.sendMessage(new BinaryMessage(packet));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | IllegalStateException e) {
                    log.warn("[ShareAudioWebSocketHandler] send failed, sessionId={}, lang={}, reason={}",
                            sessionId, lang, e.getMessage());
                    return;
                }
            }
        }
    }
}
