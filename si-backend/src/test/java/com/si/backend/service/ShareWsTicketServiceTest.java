package com.si.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void audioTicket_normalizesLanguageAndConsumesOnce() {
        ShareWsTicketService.Issued issued = service.issueAudioTicket("s-2", "id-ID");
        assertNotNull(issued.ticket());

        ShareWsTicketService.Entry entry = service.consume(issued.ticket());
        assertEquals("s-2", entry.sessionId());
        assertEquals("id", entry.lang());
        assertNull(service.consume(issued.ticket()));
    }

    @Test
    void audioTicket_rejectsInvalidLanguage() {
        assertThrows(com.si.backend.common.BizException.class, () -> service.issueAudioTicket("s-3", "fr-FR"));
        assertThrows(com.si.backend.common.BizException.class, () -> service.issueAudioTicket("s-3", ""));
    }

    @Test
    void blankTicket_returnsNull() {
        assertNull(service.consume(""));
        assertNull(service.consume(null));
        assertNull(service.consume("not-issued"));
    }
}
