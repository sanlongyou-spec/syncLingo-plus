package com.si.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.dto.SaveUserSummaryRequirementsRequest;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.vo.UserSummaryRequirementsVo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPreferenceServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final UserPreferenceService service = new UserPreferenceService(
            userMapper,
            jdbcTemplate,
            new ObjectMapper()
    );

    @Test
    void startupAddsAllSummaryPreferenceColumns() {
        service.initColumns();

        verify(jdbcTemplate).execute("ALTER TABLE si_user ADD COLUMN summary_recipients TEXT DEFAULT NULL");
        verify(jdbcTemplate).execute("ALTER TABLE si_user ADD COLUMN meeting_summary_requirements TEXT DEFAULT NULL");
        verify(jdbcTemplate).execute("ALTER TABLE si_user ADD COLUMN speaker_summary_requirements TEXT DEFAULT NULL");
        verify(jdbcTemplate, times(3)).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void differentAccountsReturnIndependentRequirements() {
        SiUser accountA = user(11L, "账号 A 的会议要求", "账号 A 的发言要求");
        SiUser accountB = user(12L, null, null);
        when(userMapper.findById(11L)).thenReturn(accountA);
        when(userMapper.findById(12L)).thenReturn(accountB);

        UserSummaryRequirementsVo resultA = service.getSummaryRequirements(11L);
        UserSummaryRequirementsVo resultB = service.getSummaryRequirements(12L);

        assertEquals("账号 A 的会议要求", resultA.getMeetingSummaryRequirements());
        assertEquals("账号 A 的发言要求", resultA.getSpeakerSummaryRequirements());
        assertEquals("", resultB.getMeetingSummaryRequirements());
        assertEquals("", resultB.getSpeakerSummaryRequirements());
        verify(userMapper).findById(11L);
        verify(userMapper).findById(12L);
    }

    @Test
    void partialRequirementSaveUpdatesOnlyProvidedFieldForCurrentAccount() {
        SaveUserSummaryRequirementsRequest request = new SaveUserSummaryRequirementsRequest();
        request.setMeetingSummaryRequirements("突出风险和行动项");
        when(userMapper.findById(21L)).thenReturn(user(21L, "突出风险和行动项", "保留数字"));

        UserSummaryRequirementsVo result = service.saveSummaryRequirements(21L, request);

        verify(userMapper).updateSummaryRequirements(21L, "突出风险和行动项", null);
        assertEquals("突出风险和行动项", result.getMeetingSummaryRequirements());
        assertEquals("保留数字", result.getSpeakerSummaryRequirements());
    }

    @Test
    void explicitEmptyRequirementClearsOnlyProvidedFieldForCurrentAccount() {
        SaveUserSummaryRequirementsRequest request = new SaveUserSummaryRequirementsRequest();
        request.setSpeakerSummaryRequirements("");
        when(userMapper.findById(22L)).thenReturn(user(22L, "保留会议要求", ""));

        UserSummaryRequirementsVo result = service.saveSummaryRequirements(22L, request);

        verify(userMapper).updateSummaryRequirements(22L, null, "");
        assertEquals("保留会议要求", result.getMeetingSummaryRequirements());
        assertEquals("", result.getSpeakerSummaryRequirements());
    }

    @Test
    void clearingRecipientsPersistsEmptyArrayForCurrentAccount() {
        service.saveSummaryRecipients(33L, List.of());

        verify(userMapper).updateSummaryRecipients(33L, "[]");
    }

    @Test
    void invalidStoredRecipientJsonFailsClosedToEmptyList() {
        when(userMapper.getSummaryRecipients(34L)).thenReturn("not-json");

        assertEquals(List.of(), service.getSummaryRecipients(34L));
    }

    @Test
    void nullStoredRecipientJsonFailsClosedToEmptyList() {
        when(userMapper.getSummaryRecipients(35L)).thenReturn("null");

        assertEquals(List.of(), service.getSummaryRecipients(35L));
    }

    @Test
    void requirementPersistenceFailureReturnsExplicitServerError() {
        SaveUserSummaryRequirementsRequest request = new SaveUserSummaryRequirementsRequest();
        request.setSpeakerSummaryRequirements("保留专业术语");
        doThrow(new RuntimeException("database unavailable"))
                .when(userMapper).updateSummaryRequirements(41L, null, "保留专业术语");

        BizException error = assertThrows(BizException.class,
                () -> service.saveSummaryRequirements(41L, request));

        assertEquals(500, error.getCode());
    }

    @Test
    void recipientPersistenceFailureReturnsExplicitServerError() {
        doThrow(new RuntimeException("database unavailable"))
                .when(userMapper).updateSummaryRecipients(42L, "[]");

        BizException error = assertThrows(BizException.class,
                () -> service.saveSummaryRecipients(42L, List.of()));

        assertEquals(500, error.getCode());
    }

    private static SiUser user(Long id, String meetingRequirements, String speakerRequirements) {
        SiUser user = new SiUser();
        user.setId(id);
        user.setMeetingSummaryRequirements(meetingRequirements);
        user.setSpeakerSummaryRequirements(speakerRequirements);
        return user;
    }
}
