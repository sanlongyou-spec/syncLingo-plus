package com.si.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Prevents test-profile application contexts from connecting to the production database.
 */
@Component
@Profile("test")
@RequiredArgsConstructor
public class TestProfileSafetyValidator {

    private final Environment environment;

    @PostConstruct
    public void validate() {
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        String normalized = datasourceUrl.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*[/\\\\]si_backend(?:[?;].*)?$")) {
            throw new IllegalStateException("Test profile cannot use production database si_backend");
        }
    }
}
