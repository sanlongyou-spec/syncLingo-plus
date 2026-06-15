package com.si.backend.config;

import com.si.backend.security.authorization.AuthorizationMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 角色权限执行配置。默认 REPORT_ONLY(只观察不拦截),确认无误拦后再设 app.authz.mode=ENFORCE。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.authz")
public class AuthorizationProperties {

    private AuthorizationMode mode = AuthorizationMode.REPORT_ONLY;
}
