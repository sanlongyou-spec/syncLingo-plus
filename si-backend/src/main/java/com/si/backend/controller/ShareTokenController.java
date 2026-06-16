package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.entity.ShareToken;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.ShareTokenService;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * P4 分享令牌管理(操作员为自有会话/频道签发,可撤销)。匿名解析端点在 InterpretationController 的 /public 下。
 */
@Slf4j
@RestController
@RequestMapping("/api/share-tokens")
@RequiredArgsConstructor
public class ShareTokenController {

    private final ShareTokenService shareTokenService;

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.INTERPRETATION_OPERATE,
            scope = ResourceScope.OWN, expectedStatuses = {200, 401, 403, 404})
    @PostMapping("/session")
    public Result<ShareTokenService.Issued> mintSession(@RequestBody Map<String, String> body) {
        return Result.ok(shareTokenService.mintSessionToken(AuthContext.requireActor(), body.get("sessionId")));
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.INTERPRETATION_OPERATE,
            scope = ResourceScope.SELF, expectedStatuses = {200, 401, 403})
    @PostMapping("/channel")
    public Result<ShareTokenService.Issued> mintChannel() {
        return Result.ok(shareTokenService.mintChannelToken(AuthContext.requireActor()));
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.INTERPRETATION_OPERATE,
            scope = ResourceScope.OWN, expectedStatuses = {200, 401, 403, 404})
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        shareTokenService.revoke(AuthContext.requireActor(), id);
        return Result.ok();
    }

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.INTERPRETATION_OPERATE,
            scope = ResourceScope.SELF, expectedStatuses = {200, 401, 403})
    @GetMapping
    public Result<List<ShareToken>> listOwnChannelTokens() {
        return Result.ok(shareTokenService.listOwnChannelTokens(AuthContext.requireActor()));
    }
}
