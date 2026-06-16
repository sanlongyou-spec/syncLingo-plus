package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.AccountService;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P5 账号自助安全端点。在 JWT 保护下(非 /api/auth/** 公开路径),仅作用于调用者本人。
 */
@Slf4j
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.ACCOUNT_SELF,
            scope = ResourceScope.SELF, expectedStatuses = {200, 400, 401})
    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody Map<String, String> body) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[AccountController] changePassword, userId={}", userId);
        accountService.changeOwnPassword(userId, body.get("currentPassword"), body.get("newPassword"));
        return Result.ok();
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.ACCOUNT_SELF,
            scope = ResourceScope.SELF, expectedStatuses = {200, 401})
    @PostMapping("/logout-all")
    public Result<Void> logoutAll() {
        Long userId = AuthContext.requireActor().userId();
        log.info("[AccountController] logoutAll, userId={}", userId);
        accountService.logoutAll(userId);
        return Result.ok();
    }
}
