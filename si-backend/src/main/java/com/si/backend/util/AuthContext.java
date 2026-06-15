package com.si.backend.util;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.Role;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the authenticated user set by JwtAuthFilter for the current HTTP request.
 */
public final class AuthContext {

    private AuthContext() {
    }

    public static Long currentUserId() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            return (Long) attributes.getRequest().getAttribute("authenticatedUserId");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 当前请求的认证角色(由 JwtAuthFilter 按 userId 从库加载并写入请求属性);未知返回 null。 */
    public static Role currentRole() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            return Role.from((String) attributes.getRequest().getAttribute("authenticatedRole"));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static AuthenticatedActor requireActor() {
        Long authenticatedUserId = currentUserId();
        if (authenticatedUserId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
        return new AuthenticatedActor(authenticatedUserId, currentRole());
    }

    /** 高危操作即时强制 ADMIN(不依赖 REPORT_ONLY 拦截器)。非 ADMIN→403。 */
    public static AuthenticatedActor requireAdmin() {
        AuthenticatedActor actor = requireActor();
        if (!actor.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
        return actor;
    }

    public static Long requireSelf(Long requestedUserId) {
        return requireSelf(requireActor(), requestedUserId);
    }

    public static Long requireSelf(AuthenticatedActor actor, Long requestedUserId) {
        if (actor == null || actor.userId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
        if (requestedUserId != null && !actor.userId().equals(requestedUserId)) {
            throw BizException.of(ErrorCode.FORBIDDEN, "Cannot access another user's data");
        }
        return actor.userId();
    }
}
