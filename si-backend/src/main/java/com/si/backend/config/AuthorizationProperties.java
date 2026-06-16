package com.si.backend.config;

import com.si.backend.security.authorization.AuthorizationMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 角色权限执行配置。默认 ENFORCE;如需灰度回滚,显式设置 app.authz.mode=REPORT_ONLY。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.authz")
public class AuthorizationProperties {

    private AuthorizationMode mode = AuthorizationMode.ENFORCE;
}
