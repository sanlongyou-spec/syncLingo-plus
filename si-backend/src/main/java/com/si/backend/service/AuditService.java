package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.AuditLog;
import com.si.backend.entity.AuditOutbox;
import com.si.backend.mapper.AuditLogMapper;
import com.si.backend.mapper.AuditOutboxMapper;
import com.si.backend.security.Role;
import com.si.backend.util.AuthContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * P2/P6 安全审计:记录敏感操作(用户管理、登录、权限拒绝、Break-glass 等)。审计表仅追加。
 *
 * <p>两档可用性(P6):
 * <ul>
 *   <li>{@code record*} best-effort:写入失败只告警、不阻断(普通访问/登录等)。</li>
 *   <li>{@code recordCritical*} fail-closed:写入失败抛异常,使高危操作在同事务内回滚
 *       (权限变更/管理员创建/Break-glass/L3 下载/删除)。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final AuditOutboxMapper auditOutboxMapper;
    private final AuditOutboxDispatcher auditOutboxDispatcher;

    @PostConstruct
    public void init() {
        try {
            auditLogMapper.createTableIfNotExists();
            auditOutboxMapper.createTableIfNotExists();
            log.info("[AuditService] audit_log/audit_outbox tables ready");
        } catch (Exception e) {
            log.warn("[AuditService] create audit_log failed: {}", e.getMessage());
        }
    }

    /** 以当前认证用户为 actor 记录(管理操作)。 */
    public void record(String action, String result, String resourceType, String resourceId, String detail) {
        Long uid = AuthContext.currentUserId();
        Role role = AuthContext.currentRole();
        recordActor(uid != null ? "USER" : "ANONYMOUS",
                uid != null ? String.valueOf(uid) : null,
                role != null ? role.name() : null,
                action, result, resourceType, resourceId, detail);
    }

    /** 显式 actor(如登录前/失败,actor 为尝试的用户名)。best-effort。 */
    public void recordActor(String actorType, String actorId, String role,
                            String action, String result, String resourceType, String resourceId, String detail) {
        try {
            AuditOutbox outbox = buildOutbox(actorType, actorId, role, action, result, resourceType, resourceId, detail);
            auditOutboxMapper.insert(outbox);
            auditOutboxDispatcher.dispatchOneBestEffort(outbox);
        } catch (Exception e) {
            // best-effort:审计失败不影响业务主流程
            log.warn("[AuditService] record failed, action={}, result={}: {}", action, result, e.getMessage());
        }
    }

    /** 高危操作以当前认证用户为 actor 记录;写入失败抛异常(fail-closed,配合 @Transactional 回滚业务)。 */
    public void recordCritical(String action, String result, String resourceType, String resourceId, String detail) {
        Long uid = AuthContext.currentUserId();
        Role role = AuthContext.currentRole();
        recordActorCritical(uid != null ? "USER" : "ANONYMOUS",
                uid != null ? String.valueOf(uid) : null,
                role != null ? role.name() : null,
                action, result, resourceType, resourceId, detail);
    }

    /** 高危操作显式 actor 记录(如 Break-glass、SYSTEM);写入失败抛异常(fail-closed)。 */
    public void recordActorCritical(String actorType, String actorId, String role,
                                    String action, String result, String resourceType, String resourceId, String detail) {
        try {
            AuditOutbox outbox = buildOutbox(actorType, actorId, role, action, result, resourceType, resourceId, detail);
            auditOutboxMapper.insert(outbox);
            auditOutboxDispatcher.dispatchOneBestEffort(outbox);
        } catch (Exception e) {
            log.error("[AuditService] CRITICAL audit write failed, action={}, result={}: {}", action, result, e.getMessage());
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "安全审计写入失败,操作已中止");
        }
    }

    private AuditLog build(String actorType, String actorId, String role,
                           String action, String result, String resourceType, String resourceId, String detail) {
        AuditLog entry = new AuditLog();
        entry.setActorType(actorType);
        entry.setActorId(truncate(actorId, 64));
        entry.setRole(role);
        entry.setAction(action);
        entry.setResult(result);
        entry.setResourceType(resourceType);
        entry.setResourceId(truncate(resourceId, 64));
        entry.setIp(currentIp());
        entry.setDetail(truncate(detail, 1024));
        return entry;
    }

    private AuditOutbox buildOutbox(String actorType, String actorId, String role,
                                    String action, String result, String resourceType, String resourceId, String detail) {
        AuditOutbox entry = new AuditOutbox();
        entry.setActorType(actorType);
        entry.setActorId(truncate(actorId, 64));
        entry.setRole(role);
        entry.setAction(action);
        entry.setResult(result);
        entry.setResourceType(resourceType);
        entry.setResourceId(truncate(resourceId, 64));
        entry.setIp(currentIp());
        entry.setDetail(truncate(detail, 1024));
        return entry;
    }

    public List<AuditLog> getRecent(int limit) {
        int safe = Math.max(1, Math.min(limit, 500));
        return auditLogMapper.findRecent(safe);
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest().getRemoteAddr() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
