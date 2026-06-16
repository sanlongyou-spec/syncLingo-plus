package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import com.si.backend.service.AuditOutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal operations endpoints protected by a dedicated ops credential.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/ops")
@RequiredArgsConstructor
public class InternalOpsController {

    private final AuditOutboxDispatcher auditOutboxDispatcher;

    @AuthorizationSpec(
            identity = IdentityType.SERVICE,
            permission = PermissionCode.OPS_EXECUTE,
            scope = ResourceScope.SERVICE,
            expectedStatuses = {200, 401, 403}
    )
    @PostMapping("/audit-outbox/dispatch")
    public Result<Map<String, Integer>> dispatchAuditOutbox(
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        int dispatched = auditOutboxDispatcher.dispatchPending(limit);
        log.info("[InternalOpsController] audit outbox dispatched, count={}", dispatched);
        return Result.ok(Map.of("dispatched", dispatched));
    }
}
