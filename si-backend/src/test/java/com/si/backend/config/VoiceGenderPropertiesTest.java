package com.si.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoiceGenderPropertiesTest {

    @Test
    void defaultTimeoutLeavesRoomForLocalModelInference() {
        VoiceGenderProperties properties = new VoiceGenderProperties();

        assertEquals(3000, properties.getTimeoutMs());
    }
}
