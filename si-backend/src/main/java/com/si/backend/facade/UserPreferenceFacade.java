package com.si.backend.facade;

import com.si.backend.dto.SaveUserSummaryRequirementsRequest;
import com.si.backend.service.UserPreferenceService;
import com.si.backend.vo.UserSummaryRequirementsVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Facade for authenticated-user summary preferences.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPreferenceFacade {

    private final UserPreferenceService userPreferenceService;

    public List<String> getSummaryRecipients(Long userId) {
        log.info("[UserPreferenceFacade] getSummaryRecipients start, userId={}", userId);
        List<String> result = userPreferenceService.getSummaryRecipients(userId);
        log.info("[UserPreferenceFacade] getSummaryRecipients end, userId={}, count={}", userId, result.size());
        return result;
    }

    public void saveSummaryRecipients(Long userId, List<String> recipients) {
        int count = recipients != null ? recipients.size() : 0;
        log.info("[UserPreferenceFacade] saveSummaryRecipients start, userId={}, count={}", userId, count);
        userPreferenceService.saveSummaryRecipients(userId, recipients != null ? recipients : List.of());
        log.info("[UserPreferenceFacade] saveSummaryRecipients end, userId={}, count={}", userId, count);
    }

    public UserSummaryRequirementsVo getSummaryRequirements(Long userId) {
        log.info("[UserPreferenceFacade] getSummaryRequirements start, userId={}", userId);
        UserSummaryRequirementsVo result = userPreferenceService.getSummaryRequirements(userId);
        log.info("[UserPreferenceFacade] getSummaryRequirements end, userId={}", userId);
        return result;
    }

    public UserSummaryRequirementsVo saveSummaryRequirements(
            Long userId,
            SaveUserSummaryRequirementsRequest request
    ) {
        log.info("[UserPreferenceFacade] saveSummaryRequirements start, userId={}", userId);
        UserSummaryRequirementsVo result = userPreferenceService.saveSummaryRequirements(userId, request);
        log.info("[UserPreferenceFacade] saveSummaryRequirements end, userId={}", userId);
        return result;
    }
}
