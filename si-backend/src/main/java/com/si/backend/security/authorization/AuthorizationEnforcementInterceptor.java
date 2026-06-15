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
 * P1 角色功能权限执行(默认 REPORT_ONLY)。
 *
 * <p>仅对声明了 {@link AuthorizationSpec} 且 identity=USER 的接口生效;
 * 匿名/服务/系统身份由认证 Filter 与各自密钥裁决,不在此处。
 * 资源归属(IDOR)由 ResourceOwnershipPolicy 在 facade/service 强制,与本拦截器无关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationEnforcementInterceptor implements HandlerInterceptor {

    private final AuthorizationProperties authorizationProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        AuthorizationSpec spec = AuthorizationSpecResolver.resolve(handlerMethod);
        if (spec == null || spec.identity() != IdentityType.USER) {
            return true; // 未声明 / 非用户身份接口:不在角色执行范围
        }

        Role role = AuthContext.currentRole();
        PermissionCode required = spec.permission();
        if (RolePermissions.grants(role, required)) {
            return true;
        }

        // 角色不足
        if (authorizationProperties.getMode() == AuthorizationMode.ENFORCE) {
            log.warn("[Authz] DENY (enforce), path={}, role={}, required={}",
                    request.getRequestURI(), role, required);
            throw BizException.of(ErrorCode.FORBIDDEN, "无权限");
        }
        log.warn("[Authz] would-deny (report-only), path={}, role={}, required={}",
                request.getRequestURI(), role, required);
        return true;
    }
}
