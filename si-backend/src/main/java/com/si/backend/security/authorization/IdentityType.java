package com.si.backend.security.authorization;

/**
 * Identity categories accepted by permission-classified entry points.
 */
public enum IdentityType {
    ANONYMOUS,
    USER,
    SERVICE,
    ADMIN_SECRET,
    BOT_FRAMEWORK,
    SYSTEM
}
