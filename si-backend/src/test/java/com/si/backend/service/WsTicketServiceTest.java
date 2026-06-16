package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P5 WS 一次性票据:签发可消费一次、重复/未知/空票据被拒、不同票据互不影响。
 */
class WsTicketServiceTest {

    private final WsTicketService service = new WsTicketService();

    @Test
    void issuedTicket_consumesOnce() {
        String ticket = service.issue(42L);
        assertEquals(42L, service.consume(ticket));
        // 一次性:再次消费返回 null。
        assertNull(service.consume(ticket));
    }

    @Test
    void unknownOrBlankTicket_returnsNull() {
        assertNull(service.consume("never-issued"));
        assertNull(service.consume(""));
        assertNull(service.consume(null));
    }

    @Test
    void distinctTickets_areIndependentAndUnique() {
        String a = service.issue(1L);
        String b = service.issue(2L);
        assertNotEquals(a, b);
        assertEquals(2L, service.consume(b));
        // 消费 b 不影响 a。
        assertEquals(1L, service.consume(a));
    }
}
