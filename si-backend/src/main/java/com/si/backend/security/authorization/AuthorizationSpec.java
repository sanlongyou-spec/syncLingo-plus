package com.si.backend.security.authorization;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers an HTTP handler's authorization contract for OpenAPI and the release coverage gate.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AuthorizationSpec {

    IdentityType identity();

    PermissionCode permission();

    ResourceScope scope();

    int[] expectedStatuses();
}
