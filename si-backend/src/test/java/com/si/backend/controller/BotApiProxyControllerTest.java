package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.integration.BotProxyIntegration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Java-side Teams Bot proxy operation allowlist and sensitive-header stripping.
 * 用户级授权由 @AuthorizationSpec(BOT_OPERATE) 统一把关，代理本身不再维护 userId 白名单。
 */
class BotApiProxyControllerTest {

    private final BotProxyIntegration integration = mock(BotProxyIntegration.class);
    private final BotApiProxyController controller = new BotApiProxyController(integration);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void unknownOperation_isDenied() {
        bindActor(5L);
        HttpServletRequest request = request("POST", "/bot-api/api/meetings/join");

        BizException error = assertThrows(BizException.class, () -> controller.proxy(request, new byte[0]));

        assertEquals(403, error.getCode());
    }

    @Test
    void allowedOperation_forwardsOnlySafeHeaders() {
        bindActor(5L);
        MockHttpServletRequest request = (MockHttpServletRequest) request(
                "POST",
                "/bot-api/api/meetings/summary"
        );
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret-user-token");
        request.addHeader(HttpHeaders.COOKIE, "session=secret");
        request.addHeader("X-Admin-Secret", "admin-secret");
        request.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        when(integration.forward(
                eq("/api/meetings/summary"),
                eq(HttpMethod.POST),
                any(HttpHeaders.class),
                any(byte[].class)
        )).thenReturn(ResponseEntity.ok(new byte[0]));

        controller.proxy(request, "{}".getBytes());

        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(integration).forward(
                eq("/api/meetings/summary"),
                eq(HttpMethod.POST),
                headers.capture(),
                any(byte[].class)
        );
        assertEquals("application/json", headers.getValue().getFirst(HttpHeaders.CONTENT_TYPE));
        assertFalse(headers.getValue().containsKey(HttpHeaders.AUTHORIZATION));
        assertFalse(headers.getValue().containsKey(HttpHeaders.COOKIE));
        assertFalse(headers.getValue().containsKey("X-Admin-Secret"));
    }

    private void bindActor(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedUserId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private HttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
