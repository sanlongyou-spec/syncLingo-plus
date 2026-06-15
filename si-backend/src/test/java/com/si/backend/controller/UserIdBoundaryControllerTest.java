package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.dto.TranslateTextRequest;
import com.si.backend.facade.TranslateFacade;
import com.si.backend.service.AsrHotwordService;
import com.si.backend.service.HotwordExtractionService;
import com.si.backend.service.PreMeetingService;
import com.si.backend.service.ResourceOwnershipPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies remaining legacy userId parameters cannot select another user's data.
 */
class UserIdBoundaryControllerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void translateWithAnotherUserId_isRejected() {
        TranslateFacade facade = mock(TranslateFacade.class);
        TranslateController controller = new TranslateController(facade);
        TranslateTextRequest request = new TranslateTextRequest(9L, "hello", "en", "id");
        bindActor(5L);

        BizException error = assertThrows(BizException.class, () -> controller.translate(request));

        assertEquals(403, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void preMeetingUsageWithAnotherUserId_isRejected() {
        PreMeetingService preMeetingService = mock(PreMeetingService.class);
        PreMeetingController controller = new PreMeetingController(
                preMeetingService,
                mock(HotwordExtractionService.class),
                mock(AsrHotwordService.class),
                mock(ResourceOwnershipPolicy.class)
        );
        bindActor(5L);

        BizException error = assertThrows(BizException.class, () -> controller.getUsage(9L, 365));

        assertEquals(403, error.getCode());
        verifyNoInteractions(preMeetingService);
    }

    private void bindActor(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedUserId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
