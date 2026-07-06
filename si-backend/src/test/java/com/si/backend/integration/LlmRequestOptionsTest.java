package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.config.OpenAiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRequestOptionsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildTextRequestJsonCanDisableReasoningForOpenRouter() throws Exception {
        LlmIntegration llmIntegration = new LlmIntegration(new OpenAiProperties());

        String json = llmIntegration.buildTextRequestJson(
                "anthropic/claude-haiku-4.5",
                "system",
                "user",
                100L,
                new LlmIntegration.ChatRequestOptions(true));

        JsonNode root = objectMapper.readTree(json);
        assertEquals("anthropic/claude-haiku-4.5", root.path("model").asText());
        assertFalse(root.path("stream").asBoolean());
        assertEquals(100L, root.path("max_tokens").asLong());
        assertEquals("none", root.path("reasoning").path("effort").asText());
        assertTrue(root.path("reasoning").path("exclude").asBoolean());
        assertFalse(root.path("include_reasoning").asBoolean());
    }

    @Test
    void effectiveExtractionModelFallsBackToCompressionModel() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setExtractionModel(" ");
        properties.setCompressionModel("test/non-reasoning");

        assertEquals("test/non-reasoning", properties.effectiveExtractionModel());
    }

    @Test
    void indonesianCompressionPromptAllowsSafeRewriteAndUsesConfiguredRatio() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setCompressionZhToIdTargetRatio(0.6);
        LlmIntegration llmIntegration = new LlmIntegration(properties);

        String prompt = llmIntegration.buildIndonesianCompressionPrompt();

        assertTrue(prompt.contains("real-time Indonesian interpretation editor"));
        assertTrue(prompt.contains("Rewrite the already-translated Indonesian text"));
        assertTrue(prompt.contains("Merge duplicated ideas into one concise clause"));
        assertTrue(prompt.contains("Preserve all names, organizations, systems, places, numbers"));
        assertTrue(prompt.contains("60%"));
        assertFalse(prompt.contains("Do NOT rephrase or rewrite the original meaning"));
    }

    @Test
    void compressionMinTextLengthDefaultsToFortyCharacters() {
        assertEquals(40, new OpenAiProperties().getCompressionMinTextLength());
    }
}
