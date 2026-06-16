package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.WsTicketService;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P5 WebSocket 一次性票据签发。已认证用户连接 ASR WS 前换取短时票据,握手用票据而非 JWT。
 * 路径在 JWT 保护下(非公开),票据仅绑定调用者自身 userId。
 */
@Slf4j
@RestController
@RequestMapping("/api/ws-tickets")
@RequiredArgsConstructor
public class WsTicketController {

    private final WsTicketService wsTicketService;

    @AuthorizationSpec(identity = IdentityType.USER, permission = PermissionCode.ACCOUNT_SELF,
            scope = ResourceScope.SELF, expectedStatuses = {200, 401})
    @PostMapping
    public Result<Map<String, String>> issue() {
        AuthenticatedActor actor = AuthContext.requireActor();
        String ticket = wsTicketService.issue(actor.userId());
        log.info("[WsTicketController] issued ws ticket, userId={}", actor.userId());
        return Result.ok(Map.of("ticket", ticket));
    }
}
