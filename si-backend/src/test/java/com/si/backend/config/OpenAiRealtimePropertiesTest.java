package com.si.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRealtimePropertiesTest {

    @Test
    void defaultsToDisabledAndNotUsable() {
        OpenAiRealtimeProperties properties = new OpenAiRealtimeProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.isUsable());
    }

    @Test
    void enabledWithoutApiKeyIsNotUsable() {
        OpenAiRealtimeProperties properties = bind(Map.of(
                "openai.realtime.enabled", "true",
                "openai.realtime.api-key", " "
        ));

        assertTrue(properties.isEnabled());
        assertFalse(properties.isUsable());
    }

    @Test
    void bindsRealtimeApiKeyAndModelSettings() {
        OpenAiRealtimeProperties properties = bind(Map.of(
                "openai.realtime.enabled", "true",
                "openai.realtime.api-key", "test-realtime-key",
                "openai.realtime.model", "gpt-realtime-translate",
                "openai.realtime.input-transcription-model", "gpt-realtime-whisper",
                "openai.realtime.flush-delay-ms", "250"
        ));

        assertTrue(properties.isUsable());
        assertEquals("test-realtime-key", properties.getApiKey());
        assertEquals("gpt-realtime-translate", properties.getModel());
        assertEquals("gpt-realtime-whisper", properties.getInputTranscriptionModel());
        assertEquals(250L, properties.getFlushDelayMs());
    }

    private static OpenAiRealtimeProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bind("openai.realtime", Bindable.of(OpenAiRealtimeProperties.class))
                .orElseGet(OpenAiRealtimeProperties::new);
    }
}
