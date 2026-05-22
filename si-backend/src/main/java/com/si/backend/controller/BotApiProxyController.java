package com.si.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * Transparent reverse-proxy for the C# Teams Calling Bot service.
 * All requests to /bot-api/** are forwarded to the bot (default :3978),
 * stripping the /bot-api prefix.
 */
@Slf4j
@RestController
@RequestMapping("/bot-api")
public class BotApiProxyController {

    @Value("${bot.api.url:http://localhost:3978}")
    private String botApiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {

        String path = request.getRequestURI().substring("/bot-api".length());
        String query = request.getQueryString();
        String targetUrl = botApiUrl + path + (query != null ? "?" + query : "");

        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!"host".equalsIgnoreCase(name)) {
                headers.set(name, request.getHeader(name));
            }
        }

        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        log.debug("[BotApiProxy] {} {} -> {}", method, request.getRequestURI(), targetUrl);

        try {
            return restTemplate.exchange(targetUrl, method, entity, byte[].class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsByteArray());
        }
    }
}
