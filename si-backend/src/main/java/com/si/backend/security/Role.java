package com.si.backend.security;

/**
 * 人员用户角色(P1)。与机器/分享身份完全分离,仅取自 si_user.role。
 */
public enum Role {
    ADMIN,
    OPERATOR,
    VIEWER;

    /** 宽松解析:大小写/空白容错;非法或空→null(由调用方按"无角色"处理)。 */
    public static Role from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
