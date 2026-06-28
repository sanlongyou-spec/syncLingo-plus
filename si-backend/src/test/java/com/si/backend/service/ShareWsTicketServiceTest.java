package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Anonymous share WS tickets: short-lived, one-time credentials bound to a shared session.
 */
class ShareWsTicketServiceTest {

    private final ShareWsTicketService service = new ShareWsTicketService();

    @Test
    void textTicket_consumesOnce() {
        ShareWsTicketService.Issued issued = service.issueTextTicket("s-1");
        assertNotNull(issued.ticket());

        ShareWsTicketService.Entry entry = service.consume(issued.ticket());
        assertEquals("s-1", entry.sessionId());
        assertNull(entry.lang());
        assertNull(service.consume(issued.ticket()));
    }

    @Test
    void blankTicket_returnsNull() {
        assertNull(service.consume(""));
        assertNull(service.consume(null));
        assertNull(service.consume("not-issued"));
    }
}
