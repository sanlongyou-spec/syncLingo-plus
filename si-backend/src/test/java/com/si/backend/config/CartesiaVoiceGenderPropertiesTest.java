package com.si.backend.config;

import com.si.backend.config.CartesiaProperties.VoiceGenderTtsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 校验性别×语种音色选择：中文用中文音色，未配置语种音色时回退到全局音色。
 */
class CartesiaVoiceGenderPropertiesTest {

    @Test
    void languageSpecificVoiceWinsOverGlobal() {
        VoiceGenderTtsProperties props = new VoiceGenderTtsProperties();
        props.setMaleVoiceId("global-male");
        props.setFemaleVoiceId("global-female");
        props.setZhMaleVoiceId("zh-male");
        props.setZhFemaleVoiceId("zh-female");

        assertEquals("zh-male", props.maleVoiceIdForLanguage("zh"));
        assertEquals("zh-female", props.femaleVoiceIdForLanguage("zh"));
    }

    @Test
    void fallsBackToGlobalWhenLanguageVoiceMissing() {
        VoiceGenderTtsProperties props = new VoiceGenderTtsProperties();
        props.setMaleVoiceId("global-male");
        props.setFemaleVoiceId("global-female");
        // en/id 未配置语种音色

        assertEquals("global-male", props.maleVoiceIdForLanguage("en"));
        assertEquals("global-female", props.femaleVoiceIdForLanguage("id"));
        assertEquals("global-male", props.maleVoiceIdForLanguage(null));
    }

    @Test
    void blankLanguageVoiceFallsBackToGlobal() {
        VoiceGenderTtsProperties props = new VoiceGenderTtsProperties();
        props.setMaleVoiceId("global-male");
        props.setZhMaleVoiceId("   ");

        assertEquals("global-male", props.maleVoiceIdForLanguage("zh"));
    }

    @Test
    void noVoiceConfiguredReturnsNull() {
        VoiceGenderTtsProperties props = new VoiceGenderTtsProperties();
        assertNull(props.maleVoiceIdForLanguage("zh"));
        assertNull(props.femaleVoiceIdForLanguage("en"));
    }
}
