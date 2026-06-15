package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.CreateUserRequest;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.UserAdminService;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.UserSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * P1 用户管理(仅 ADMIN)。路径在 JWT 保护下(非 /api/admin/** 共享密钥路径);
 * 每个写操作 {@link AuthContext#requireAdmin()} 即时强制,不依赖 REPORT_ONLY 拦截器。
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.ACCOUNT_SELF,
            scope = ResourceScope.SELF, expectedStatuses = {200, 401})
    @GetMapping("/me")
    public Result<UserSummaryVo> me() {
        return Result.ok(userAdminService.getById(AuthContext.requireActor().userId()));
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.USER_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 401, 403})
    @GetMapping
    public Result<List<UserSummaryVo>> list() {
        AuthContext.requireAdmin();
        return Result.ok(userAdminService.list());
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.USER_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 400, 401, 403})
    @PostMapping
    public Result<UserSummaryVo> create(@RequestBody CreateUserRequest request) {
        AuthContext.requireAdmin();
        return Result.ok(userAdminService.create(request));
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.USER_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 400, 401, 403, 404})
    @PutMapping("/{id}/role")
    public Result<UserSummaryVo> updateRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        AuthContext.requireAdmin();
        return Result.ok(userAdminService.updateRole(id, body.get("role")));
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.USER_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 400, 401, 403, 404})
    @PutMapping("/{id}/status")
    public Result<UserSummaryVo> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        AuthContext.requireAdmin();
        return Result.ok(userAdminService.updateStatus(id, body.get("status")));
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.USER_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 400, 401, 403, 404})
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        AuthContext.requireAdmin();
        userAdminService.resetPassword(id, body.get("password"));
        return Result.ok();
    }
}
