package com.si.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Cartesia stream routing guards that keep reused WebSocket contexts isolated.
 */
class CartesiaStreamingIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messageContextMatchesCurrentGenerationOnly() throws Exception {
        assertTrue(CartesiaStreamingIntegration.CartesiaWsClient.messageContextMatches(
                objectMapper.readTree("{\"type\":\"chunk\",\"context_id\":\"ctx-1\"}"),
                "ctx-1"));

        assertFalse(CartesiaStreamingIntegration.CartesiaWsClient.messageContextMatches(
                objectMapper.readTree("{\"type\":\"done\",\"context_id\":\"ctx-old\"}"),
                "ctx-new"));
    }

    @Test
    void missingContextRemainsCompatibleWithProviderMessages() throws Exception {
        assertTrue(CartesiaStreamingIntegration.CartesiaWsClient.messageContextMatches(
                objectMapper.readTree("{\"type\":\"chunk\"}"),
                "ctx-1"));

        assertTrue(CartesiaStreamingIntegration.CartesiaWsClient.messageContextMatches(
                objectMapper.readTree("{\"type\":\"chunk\",\"context_id\":\"ctx-1\"}"),
                null));
    }
}
