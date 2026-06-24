package com.si.backend.integration;

import com.si.backend.config.BotApiProxyProperties;
import com.si.backend.config.ServiceSignatureProperties;
import com.si.backend.security.ServiceSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Calls the fixed C# Bot target for the temporary Java reverse proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotProxyIntegration {

    /** 下行签名的 keyId,C# 侧用它选择对应的验签密钥。 */
    private static final String DOWNSTREAM_KEY_ID = "java-backend";

    private final BotApiProxyProperties properties;
    private final RestTemplate restTemplate;
    private final ServiceSignature serviceSignature;
    private final ServiceSignatureProperties signatureProperties;

    public ResponseEntity<byte[]> forward(
            String path,
            HttpMethod method,
            HttpHeaders headers,
            byte[] body
    ) {
        String targetUrl = normalizedBaseUrl() + path;
        applyDownstreamSignature(headers, method, path, body);
        long start = System.currentTimeMillis();
        log.info("[BotProxyIntegration] forward start, method={}, path={}", method, path);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    targetUrl,
                    method,
                    new HttpEntity<>(body, headers),
                    byte[].class
            );
            log.info("[BotProxyIntegration] forward end, method={}, path={}, status={}, costMs={}",
                    method, path, response.getStatusCode().value(), System.currentTimeMillis() - start);
            return cleanResponse(response.getStatusCode(), response.getHeaders().getContentType(), response.getBody());
        } catch (HttpStatusCodeException error) {
            log.warn("[BotProxyIntegration] forward rejected by bot, method={}, path={}, status={}, costMs={}",
                    method, path, error.getStatusCode().value(), System.currentTimeMillis() - start);
            return cleanResponse(error.getStatusCode(),
                    error.getResponseHeaders() != null ? error.getResponseHeaders().getContentType() : null,
                    error.getResponseBodyAsByteArray());
        }
    }

    /**
     * 只保留状态码、Content-Type 和响应体，丢弃上游(C# Bot)的逐跳头
     * (Content-Length / Transfer-Encoding / Connection / Date / Server 等)。
     * 否则把这些原样转发给浏览器会导致响应体被破坏(前端表现为 Network Error)。
     * Content-Length 由 Spring 按实际 body 重新计算。
     */
    private ResponseEntity<byte[]> cleanResponse(HttpStatusCode status, MediaType contentType, byte[] body) {
        HttpHeaders safe = new HttpHeaders();
        if (contentType != null) {
            safe.setContentType(contentType);
        }
        return ResponseEntity.status(status).headers(safe).body(body);
    }

    /** 配置了下行密钥时对转发请求签名;未配置则跳过(本地/灰度,C# 兼容忽略)。 */
    private void applyDownstreamSignature(HttpHeaders headers, HttpMethod method, String path, byte[] body) {
        String key = signatureProperties.getDownstreamKey();
        if (key == null || key.isBlank()) {
            return;
        }
        Map<String, String> signed = serviceSignature.sign(
                key, DOWNSTREAM_KEY_ID, method.name(), path, "", body);
        signed.forEach(headers::set);
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.getUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
