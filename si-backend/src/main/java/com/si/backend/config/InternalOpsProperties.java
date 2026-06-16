package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for internal operations endpoints.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.internal-ops")
public class InternalOpsProperties {
    private String apiSecret = "";
    private List<String> allowedIps = new ArrayList<>();
}
