package com.si.backend.security;

/**
 * 会议成员访问级别(P3)。OPERATE 蕴含 VIEW。
 */
public enum AccessLevel {
    VIEW,
    OPERATE;

    public static AccessLevel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 已授予 granted 是否满足所需 required(OPERATE 满足 VIEW 与 OPERATE;VIEW 仅满足 VIEW)。 */
    public static boolean satisfies(String granted, AccessLevel required) {
        AccessLevel g = from(granted);
        if (g == null || required == null) {
            return false;
        }
        return g == AccessLevel.OPERATE || g == required;
    }
}
