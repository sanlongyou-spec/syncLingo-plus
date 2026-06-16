package com.si.backend.service;

import com.si.backend.entity.AuditLog;
import com.si.backend.entity.AuditOutbox;
import com.si.backend.mapper.AuditLogMapper;
import com.si.backend.mapper.AuditOutboxMapper;
import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.IdentityType;
import com.si.backend.security.authorization.PermissionCode;
import com.si.backend.security.authorization.ResourceScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Delivers audit outbox rows to the append-only audit log with bounded retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditOutboxDispatcher {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final AuditOutboxMapper auditOutboxMapper;
    private final AuditLogMapper auditLogMapper;

    @Scheduled(fixedDelayString = "${app.audit.outbox-dispatch-delay-ms:5000}")
    @AuthorizationSpec(
            identity = IdentityType.SYSTEM,
            permission = PermissionCode.INTERNAL_ASYNC,
            scope = ResourceScope.ALL,
            expectedStatuses = {200}
    )
    public void dispatchScheduled() {
        dispatchPending(DEFAULT_BATCH_SIZE);
    }

    public int dispatchPending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AuditOutbox> entries = auditOutboxMapper.findPending(safeLimit);
        int sent = 0;
        for (AuditOutbox entry : entries) {
            try {
                auditLogMapper.insert(toAuditLog(entry));
                auditOutboxMapper.markSent(entry.getId());
                sent++;
            } catch (Exception e) {
                auditOutboxMapper.markFailed(entry.getId(), truncate(e.getMessage(), 512));
                log.warn("[AuditOutboxDispatcher] dispatch failed, outboxId={}, action={}: {}",
                        entry.getId(), entry.getAction(), e.getMessage());
            }
        }
        return sent;
    }

    public void dispatchOneBestEffort(AuditOutbox entry) {
        if (entry == null || entry.getId() == null) {
            return;
        }
        try {
            auditLogMapper.insert(toAuditLog(entry));
            auditOutboxMapper.markSent(entry.getId());
        } catch (Exception e) {
            auditOutboxMapper.markFailed(entry.getId(), truncate(e.getMessage(), 512));
            log.warn("[AuditOutboxDispatcher] immediate dispatch failed, outboxId={}, action={}: {}",
                    entry.getId(), entry.getAction(), e.getMessage());
        }
    }

    private AuditLog toAuditLog(AuditOutbox entry) {
        AuditLog logEntry = new AuditLog();
        logEntry.setActorType(entry.getActorType());
        logEntry.setActorId(entry.getActorId());
        logEntry.setRole(entry.getRole());
        logEntry.setAction(entry.getAction());
        logEntry.setResourceType(entry.getResourceType());
        logEntry.setResourceId(entry.getResourceId());
        logEntry.setResult(entry.getResult());
        logEntry.setIp(entry.getIp());
        logEntry.setDetail(entry.getDetail());
        return logEntry;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
