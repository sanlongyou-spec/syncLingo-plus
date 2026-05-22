package com.si.backend.facade;

import com.si.backend.dto.SaveUserLanguagePreferenceRequest;
import com.si.backend.service.UserLanguagePreferenceService;
import com.si.backend.vo.UserLanguagePreferenceVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Facade for user default interpretation language preferences.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserLanguagePreferenceFacade {

    private final UserLanguagePreferenceService preferenceService;

    public UserLanguagePreferenceVo get(Long userId) {
        log.info("[UserLanguagePreferenceFacade] get start, userId={}", userId);
        UserLanguagePreferenceVo result = preferenceService.get(userId);
        log.info("[UserLanguagePreferenceFacade] get end, userId={}, enabledCount={}", userId, result.getEnabledLanguages().size());
        return result;
    }

    public UserLanguagePreferenceVo save(Long userId, SaveUserLanguagePreferenceRequest request) {
        log.info("[UserLanguagePreferenceFacade] save start, userId={}", userId);
        UserLanguagePreferenceVo result = preferenceService.save(userId, request);
        log.info("[UserLanguagePreferenceFacade] save end, userId={}, enabledCount={}", userId, result.getEnabledLanguages().size());
        return result;
    }
}
