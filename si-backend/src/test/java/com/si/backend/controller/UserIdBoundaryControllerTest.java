package com.si.backend.controller;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies P5 user-owned endpoints derive the owner from the authenticated actor, not client userId input.
 */
class UserIdBoundaryControllerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void translate_usesAuthenticatedActorUserId() {
        TranslateFacade facade = mock(TranslateFacade.class);
        TranslateController controller = new TranslateController(facade);
        TranslateTextRequest request = new TranslateTextRequest("hello", "en", "id");
        when(facade.translate("hello", "en", "id", 5L)).thenReturn("halo");
        bindActor(5L);

        assertEquals("halo", controller.translate(request).getData());

        verify(facade).translate("hello", "en", "id", 5L);
    }

    @Test
    void preMeetingUsage_usesAuthenticatedActorUserId() {
        PreMeetingService preMeetingService = mock(PreMeetingService.class);
        PreMeetingController controller = new PreMeetingController(
                preMeetingService,
                mock(HotwordExtractionService.class),
                mock(AsrHotwordService.class),
                mock(com.si.backend.service.MeetingKnowledgeService.class),
                mock(com.si.backend.service.TerminologyExtractionService.class),
                mock(ResourceOwnershipPolicy.class)
        );
        bindActor(5L);

        controller.getUsage(365);

        verify(preMeetingService).getDailyUsage(5L, 365);
    }

    private void bindActor(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedUserId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
