package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the internal admin API.
 * Set app.admin.api-secret to a non-empty value to require the
 * X-Admin-Secret header on all /api/admin/** requests.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.admin")
public class AppAdminProperties {

    private String apiSecret = "";
}
