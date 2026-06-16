package com.si.backend.filter;

import com.si.backend.config.InternalOpsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Internal operations endpoints are guarded by a dedicated secret and optional IP allowlist.
 */
class InternalOpsAuthFilterTest {

    private static final String PATH = "/api/internal/ops/audit-outbox/dispatch";

    private final InternalOpsProperties properties = new InternalOpsProperties();
    private final InternalOpsAuthFilter filter = new InternalOpsAuthFilter(properties);

    @Test
    void nonOpsPath_passesThrough() throws Exception {
        properties.setApiSecret("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/health");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void missingConfiguredSecret_rejects() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(opsRequest("secret", "127.0.0.1"), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void validSecretAndAllowedIp_passesThrough() throws Exception {
        properties.setApiSecret("secret");
        properties.setAllowedIps(List.of("127.0.0.1"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(opsRequest("secret", "127.0.0.1"), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void invalidSecret_rejects() throws Exception {
        properties.setApiSecret("secret");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(opsRequest("wrong", "127.0.0.1"), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void deniedIp_rejects() throws Exception {
        properties.setApiSecret("secret");
        properties.setAllowedIps(List.of("10.0.0.1"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(opsRequest("secret", "127.0.0.1"), response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    private MockHttpServletRequest opsRequest(String secret, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setRemoteAddr(remoteAddr);
        if (secret != null) {
            request.addHeader(InternalOpsAuthFilter.HEADER_SECRET, secret);
        }
        return request;
    }
}
