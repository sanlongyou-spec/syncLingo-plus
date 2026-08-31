package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.dto.SaveUserSummaryRequirementsRequest;
import com.si.backend.facade.UserPreferenceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import com.si.backend.vo.UserSummaryRequirementsVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

/**
 * Verifies user preference endpoints fail closed without an authenticated actor.
 */
class UserPreferenceControllerSecurityTest {

    private final UserPreferenceFacade facade = mock(UserPreferenceFacade.class);
    private final UserPreferenceController controller = new UserPreferenceController(facade);

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getWithoutActor_isRejectedBeforeServiceCall() {
        BizException error = assertThrows(BizException.class, controller::getSummaryRecipients);

        assertEquals(401, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void saveRecipientsWithoutActor_isRejectedBeforeFacadeCall() {
        BizException error = assertThrows(BizException.class,
                () -> controller.saveSummaryRecipients(List.of("recipient@example.com")));

        assertEquals(401, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void getRequirementsWithoutActor_isRejectedBeforeFacadeCall() {
        BizException error = assertThrows(BizException.class, controller::getSummaryRequirements);

        assertEquals(401, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void saveRequirementsWithoutActor_isRejectedBeforeFacadeCall() {
        BizException error = assertThrows(BizException.class,
                () -> controller.saveSummaryRequirements(new SaveUserSummaryRequirementsRequest()));

        assertEquals(401, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void authenticatedRequestsUseActorUserId() {
        bindActor(57L);
        UserSummaryRequirementsVo requirements = UserSummaryRequirementsVo.builder()
                .meetingSummaryRequirements("账号 57 的设置")
                .speakerSummaryRequirements("")
                .build();
        when(facade.getSummaryRecipients(57L)).thenReturn(List.of("owner@example.com"));
        when(facade.getSummaryRequirements(57L)).thenReturn(requirements);

        assertEquals(List.of("owner@example.com"), controller.getSummaryRecipients().getData());
        assertEquals(requirements, controller.getSummaryRequirements().getData());

        verify(facade).getSummaryRecipients(57L);
        verify(facade).getSummaryRequirements(57L);
    }

    private static void bindActor(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedUserId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
