package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.service.UserPreferenceService;
import com.si.backend.util.AuthContext;
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
        expectedStatuses = {200, 401})
@RequestMapping("/api/user/preference")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    @GetMapping("/summary-recipients")
    public Result<List<String>> getSummaryRecipients() {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserPreferenceController] getSummaryRecipients, userId={}", userId);
        return Result.ok(userPreferenceService.getSummaryRecipients(userId));
    }

    @PutMapping("/summary-recipients")
    public Result<Void> saveSummaryRecipients(@RequestBody List<String> recipients) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[UserPreferenceController] saveSummaryRecipients, userId={}, count={}", userId, recipients.size());
        userPreferenceService.saveSummaryRecipients(userId, recipients);
        return Result.ok(null);
    }
}
