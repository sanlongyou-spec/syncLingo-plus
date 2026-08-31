package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.SaveUserSummaryRequirementsRequest;
import com.si.backend.facade.UserPreferenceFacade;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.UserSummaryRequirementsVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.USER_PREFERENCE_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 400, 401, 403})
@RequestMapping("/api/user/preference")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceFacade facade;

    @GetMapping("/summary-recipients")
    public Result<List<String>> getSummaryRecipients() {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserPreferenceController] getSummaryRecipients start, userId={}", userId);
        List<String> result = facade.getSummaryRecipients(userId);
        log.info("[UserPreferenceController] getSummaryRecipients end, userId={}, count={}", userId, result.size());
        return Result.ok(result);
    }

    @PutMapping("/summary-recipients")
    public Result<Void> saveSummaryRecipients(@RequestBody List<String> recipients) {
        Long userId = AuthContext.requireActor().userId();
        List<String> safeRecipients = recipients != null ? recipients : List.of();
        log.info("[UserPreferenceController] saveSummaryRecipients start, userId={}, count={}",
                userId, safeRecipients.size());
        facade.saveSummaryRecipients(userId, safeRecipients);
        log.info("[UserPreferenceController] saveSummaryRecipients end, userId={}, count={}",
                userId, safeRecipients.size());
        return Result.ok(null);
    }

    @GetMapping("/summary-requirements")
    public Result<UserSummaryRequirementsVo> getSummaryRequirements() {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserPreferenceController] getSummaryRequirements start, userId={}", userId);
        UserSummaryRequirementsVo result = facade.getSummaryRequirements(userId);
        log.info("[UserPreferenceController] getSummaryRequirements end, userId={}", userId);
        return Result.ok(result);
    }

    @PutMapping("/summary-requirements")
    public Result<UserSummaryRequirementsVo> saveSummaryRequirements(
            @Valid @RequestBody SaveUserSummaryRequirementsRequest request
    ) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserPreferenceController] saveSummaryRequirements start, userId={}", userId);
        UserSummaryRequirementsVo result = facade.saveSummaryRequirements(userId, request);
        log.info("[UserPreferenceController] saveSummaryRequirements end, userId={}", userId);
        return Result.ok(result);
    }
}
