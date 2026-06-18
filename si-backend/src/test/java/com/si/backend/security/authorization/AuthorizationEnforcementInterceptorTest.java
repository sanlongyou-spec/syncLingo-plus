package com.si.backend.security.authorization;

import com.si.backend.common.BizException;
import com.si.backend.config.AuthorizationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 角色功能权限执行:REPORT_ONLY 不拦截;ENFORCE 越权抛 403;授权通过;非 USER/未声明跳过。
 */
class AuthorizationEnforcementInterceptorTest {

    @SuppressWarnings("unused")
    static class DummyController {
        @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.MEETING_MANAGE,
                scope = ResourceScope.OWN, expectedStatuses = {200})
        public void userMeeting() { }

        @AuthorizationSpec(identity = IdentityType.ANONYMOUS, permission = PermissionCode.HEALTH_READ,
                scope = ResourceScope.PUBLIC, expectedStatuses = {200})
        public void anon() { }

        public void noSpec() { }
    }

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(), DummyController.class.getMethod(method));
    }

    private void bindRole(String role) {
        request.setAttribute("authenticatedRole", role);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AuthorizationEnforcementInterceptor interceptor(AuthorizationMode mode) {
        AuthorizationProperties props = new AuthorizationProperties();
        props.setMode(mode);
        return new AuthorizationEnforcementInterceptor(props);
    }

    @Test
    void authorizationProperties_defaultModeIsEnforce() {
        AuthorizationProperties props = new AuthorizationProperties();
        assertEquals(AuthorizationMode.ENFORCE, props.getMode());
    }

    @Test
    void reportOnly_neverBlocks_evenWhenDenied() throws Exception {
        bindRole("VIEWER"); // VIEWER 无 MEETING_MANAGE
        assertTrue(interceptor(AuthorizationMode.REPORT_ONLY).preHandle(request, response, handler("userMeeting")));
    }

    @Test
    void enforce_deniesWhenRoleLacksPermission() throws Exception {
        bindRole("VIEWER");
        BizException e = assertThrows(BizException.class,
                () -> interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, handler("userMeeting")));
        assertEquals(403, e.getCode());
    }

    @Test
    void enforce_allowsWhenRoleGrants() throws Exception {
        bindRole("OPERATOR"); // OPERATOR 有 MEETING_MANAGE
        assertTrue(interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, handler("userMeeting")));
    }

    @Test
    void enforce_deniesAdminOnBusinessPermission() throws Exception {
        bindRole("ADMIN");
        BizException e = assertThrows(BizException.class,
                () -> interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, handler("userMeeting")));
        assertEquals(403, e.getCode());
    }

    @Test
    void enforce_deniesWhenNoRole() throws Exception {
        bindRole(null); // role 未知(如旧 token / 未回填)
        assertThrows(BizException.class,
                () -> interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, handler("userMeeting")));
    }

    @Test
    void nonUserIdentity_skipped() throws Exception {
        bindRole(null);
        assertTrue(interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, handler("anon")));
    }

    @Test
    void noSpec_skipped() throws Exception {
        bindRole(null);
        assertTrue(interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, handler("noSpec")));
    }

    @Test
    void nonHandlerMethod_skipped() {
        assertTrue(interceptor(AuthorizationMode.ENFORCE).preHandle(request, response, new Object()));
    }
}
