package com.si.backend.util;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the authenticated userId set by JwtAuthFilter from the current request thread.
 * Returns null in non-request contexts (async tasks, tests, admin endpoints).
 */
public final class AuthContext {

    private AuthContext() {}

    public static Long currentUserId() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return (Long) attrs.getRequest().getAttribute("authenticatedUserId");
        } catch (Exception ignored) {
            return null;
        }
    }
}
