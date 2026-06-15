package com.si.backend.security;

/**
 * Authenticated human user propagated explicitly across authorization boundaries.
 *
 * <p>{@code role} 由 JwtAuthFilter 按 userId 从服务端加载(P1);旧调用点未提供角色时为 null,
 * 经向后兼容构造保留(权限判定对 null 角色一律 fail-closed)。
 */
public record AuthenticatedActor(Long userId, Role role) {

    /** 向后兼容:仅有 userId(角色未知)。用于 WS / 过渡期 / 测试。 */
    public AuthenticatedActor(Long userId) {
        this(userId, null);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean hasRole(Role expected) {
        return role != null && role == expected;
    }
}
