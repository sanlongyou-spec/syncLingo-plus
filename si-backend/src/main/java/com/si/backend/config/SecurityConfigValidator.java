package com.si.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置启动校验(权限方案 P0)。
 *
 * <p>生产环境(prod profile)启动时,若关键密钥为默认值/空/过短则<b>拒绝启动</b>——
 * 否则攻击者可用说明书默认密钥伪造任意用户(含管理员)token,使后续所有归属/IDOR 修复失效。
 *
 * <p>dev/test profile 仅打 WARN,不阻塞本地开发与单元测试。
 *
 * <p>注意:本守卫只在 prod profile 生效,服务器必须以 {@code SPRING_PROFILES_ACTIVE=prod} 启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityConfigValidator {

    /** application.yml 中 JWT_SECRET 的出厂默认值,生产必须覆盖。 */
    static final String DEFAULT_JWT_SECRET = "change_this_secret_to_random_32_plus_chars";
    /** HMAC-SHA256 密钥的最小可接受长度(字节)。 */
    static final int MIN_JWT_SECRET_LENGTH = 32;

    private final Environment environment;
    private final JwtProperties jwtProperties;

    @PostConstruct
    public void validate() {
        boolean prod = isProdProfile();
        List<String> problems = collectProblems();

        if (problems.isEmpty()) {
            log.info("[SecurityConfigValidator] security config OK, prod={}", prod);
            return;
        }
        if (prod) {
            // 抛出会中止 Spring 上下文初始化 → 进程启动失败(fail-fast)
            throw new IllegalStateException(
                    "[SecurityConfigValidator] 生产环境安全配置不合规,拒绝启动: " + String.join("; ", problems));
        }
        log.warn("[SecurityConfigValidator] 安全配置存在弱项(非 prod profile,放行启动): {}", problems);
    }

    private List<String> collectProblems() {
        List<String> problems = new ArrayList<>();
        validateJwtSecret(problems);
        requireNonBlank(problems, "app.admin.api-secret(ADMIN_API_SECRET)");
        requireNonBlank(problems, "audio.record.api-secret(TEAMS_BOT_API_SECRET)");
        validateDatabasePassword(problems);
        return problems;
    }

    private void validateDatabasePassword(List<String> problems) {
        String password = environment.getProperty("spring.datasource.password");
        if (password == null || password.isBlank()) {
            problems.add("Database password is not configured (DB_PASSWORD)");
        } else if ("ysl666".equals(password)) {
            problems.add("Database password still uses the repository default");
        }
    }

    private void validateJwtSecret(List<String> problems) {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            problems.add("JWT secret 未配置(JWT_SECRET)");
        } else if (DEFAULT_JWT_SECRET.equals(secret)) {
            problems.add("JWT secret 仍为出厂默认值,必须覆盖 JWT_SECRET");
        } else if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_JWT_SECRET_LENGTH) {
            problems.add("JWT secret 过短(< " + MIN_JWT_SECRET_LENGTH + " 字节)");
        }
    }

    private void requireNonBlank(List<String> problems, String propertyKey) {
        String key = propertyKey.substring(0, propertyKey.indexOf('('));
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            problems.add(propertyKey + " 未配置");
        }
    }

    private boolean isProdProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }
}
