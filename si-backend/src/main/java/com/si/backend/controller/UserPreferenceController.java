package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.common.Result;
import com.si.backend.service.UserPreferenceService;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/user/preference")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    @GetMapping("/summary-recipients")
    public Result<List<String>> getSummaryRecipients() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw BizException.of(ErrorCode.UNAUTHORIZED, "请先登录");
        log.info("[UserPreferenceController] getSummaryRecipients, userId={}", userId);
        return Result.ok(userPreferenceService.getSummaryRecipients(userId));
    }

    @PutMapping("/summary-recipients")
    public Result<Void> saveSummaryRecipients(@RequestBody List<String> recipients) {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw BizException.of(ErrorCode.UNAUTHORIZED, "请先登录");
        log.info("[UserPreferenceController] saveSummaryRecipients, userId={}, count={}", userId, recipients.size());
        userPreferenceService.saveSummaryRecipients(userId, recipients);
        return Result.ok(null);
    }
}
