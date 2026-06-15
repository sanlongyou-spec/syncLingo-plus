package com.si.backend.util;

import com.si.backend.common.BizException;
import com.si.backend.security.AuthenticatedActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0.5a 本人资源守卫:无主体→401、不一致→403、缺省→返回 actor。
 */
class AuthContextTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindActor(Long authId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (authId != null) {
            req.setAttribute("authenticatedUserId", authId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void noContext_throwsUnauthorized() {
        // 无请求上下文(异步/未认证)→ 拒绝
        BizException e = assertThrows(BizException.class, () -> AuthContext.requireSelf(5L));
        assertEquals(401, e.getCode());
    }

    @Test
    void noAuthIdInContext_throwsUnauthorized() {
        bindActor(null);
        BizException e = assertThrows(BizException.class, () -> AuthContext.requireSelf(5L));
        assertEquals(401, e.getCode());
    }

    @Test
    void mismatch_throwsForbidden() {
        bindActor(5L);
        BizException e = assertThrows(BizException.class, () -> AuthContext.requireSelf(9L));
        assertEquals(403, e.getCode());
    }

    @Test
    void match_returnsAuthId() {
        bindActor(5L);
        assertEquals(5L, AuthContext.requireSelf(5L));
    }

    @Test
    void nullRequested_returnsAuthId() {
        // 请求未带 userId → 由服务端推导
        bindActor(7L);
        assertEquals(7L, AuthContext.requireSelf(null));
    }

    @Test
    void requireActor_returnsExplicitActor() {
        bindActor(8L);
        assertEquals(new AuthenticatedActor(8L), AuthContext.requireActor());
    }

    @Test
    void explicitActorMismatch_throwsForbidden() {
        BizException e = assertThrows(
                BizException.class,
                () -> AuthContext.requireSelf(new AuthenticatedActor(5L), 9L)
        );
        assertEquals(403, e.getCode());
    }
}
