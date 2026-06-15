package com.si.backend.integration;

import com.si.backend.config.BotApiProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Calls the fixed C# Bot target for the temporary Java reverse proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotProxyIntegration {

    private final BotApiProxyProperties properties;
    private final RestTemplate restTemplate;

    public ResponseEntity<byte[]> forward(
            String path,
            HttpMethod method,
            HttpHeaders headers,
            byte[] body
    ) {
        String targetUrl = normalizedBaseUrl() + path;
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
            return response;
        } catch (HttpStatusCodeException error) {
            log.warn("[BotProxyIntegration] forward rejected by bot, method={}, path={}, status={}, costMs={}",
                    method, path, error.getStatusCode().value(), System.currentTimeMillis() - start);
            return ResponseEntity.status(error.getStatusCode())
                    .headers(error.getResponseHeaders())
                    .body(error.getResponseBodyAsByteArray());
        }
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.getUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
