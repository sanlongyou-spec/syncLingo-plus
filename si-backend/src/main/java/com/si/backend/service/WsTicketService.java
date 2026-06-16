package com.si.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P5 WebSocket 一次性握手票据。受保护 HTTP 接口签发,WS 握手消费,避免把长效 JWT 放进 query
 * (query 会进 nginx/代理访问日志与浏览器历史)。票据短时(60s)、一次性、绑定 userId。
 *
 * <p>进程内存储(单实例 MVP);多实例需迁 Redis 并在签发节点外可消费。
 */
@Slf4j
@Service
public class WsTicketService {

    /** 票据有效期(秒)。短到足以连接,泄露价值低。 */
    static final long TICKET_TTL_SECONDS = 60;
    private static final int TICKET_BYTES = 32;
    private static final int MAX_ENTRIES = 100_000;

    private final SecureRandom secureRandom = new SecureRandom();
    /** ticket -> (userId, 过期 epoch 秒)。消费即删除,保证一次性。 */
    private final ConcurrentHashMap<String, Entry> tickets = new ConcurrentHashMap<>();

    /** 为已认证用户签发一次性票据。 */
    public String issue(long userId) {
        evictExpired();
        if (tickets.size() >= MAX_ENTRIES) {
            log.warn("[WsTicketService] ticket store full, size={}", tickets.size());
            throw com.si.backend.common.BizException.of(
                    com.si.backend.common.ErrorCode.INTERNAL_ERROR, "WS 票据暂不可用,请重试");
        }
        byte[] raw = new byte[TICKET_BYTES];
        secureRandom.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long expiry = Instant.now().getEpochSecond() + TICKET_TTL_SECONDS;
        tickets.put(ticket, new Entry(userId, expiry));
        log.info("[WsTicketService] issued ticket, userId={}", userId);
        return ticket;
    }

    /**
     * 消费票据:有效则返回 userId 并立即删除(一次性);无效/过期/重复使用返回 {@code null}。
     */
    public Long consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Entry entry = tickets.remove(ticket);
        if (entry == null) {
            return null;
        }
        if (Instant.now().getEpochSecond() > entry.expiry) {
            log.warn("[WsTicketService] ticket expired on consume, userId={}", entry.userId);
            return null;
        }
        return entry.userId;
    }

    private void evictExpired() {
        long now = Instant.now().getEpochSecond();
        tickets.entrySet().removeIf(e -> e.getValue().expiry < now);
    }

    private record Entry(long userId, long expiry) {
    }
}
