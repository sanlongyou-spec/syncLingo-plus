package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.security.AnonymousRequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies anonymous compatibility endpoints are sunset where needed and still validate latency fields.
 */
class PublicInterpretationEndpointSecurityTest {

    private final InterpretationFacade facade = mock(InterpretationFacade.class);
    private final AnonymousRequestRateLimiter limiter = mock(AnonymousRequestRateLimiter.class);
    private final com.si.backend.service.ShareTokenService shareTokenService =
            mock(com.si.backend.service.ShareTokenService.class);
    private final InterpretationController controller = new InterpretationController(facade, limiter, shareTokenService);

    @Test
    void legacyActiveLookup_isGoneWithoutResolvingUserSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        BizException error = assertThrows(
                BizException.class,
                () -> controller.getActiveSessionForUser(7L, request)
        );

        assertEquals(410, error.getCode());
        verifyNoInteractions(facade, limiter);
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
