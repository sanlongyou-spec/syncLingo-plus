package com.si.backend.security.authorization;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Resolves method-level authorization metadata before falling back to the controller-level default.
 */
public final class AuthorizationSpecResolver {

    private AuthorizationSpecResolver() {
    }

    public static AuthorizationSpec resolve(HandlerMethod handlerMethod) {
        AuthorizationSpec methodSpec = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(),
                AuthorizationSpec.class
        );
        if (methodSpec != null) {
            return methodSpec;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), AuthorizationSpec.class);
    }
}
