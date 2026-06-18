package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.Result;
import com.si.backend.dto.LoginRequest;
import com.si.backend.dto.LoginResponse;
import com.si.backend.facade.AuthFacade;
import com.si.backend.security.AnonymousRequestRateLimiter;
import com.si.backend.service.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies public self-registration remains explicitly closed.
 */
class AuthControllerSecurityTest {

    @Test
    void login_setsHttpOnlyRefreshCookie() {
        AuthFacade facade = mock(AuthFacade.class);
        AuthController controller = new AuthController(facade, mock(AnonymousRequestRateLimiter.class));
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("password");
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(facade.login(request, "127.0.0.1")).thenReturn(
                new AuthSessionService.IssuedAuth(new LoginResponse(9L, "access", "OPERATOR"), "refresh"));

        Result<LoginResponse> result = controller.login(request, httpRequest, response);

        assertEquals(200, result.getCode());
        assertEquals("OPERATOR", result.getData().getRole());
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertEquals(true, setCookie.contains(AuthController.REFRESH_COOKIE + "=refresh"));
        assertEquals(true, setCookie.contains("HttpOnly"));
        assertEquals(true, setCookie.contains("SameSite=Strict"));
    }

    @Test
    void refresh_setsRotatedRefreshCookie() {
        AuthFacade facade = mock(AuthFacade.class);
        AuthController controller = new AuthController(facade, mock(AnonymousRequestRateLimiter.class));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(facade.refresh("old-refresh")).thenReturn(
                new AuthSessionService.IssuedAuth(new LoginResponse(9L, "new-access", "ADMIN"), "new-refresh"));

        Result<LoginResponse> result = controller.refresh("old-refresh", new MockHttpServletRequest(), response);

        assertEquals(200, result.getCode());
        assertEquals("new-access", result.getData().getToken());
        assertEquals("ADMIN", result.getData().getRole());
        assertEquals(true, response.getHeader("Set-Cookie").contains(AuthController.REFRESH_COOKIE + "=new-refresh"));
    }

    @Test
    void logout_clearsRefreshCookie() {
        AuthFacade facade = mock(AuthFacade.class);
        AuthController controller = new AuthController(facade, mock(AnonymousRequestRateLimiter.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout("refresh", response);

        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertEquals(true, setCookie.contains(AuthController.REFRESH_COOKIE + "="));
        assertEquals(true, setCookie.contains("Max-Age=0"));
    }

    @Test
    void refresh_rejectsCrossOriginRequest() {
        AuthFacade facade = mock(AuthFacade.class);
        AnonymousRequestRateLimiter limiter = mock(AnonymousRequestRateLimiter.class);
        AuthController controller = new AuthController(facade, limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("app.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://evil.example.com");

        BizException error = assertThrows(BizException.class,
                () -> controller.refresh("refresh", request, new MockHttpServletResponse()));

        assertEquals(403, error.getCode());
        verifyNoInteractions(facade);
        verify(limiter, never()).requireAllowed(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void register_isRejectedWithoutCallingFacade() {
        AuthFacade facade = mock(AuthFacade.class);
        AuthController controller = new AuthController(facade, mock(AnonymousRequestRateLimiter.class));
        LoginRequest request = new LoginRequest();
        request.setUsername("new-user");
        request.setPassword("password");

        BizException error = assertThrows(BizException.class, () -> controller.register(request));

        assertEquals(403, error.getCode());
        verifyNoInteractions(facade);
    }
}
