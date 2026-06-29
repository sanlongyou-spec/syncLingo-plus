package com.si.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.config.CartesiaProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void bytesEndpointUsesHttpBaseUrlAndFullTranscriptPayload() {
        CartesiaProperties properties = new CartesiaProperties();
        properties.setApiUrl("wss://api.cartesia.ai/");
        properties.getTts().setModelId("sonic-3.5");
        CartesiaStreamingIntegration integration = new CartesiaStreamingIntegration(properties, objectMapper);

        Map<String, Object> request = integration.buildBytesTtsRequest(
                "你好", 24_000, 1.1, "zh", "voice-1");

        assertEquals("https://api.cartesia.ai", integration.httpApiBaseUrl());
        assertEquals("sonic-3.5", request.get(Constants.CARTESIA_FIELD_MODEL_ID));
        assertEquals("你好", request.get(Constants.CARTESIA_FIELD_TRANSCRIPT));
        assertEquals("zh", request.get("language"));
        assertFalse(request.containsKey(Constants.CARTESIA_FIELD_CONTEXT_ID));
        assertFalse(request.containsKey(Constants.CARTESIA_FIELD_CONTINUE));
        assertFalse(request.containsKey(Constants.CARTESIA_FIELD_MAX_BUFFER_DELAY_MS));

        @SuppressWarnings("unchecked")
        Map<String, Object> voice = (Map<String, Object>) request.get(Constants.CARTESIA_FIELD_VOICE);
        assertEquals("id", voice.get("mode"));
        assertEquals("voice-1", voice.get("id"));
    }
}
