package com.si.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0 安全配置启动校验:生产拒绝弱密钥,dev/test 放行。
 */
class SecurityConfigValidatorTest {

    private static final String STRONG_SECRET = "a-very-strong-production-secret-key-0123456789"; // > 32 bytes

    private Environment env(String[] profiles, String adminSecret, String botSecret) {
        Environment e = mock(Environment.class);
        when(e.getActiveProfiles()).thenReturn(profiles);
        when(e.getProperty("app.admin.api-secret")).thenReturn(adminSecret);
        when(e.getProperty("audio.record.api-secret")).thenReturn(botSecret);
        when(e.getProperty("spring.datasource.password")).thenReturn("strong-database-password");
        return e;
    }

    private JwtProperties jwt(String secret) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        return p;
    }

    private SecurityConfigValidator validator(Environment e, String secret) {
        return new SecurityConfigValidator(e, jwt(secret));
    }

    @Test
    void prod_withDefaultSecret_abortsStartup() {
        SecurityConfigValidator v = validator(
                env(new String[]{"prod"}, "real-admin", "real-bot"),
                SecurityConfigValidator.DEFAULT_JWT_SECRET);
        assertThrows(IllegalStateException.class, v::validate);
    }

    @Test
    void prod_withBlankSecret_abortsStartup() {
        SecurityConfigValidator v = validator(env(new String[]{"prod"}, "real-admin", "real-bot"), "  ");
        assertThrows(IllegalStateException.class, v::validate);
    }

    @Test
    void prod_withShortSecret_abortsStartup() {
        SecurityConfigValidator v = validator(env(new String[]{"prod"}, "real-admin", "real-bot"), "tooshort");
        assertThrows(IllegalStateException.class, v::validate);
    }

    @Test
    void prod_withBlankAdminSecret_abortsStartup() {
        SecurityConfigValidator v = validator(env(new String[]{"prod"}, "", "real-bot"), STRONG_SECRET);
        assertThrows(IllegalStateException.class, v::validate);
    }

    @Test
    void prod_withBlankBotSecret_abortsStartup() {
        SecurityConfigValidator v = validator(env(new String[]{"prod"}, "real-admin", null), STRONG_SECRET);
        assertThrows(IllegalStateException.class, v::validate);
    }

    @Test
    void prod_withDefaultDatabasePassword_abortsStartup() {
        Environment environment = env(new String[]{"prod"}, "real-admin", "real-bot");
        when(environment.getProperty("spring.datasource.password")).thenReturn("ysl666");
        SecurityConfigValidator validator = validator(environment, STRONG_SECRET);
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void prod_withAllStrong_startsUp() {
        SecurityConfigValidator v = validator(env(new String[]{"prod"}, "real-admin", "real-bot"), STRONG_SECRET);
        assertDoesNotThrow(v::validate);
    }

    @Test
    void dev_withDefaultSecret_warnsButStartsUp() {
        SecurityConfigValidator v = validator(env(new String[]{"dev"}, "", ""), SecurityConfigValidator.DEFAULT_JWT_SECRET);
        assertDoesNotThrow(v::validate);
    }

    @Test
    void test_profile_withWeakSecret_startsUp() {
        SecurityConfigValidator v = validator(env(new String[]{"test"}, "", ""), "tooshort");
        assertDoesNotThrow(v::validate);
    }
}
