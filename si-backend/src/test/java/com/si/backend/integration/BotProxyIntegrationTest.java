package com.si.backend.integration;

import com.si.backend.config.BotApiProxyProperties;
import com.si.backend.config.ServiceSignatureProperties;
import com.si.backend.security.ServiceSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4 下行签名:配置下行密钥时为转发请求附加 HMAC 头;未配置时保持兼容不签名。
 */
class BotProxyIntegrationTest {

    private final BotApiProxyProperties botProps = new BotApiProxyProperties();
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ServiceSignatureProperties sigProps = new ServiceSignatureProperties();
    private final ServiceSignature signature = new ServiceSignature(sigProps);

    private BotProxyIntegration newIntegration() {
        botProps.setUrl("http://localhost:3978");
        return new BotProxyIntegration(botProps, restTemplate, signature, sigProps);
    }

    @SuppressWarnings("unchecked")
    private HttpHeaders forwardAndCaptureHeaders() {
        when(restTemplate.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[0]));
        newIntegration().forward("/api/meetings/summary", HttpMethod.POST, new HttpHeaders(), "{}".getBytes());
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), entity.capture(), eq(byte[].class));
        return entity.getValue().getHeaders();
    }

    @Test
    void withDownstreamKey_addsSignatureHeaders() {
        sigProps.setDownstreamKey("downstream_key_at_least_32_chars_long_xx");
        HttpHeaders headers = forwardAndCaptureHeaders();
        assertNotNull(headers.getFirst(ServiceSignature.HEADER_SIGNATURE));
        assertTrue(signature.hasSignatureHeaders(headers.toSingleValueMap()));
    }

    @Test
    void withoutDownstreamKey_doesNotSign() {
        sigProps.setDownstreamKey("");
        HttpHeaders headers = forwardAndCaptureHeaders();
        assertFalse(headers.containsKey(ServiceSignature.HEADER_SIGNATURE));
    }
}
