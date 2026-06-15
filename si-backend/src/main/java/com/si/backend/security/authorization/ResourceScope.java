package com.si.backend.security.authorization;

/**
 * Resource range an entry point is allowed to address.
 */
public enum ResourceScope {
    NONE,
    PUBLIC,
    SELF,
    OWN,
    OWN_OR_SELF,
    SERVICE,
    ALL
}
