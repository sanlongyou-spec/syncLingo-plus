package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.security.AnonymousRequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies anonymous compatibility endpoints use server-observed addresses and strict latency fields.
 */
class PublicInterpretationEndpointSecurityTest {

    private final InterpretationFacade facade = mock(InterpretationFacade.class);
    private final AnonymousRequestRateLimiter limiter = mock(AnonymousRequestRateLimiter.class);
    private final InterpretationController controller = new InterpretationController(facade, limiter);

    @Test
    void activeLookup_ignoresSpoofedForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        controller.getActiveSessionForUser(7L, request);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(limiter).requireAllowed(eq("public-active"), key.capture(), anyInt(), anyLong());
        assertEquals("10.0.0.8:7", key.getValue());
    }

    @Test
    void latencyWithUnknownField_isRejectedBeforeLoggingOrRateLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");

        BizException error = assertThrows(
                BizException.class,
                () -> controller.reportLatency(Map.of("sessionId", "s1", "token", "secret"), request)
        );

        assertEquals(400, error.getCode());
        verifyNoInteractions(limiter);
    }

    @Test
    void oversizedLatencyBody_isRejectedBeforeLoggingOrRateLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.setContent(new byte[2_049]);

        BizException error = assertThrows(
                BizException.class,
                () -> controller.reportLatency(Map.of("sessionId", "s1"), request)
        );

        assertEquals(400, error.getCode());
        verifyNoInteractions(limiter);
    }
}
