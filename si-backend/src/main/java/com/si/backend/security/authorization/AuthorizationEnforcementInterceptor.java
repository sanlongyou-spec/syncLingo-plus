package com.si.backend.security.authorization;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.AuthorizationProperties;
import com.si.backend.security.Role;
import com.si.backend.util.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces role-to-permission authorization for endpoints annotated with {@link AuthorizationSpec}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationEnforcementInterceptor implements HandlerInterceptor {

    private final AuthorizationProperties authorizationProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long startMs = System.currentTimeMillis();
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        AuthorizationSpec spec = AuthorizationSpecResolver.resolve(handlerMethod);
        if (spec == null || spec.identity() != IdentityType.USER) {
            return true;
        }

        Role role = AuthContext.currentRole();
        Long actorId = AuthContext.currentUserId();
        PermissionCode required = spec.permission();
        AuthorizationMode mode = authorizationProperties.getMode();
        String handlerName = handlerMethod.getBeanType().getSimpleName() + "."
                + handlerMethod.getMethod().getName();

        if (RolePermissions.grants(role, required)) {
            log.info("[Authz] ALLOW, actorId={}, role={}, required={}, mode={}, method={}, path={}, handler={}, costMs={}",
                    actorId, role, required, mode, request.getMethod(), request.getRequestURI(),
                    handlerName, System.currentTimeMillis() - startMs);
            return true;
        }

        if (mode == AuthorizationMode.ENFORCE) {
            log.warn("[Authz] DENY, actorId={}, role={}, required={}, mode={}, method={}, path={}, handler={}, costMs={}",
                    actorId, role, required, mode, request.getMethod(), request.getRequestURI(),
                    handlerName, System.currentTimeMillis() - startMs);
            throw BizException.of(ErrorCode.FORBIDDEN, "无权限");
        }

        log.warn("[Authz] REPORT_ONLY_DENY, actorId={}, role={}, required={}, mode={}, method={}, path={}, handler={}, costMs={}",
                actorId, role, required, mode, request.getMethod(), request.getRequestURI(),
                handlerName, System.currentTimeMillis() - startMs);
        return true;
    }
}
