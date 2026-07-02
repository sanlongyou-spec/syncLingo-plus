package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues short-lived, one-time WebSocket tickets for anonymous share listeners.
 */
@Slf4j
@Service
public class ShareWsTicketService {

    static final long TICKET_TTL_SECONDS = 60;
    private static final int TICKET_BYTES = 32;
    private static final int MAX_ENTRIES = 100_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> tickets = new ConcurrentHashMap<>();

    public Issued issueTextTicket(String sessionId) {
        return issue(sessionId, null);
    }

    public Issued issueAudioTicket(String sessionId, String lang) {
        String normalized = com.si.backend.ws.ShareAudioWebSocketHandler.normalizeLang(lang);
        if (normalized == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Invalid audio language");
        }
        return issue(sessionId, normalized);
    }

    public Entry consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Entry entry = tickets.remove(ticket);
        if (entry == null) {
            return null;
        }
        if (Instant.now().getEpochSecond() > entry.expiryEpochSecond()) {
            log.warn("[ShareWsTicketService] ticket expired, sessionId={}", entry.sessionId());
            return null;
        }
        return entry;
    }

    private Issued issue(String sessionId, String lang) {
        if (sessionId == null || sessionId.isBlank()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "No active share session");
        }
        evictExpired();
        if (tickets.size() >= MAX_ENTRIES) {
            log.warn("[ShareWsTicketService] ticket store full, size={}", tickets.size());
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "Share WS tickets are temporarily unavailable");
        }
        byte[] raw = new byte[TICKET_BYTES];
        secureRandom.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long expiry = Instant.now().getEpochSecond() + TICKET_TTL_SECONDS;
        tickets.put(ticket, new Entry(sessionId, lang, expiry));
        log.info("[ShareWsTicketService] issued ticket, sessionId={}, lang={}", sessionId, lang);
        return new Issued(ticket);
    }

    private void evictExpired() {
        long now = Instant.now().getEpochSecond();
        tickets.entrySet().removeIf(e -> e.getValue().expiryEpochSecond() < now);
    }

    public record Issued(String ticket) {
    }

    public record Entry(String sessionId, String lang, long expiryEpochSecond) {
    }
}
