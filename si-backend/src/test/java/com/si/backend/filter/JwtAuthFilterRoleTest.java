package com.si.backend.filter;

import com.si.backend.config.JwtProperties;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 JwtAuthFilter:加载角色/状态;停用→403;未知用户→401;DB 异常降级不锁人。
 */
class JwtAuthFilterRoleTest {

    private static final String SECRET = "unit-test-secret-key-0123456789-abcdefgh";

    private final UserMapper userMapper = mock(UserMapper.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtProps(), userMapper);

    private JwtProperties jwtProps() {
        JwtProperties p = new JwtProperties();
        p.setSecret(SECRET);
        return p;
    }

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/meetings");
        req.addHeader("Authorization", "Bearer " + JwtUtil.createToken(5L, "u", 600000, SECRET));
        return req;
    }

    private SiUser user(String role, String status) {
        SiUser u = new SiUser();
        u.setId(5L);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    void activeUser_setsRole_andProceeds() throws Exception {
        when(userMapper.findById(5L)).thenReturn(user("OPERATOR", "ACTIVE"));
        MockHttpServletRequest req = authedRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertNotNull(chain.getRequest(), "应放行到下游");
        assertEquals("OPERATOR", req.getAttribute("authenticatedRole"));
        assertEquals(5L, req.getAttribute("authenticatedUserId"));
    }

    @Test
    void disabledUser_rejected403() throws Exception {
        when(userMapper.findById(5L)).thenReturn(user("OPERATOR", "DISABLED"));
        MockHttpServletRequest req = authedRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertEquals(403, res.getStatus());
        assertNull(chain.getRequest(), "停用账号不应放行");
    }

    @Test
    void unknownUser_rejected401() throws Exception {
        when(userMapper.findById(5L)).thenReturn(null);
        MockHttpServletRequest req = authedRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void dbError_degradesButProceeds() throws Exception {
        when(userMapper.findById(5L)).thenThrow(new RuntimeException("db down"));
        MockHttpServletRequest req = authedRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertNotNull(chain.getRequest(), "DB 异常不应锁人,降级放行");
        assertEquals(5L, req.getAttribute("authenticatedUserId"));
        assertNull(req.getAttribute("authenticatedRole"));
    }
}
