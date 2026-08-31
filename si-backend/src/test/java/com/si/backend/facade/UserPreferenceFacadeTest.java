package com.si.backend.facade;

import com.si.backend.dto.SaveUserSummaryRequirementsRequest;
import com.si.backend.service.UserPreferenceService;
import com.si.backend.vo.UserSummaryRequirementsVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPreferenceFacadeTest {

    private final UserPreferenceService service = mock(UserPreferenceService.class);
    private final UserPreferenceFacade facade = new UserPreferenceFacade(service);

    @Test
    void recipientOperationsUseRequestedAccount() {
        when(service.getSummaryRecipients(27L)).thenReturn(List.of("team@example.com"));

        assertEquals(List.of("team@example.com"), facade.getSummaryRecipients(27L));
        facade.saveSummaryRecipients(27L, List.of());

        verify(service).getSummaryRecipients(27L);
        verify(service).saveSummaryRecipients(27L, List.of());
    }

    @Test
    void requirementOperationsUseRequestedAccount() {
        SaveUserSummaryRequirementsRequest request = new SaveUserSummaryRequirementsRequest();
        request.setMeetingSummaryRequirements("突出决议");
        UserSummaryRequirementsVo saved = UserSummaryRequirementsVo.builder()
                .meetingSummaryRequirements("突出决议")
                .speakerSummaryRequirements("")
                .build();
        when(service.saveSummaryRequirements(31L, request)).thenReturn(saved);

        assertEquals(saved, facade.saveSummaryRequirements(31L, request));
        verify(service).saveSummaryRequirements(31L, request);
    }
}
