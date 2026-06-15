package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.dto.LoginRequest;
import com.si.backend.facade.AuthFacade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies public self-registration remains explicitly closed.
 */
class AuthControllerSecurityTest {

    @Test
    void register_isRejectedWithoutCallingFacade() {
        AuthFacade facade = mock(AuthFacade.class);
        AuthController controller = new AuthController(facade);
        LoginRequest request = new LoginRequest();
        request.setUsername("new-user");
        request.setPassword("password");

        BizException error = assertThrows(BizException.class, () -> controller.register(request));

        assertEquals(403, error.getCode());
        verifyNoInteractions(facade);
    }
}
