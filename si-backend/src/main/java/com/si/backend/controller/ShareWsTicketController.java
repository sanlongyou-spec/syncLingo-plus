package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.ShareTokenService;
import com.si.backend.service.ShareWsTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public share WebSocket ticket exchange. The share token remains the capability; the returned ticket is one-time.
 */
@Slf4j
@RestController
@RequestMapping("/api/interpretation/public/share-ws-tickets")
@RequiredArgsConstructor
public class ShareWsTicketController {

    private final ShareTokenService shareTokenService;
    private final ShareWsTicketService shareWsTicketService;

    @AuthorizationSpec(identity = IdentityType.ANONYMOUS, permission = PermissionCode.SHARE_READ,
            scope = ResourceScope.PUBLIC, expectedStatuses = {200, 400, 401, 404})
    @PostMapping
    public Result<ShareWsTicketService.Issued> issue(@RequestParam("token") String token) {
        String sessionId = shareTokenService.resolveSessionId(token);
        ShareWsTicketService.Issued issued = shareWsTicketService.issueTextTicket(sessionId);
        log.info("[ShareWsTicketController] issued share ws ticket, sessionId={}", sessionId);
        return Result.ok(issued);
    }
}
