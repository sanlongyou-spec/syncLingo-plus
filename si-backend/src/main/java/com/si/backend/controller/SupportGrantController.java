package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.entity.SupportAccessGrant;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.SupportAccessGrantService;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * P3 管理员临时内容授权。申请仅 ADMIN;批准须会议 owner 或另一管理员(申请人不能自批,服务层强制)。
 */
@Slf4j
@RestController
@RequestMapping("/api/support-grants")
@RequiredArgsConstructor
public class SupportGrantController {

    private final SupportAccessGrantService supportAccessGrantService;

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.MEETING_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 400, 401, 403, 404})
    @PostMapping
    public Result<Long> request(@RequestBody Map<String, Object> body) {
        Long meetingId = body.get("meetingId") == null ? null : Long.valueOf(String.valueOf(body.get("meetingId")));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        int ttl = body.get("ttlMinutes") == null ? 0 : Integer.parseInt(String.valueOf(body.get("ttlMinutes")));
        SupportAccessGrant grant = supportAccessGrantService.request(AuthContext.requireActor(), meetingId, reason, ttl);
        return Result.ok(grant.getId());
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.MEETING_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 400, 401, 403, 404})
    @PutMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        supportAccessGrantService.approve(AuthContext.requireActor(), id);
        return Result.ok();
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.MEETING_MANAGE,
            scope = ResourceScope.ALL, expectedStatuses = {200, 401, 403, 404})
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        supportAccessGrantService.revoke(AuthContext.requireActor(), id);
        return Result.ok();
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.AUDIT_READ,
            scope = ResourceScope.ALL, expectedStatuses = {200, 401, 403})
    @GetMapping
    public Result<List<SupportAccessGrant>> list(@RequestParam(required = false, defaultValue = "100") int limit) {
        return Result.ok(supportAccessGrantService.listRecent(AuthContext.requireActor(), limit));
    }
}
