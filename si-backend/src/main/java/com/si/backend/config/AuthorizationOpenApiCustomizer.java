package com.si.backend.config;

import com.si.backend.security.authorization.AuthorizationSpec;
import com.si.backend.security.authorization.AuthorizationSpecResolver;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;

/**
 * Adds the registered authorization contract to every Swagger/OpenAPI operation.
 */
@Component
public class AuthorizationOpenApiCustomizer implements OperationCustomizer {

    public static final String EXT_IDENTITY = "x-identity";
    public static final String EXT_PERMISSION = "x-permission";
    public static final String EXT_RESOURCE_SCOPE = "x-resource-scope";
    public static final String EXT_EXPECTED_STATUSES = "x-expected-status-codes";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        AuthorizationSpec spec = AuthorizationSpecResolver.resolve(handlerMethod);
        if (spec == null) {
            return operation;
        }
        return applyAuthorizationExtensions(operation, spec);
    }

    /**
     * Applies the authorization metadata used by Swagger and the release coverage gate.
     */
    public static Operation applyAuthorizationExtensions(Operation operation, AuthorizationSpec spec) {
        operation.addExtension(EXT_IDENTITY, spec.identity().name());
        operation.addExtension(EXT_PERMISSION, spec.permission().name());
        operation.addExtension(EXT_RESOURCE_SCOPE, spec.scope().name());
        operation.addExtension(
                EXT_EXPECTED_STATUSES,
                Arrays.stream(spec.expectedStatuses()).boxed().toList()
        );
        return operation;
    }
}
