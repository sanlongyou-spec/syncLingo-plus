package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.service.UserPreferenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies user preference endpoints fail closed without an authenticated actor.
 */
class UserPreferenceControllerSecurityTest {

    private final UserPreferenceService service = mock(UserPreferenceService.class);
    private final UserPreferenceController controller = new UserPreferenceController(service);

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getWithoutActor_isRejectedBeforeServiceCall() {
        BizException error = assertThrows(BizException.class, controller::getSummaryRecipients);

        assertEquals(401, error.getCode());
        verifyNoInteractions(service);
    }
}
