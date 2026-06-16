package com.si.backend.filter;

import com.si.backend.config.ServiceSignatureProperties;
import com.si.backend.security.ServiceSignature;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P4 上行验签过滤器:路径放行、无密钥跳过、有效签名放行(且体可重复读)、无效签名 401、缺签名默认拒绝、显式迁移期放行。
 */
class TeamsBotSignatureFilterTest {

    private static final String UPSTREAM = "upstream_key_at_least_32_chars_long_xxxx";
    private static final String KEY_ID = "csharp-bot";
    private static final String PATH = "/api/teams-bot/query";

    private final ServiceSignatureProperties props = new ServiceSignatureProperties();
    private final ServiceSignature signature = new ServiceSignature(props);
    private final TeamsBotSignatureFilter filter = new TeamsBotSignatureFilter(props, signature);

    private MockHttpServletRequest botRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setContent(body);
        return request;
    }

    @Test
    void nonBotPath_passesThroughUnchanged() throws Exception {
        props.setUpstreamKey(UPSTREAM);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.setRequestURI("/api/health");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(request, chain.getRequest()); // 原始请求,未包装
    }

    @Test
    void noUpstreamKey_skipsVerification() throws Exception {
        props.setUpstreamKey("");
        MockHttpServletRequest request = botRequest("{\"m\":1}".getBytes());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest()); // 放行
    }

    @Test
    void validSignature_passesAndBodyStillReadable() throws Exception {
        props.setUpstreamKey(UPSTREAM);
        byte[] body = "{\"aadId\":\"x\"}".getBytes();
        MockHttpServletRequest request = botRequest(body);
        Map<String, String> headers = signature.sign(UPSTREAM, KEY_ID, "POST", PATH, "", body);
        headers.forEach(request::addHeader);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertNotNull(forwarded);
        // 下游仍可读到完整请求体
        assertArrayEquals(body, forwarded.getInputStream().readAllBytes());
    }

    @Test
    void invalidSignature_isRejected401_andChainNotCalled() throws Exception {
        props.setUpstreamKey(UPSTREAM);
        byte[] body = "{\"aadId\":\"x\"}".getBytes();
        MockHttpServletRequest request = botRequest(body);
        Map<String, String> headers = signature.sign(UPSTREAM, KEY_ID, "POST", PATH, "", body);
        headers.forEach(request::addHeader);
        // 篡改签名
        request.removeHeader(ServiceSignature.HEADER_SIGNATURE);
        request.addHeader(ServiceSignature.HEADER_SIGNATURE, "tampered");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest()); // 未放行
    }

    @Test
    void missingSignature_isRejectedWhenRequired() throws Exception {
        props.setUpstreamKey(UPSTREAM);
        MockHttpServletRequest request = botRequest("{\"m\":1}".getBytes()); // 无签名头
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void missingSignatureDuringExplicitMigration_isAllowed() throws Exception {
        props.setUpstreamKey(UPSTREAM);
        props.setUpstreamRequired(false);
        MockHttpServletRequest request = botRequest("{\"m\":1}".getBytes()); // 无签名头
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest()); // 放行(由静态 api-secret 兜底)
    }
}
