package com.si.backend.config;

import com.si.backend.config.CorsProperties;
import com.si.backend.security.authorization.AuthorizationEnforcementInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;
    private final AuthorizationEnforcementInterceptor authorizationEnforcementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // P5 角色功能权限执行(默认 ENFORCE,可通过 app.authz.mode 显式降级)
        registry.addInterceptor(authorizationEnforcementInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(corsProperties.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
