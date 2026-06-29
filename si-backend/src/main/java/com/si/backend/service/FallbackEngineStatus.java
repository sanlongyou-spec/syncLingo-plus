package com.si.backend.service;

public record FallbackEngineStatus(
        String sessionId,
        String activeEngine,
        boolean available,
        String message
) {
}
