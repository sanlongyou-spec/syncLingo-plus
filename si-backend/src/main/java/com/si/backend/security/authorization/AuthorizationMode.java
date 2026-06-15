package com.si.backend.security.authorization;

/**
 * 角色权限执行模式(P1)。
 * <ul>
 *   <li>{@code REPORT_ONLY}:只记录"本应拒绝",不拦截(上线观察期,零功能影响)。</li>
 *   <li>{@code ENFORCE}:实际拒绝(返回 403)。</li>
 * </ul>
 * 注意:IDOR/资源归属永远强制(在 ResourceOwnershipPolicy/AuthContext 中),不受本模式影响。
 */
public enum AuthorizationMode {
    REPORT_ONLY,
    ENFORCE
}
