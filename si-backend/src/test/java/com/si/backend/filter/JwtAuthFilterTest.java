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

/**
 * Verifies protected and public path behavior after the Bot proxy is removed from the whitelist.
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac";

    @Test
    void botProxyWithoutToken_isRejected() throws Exception {
        JwtAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bot-api/api/meetings/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void publicInterpretationPath_remainsPublic() throws Exception {
        JwtAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/interpretation/public/user/5/active"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNull(request.getAttribute("authenticatedUserId"));
    }

    @Test
    void actuatorHealth_remainsPublic() throws Exception {
        JwtAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void nonHealthActuatorEndpoint_requiresToken() throws Exception {
        JwtAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void botProxyWithValidToken_bindsUserId() throws Exception {
        JwtAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bot-api/api/meetings/summary");
        request.addHeader("Authorization", "Bearer " + JwtUtil.createToken(7L, "operator", 0, 60_000L, SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals(7L, request.getAttribute("authenticatedUserId"));
    }

    private JwtAuthFilter filter() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        UserMapper userMapper = org.mockito.Mockito.mock(UserMapper.class);
        SiUser active = new SiUser();
        active.setStatus("ACTIVE");
        org.mockito.Mockito.when(userMapper.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(active);
        return new JwtAuthFilter(properties, userMapper);
    }
}
