package com.si.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 文档配置。
 *
 * <p>仅在 springdoc 启用时（dev）生效；生产环境通过 {@code springdoc.api-docs.enabled=false}
 * 与 {@code springdoc.swagger-ui.enabled=false} 关闭，避免对公网暴露全部接口（含 admin）。
 *
 * <p>定义 bearer(JWT) 安全方案，使 Swagger UI 出现 "Authorize" 按钮，可携带
 * {@code Authorization: Bearer <token>} 调试受保护接口。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI siBackendOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("syncLingo Backend API")
                        .version("1.0.0")
                        .description("同声传译后端接口文档。受保护接口需点击右上角 Authorize 填入登录返回的 JWT。"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录 /api/auth/login 获取 token，填入即可")));
    }
}
