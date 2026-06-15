package com.si.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the test profile cannot be pointed at the production database name.
 */
class TestProfileSafetyValidatorTest {

    @Test
    void productionDatabaseName_isRejected() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.datasource.url", ""))
                .thenReturn("jdbc:mysql://db:3306/si_backend?useSSL=false");

        assertThrows(IllegalStateException.class, () -> new TestProfileSafetyValidator(environment).validate());
    }

    @Test
    void dedicatedTestDatabaseName_isAllowed() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.datasource.url", ""))
                .thenReturn("jdbc:mysql://db:3306/si_backend_test?useSSL=false");

        assertDoesNotThrow(() -> new TestProfileSafetyValidator(environment).validate());
    }
}
