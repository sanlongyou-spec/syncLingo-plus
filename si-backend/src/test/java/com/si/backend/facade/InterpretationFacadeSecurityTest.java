package com.si.backend.facade;

import com.si.backend.common.ErrorCode;
import com.si.backend.common.BizException;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationResultService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.MeetingSummaryService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.service.SessionSpeakerNameService;
import com.si.backend.service.UserLanguagePreferenceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the facade enforces actor and resource ownership before business operations.
 */
class InterpretationFacadeSecurityTest {

    private final InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
    private final UserLanguagePreferenceService languagePreferenceService = mock(UserLanguagePreferenceService.class);
    private final InterpretationResultService resultService = mock(InterpretationResultService.class);
    private final InterpretationRecordService recordService = mock(InterpretationRecordService.class);
    private final SessionSpeakerNameService speakerNameService = mock(SessionSpeakerNameService.class);
    private final MeetingSummaryService summaryService = mock(MeetingSummaryService.class);
    private final ResourceOwnershipPolicy ownershipPolicy = mock(ResourceOwnershipPolicy.class);
    private final InterpretationFacade facade = new InterpretationFacade(
            sessionService,
            languagePreferenceService,
            resultService,
            recordService,
            speakerNameService,
            summaryService,
            ownershipPolicy
    );

    @Test
    void start_usesAuthenticatedActorUserIdForSessionCreation() {
        StartInterpretationRequest request = new StartInterpretationRequest();
        request.setSourceLang("zh-CN");
        request.setTargetLang("id-ID");
        when(languagePreferenceService.resolveEnabledLanguages(5L, null))
                .thenReturn(List.of("zh-CN", "id-ID"));

        facade.startInterpretation(new AuthenticatedActor(5L), request);

        verify(sessionService).startSession(
                anyString(),
                eq(5L),
                eq("zh-CN"),
                eq("id-ID"),
                any(),
                any(),
                any(),
                anyList(),
                any()
        );
    }

    @Test
    void stopOtherUsersSession_isRejectedBeforeStop() {
        AuthenticatedActor actor = new AuthenticatedActor(5L);
        when(ownershipPolicy.requireOwnedSession(actor, "other"))
                .thenThrow(BizException.of(ErrorCode.NOT_FOUND));

        BizException error = assertThrows(BizException.class, () -> facade.stopInterpretation(actor, "other"));

        assertEquals(404, error.getCode());
        verifyNoInteractions(sessionService);
    }
}
