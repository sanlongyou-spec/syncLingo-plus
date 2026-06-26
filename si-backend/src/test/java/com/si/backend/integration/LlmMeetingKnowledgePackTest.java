package com.si.backend.integration;

import com.si.backend.config.OpenAiProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMeetingKnowledgePackTest {

    @Test
    void extractMeetingKnowledgePackKeepsSuccessfulChunksWhenOneChunkFails() throws Exception {
        TestLlmIntegration llmIntegration = new TestLlmIntegration(
                "topic: east line\nterm: pupuk boron",
                new IOException("LLM response content is empty"),
                "topic: west line\ntopic: east line"
        );

        String result = llmIntegration.extractMeetingKnowledgePack("a".repeat(25_000));

        assertTrue(result.contains("topic: east line"));
        assertTrue(result.contains("term: pupuk boron"));
        assertTrue(result.contains("topic: west line"));
        assertEquals(result.indexOf("topic: east line"), result.lastIndexOf("topic: east line"));
    }

    @Test
    void extractMeetingKnowledgePackReturnsEmptyWhenEveryChunkFails() throws Exception {
        TestLlmIntegration llmIntegration = new TestLlmIntegration(
                new IOException("empty one"),
                new IOException("empty two")
        );

        String result = llmIntegration.extractMeetingKnowledgePack("a".repeat(13_000));

        assertEquals("", result);
    }

    private static final class TestLlmIntegration extends LlmIntegration {
        private final Queue<Object> responses = new ArrayDeque<>();

        private TestLlmIntegration(Object... responses) {
            super(new OpenAiProperties());
            this.responses.addAll(List.of(responses));
        }

        @Override
        String extractMeetingKnowledgeChunk(String chunk) throws IOException {
            Object response = responses.remove();
            if (response instanceof IOException ioException) {
                throw ioException;
            }
            return (String) response;
        }
    }
}
