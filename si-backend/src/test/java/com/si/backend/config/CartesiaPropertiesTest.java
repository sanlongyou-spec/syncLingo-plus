package com.si.backend.config;

import com.si.backend.common.Constants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CartesiaPropertiesTest {

    @Test
    void defaultsToSonic35WhenModelIsNotConfigured() {
        CartesiaProperties properties = new CartesiaProperties();

        assertEquals(Constants.CARTESIA_TTS_MODEL, properties.getTts().getModelId());
        assertEquals("sonic-3.5", properties.getTts().getModelId());
    }

    @Test
    void bindsLegacyModelPropertyToEffectiveModelId() {
        CartesiaProperties properties = bind(Map.of("cartesia.tts.model", "sonic-latest"));

        assertEquals("sonic-latest", properties.getTts().getModelId());
    }

    @Test
    void modelIdPropertyOverridesLegacyModelProperty() {
        CartesiaProperties properties = bind(Map.of(
                "cartesia.tts.model", "sonic-latest",
                "cartesia.tts.model-id", "sonic-3.5"
        ));

        assertEquals("sonic-3.5", properties.getTts().getModelId());
    }

    private static CartesiaProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bind("cartesia", Bindable.of(CartesiaProperties.class)).orElseGet(CartesiaProperties::new);
    }
}
