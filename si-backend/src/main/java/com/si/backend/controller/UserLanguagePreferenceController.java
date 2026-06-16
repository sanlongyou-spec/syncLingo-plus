package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.SaveUserLanguagePreferenceRequest;
import com.si.backend.facade.UserLanguagePreferenceFacade;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.UserLanguagePreferenceVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * User default interpretation language preference endpoints.
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.LANGUAGE_PREFERENCE_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 401, 403})
@RequestMapping("/api/language-preferences")
@RequiredArgsConstructor
public class UserLanguagePreferenceController {

    private final UserLanguagePreferenceFacade facade;

    @GetMapping
    public Result<UserLanguagePreferenceVo> get() {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserLanguagePreferenceController] get start, userId={}", userId);
        UserLanguagePreferenceVo result = facade.get(userId);
        log.info("[UserLanguagePreferenceController] get end, userId={}", userId);
        return Result.ok(result);
    }

    @PutMapping
    public Result<UserLanguagePreferenceVo> save(
            @RequestBody SaveUserLanguagePreferenceRequest request
    ) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserLanguagePreferenceController] save start, userId={}", userId);
        UserLanguagePreferenceVo result = facade.save(userId, request);
        log.info("[UserLanguagePreferenceController] save end, userId={}", userId);
        return Result.ok(result);
    }
}
