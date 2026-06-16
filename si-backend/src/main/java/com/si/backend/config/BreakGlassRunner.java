package com.si.backend.config;

import com.si.backend.service.BreakGlassService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P6 Break-glass 离线 CLI 入口。仅当启动参数含 {@code --break-glass} 时执行,执行后进程退出;
 * 正常启动不带该参数则为空操作,不影响 Web 服务。
 *
 * <p>建议调用(不绑定 Web 端口,避免与运行实例冲突):
 * <pre>
 * java -jar app.jar --break-glass --username=admin --ticket=OPS-123 --reason="locked out" \
 *      --spring.main.web-application-type=none
 * # 密码可经 --password=... 或环境变量 BREAK_GLASS_PASSWORD 传入(优先 --password)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BreakGlassRunner implements ApplicationRunner {

    private static final String FLAG = "break-glass";
    private static final String ENV_PASSWORD = "BREAK_GLASS_PASSWORD";

    private final BreakGlassService breakGlassService;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(FLAG)) {
            return; // 正常启动:不触发
        }
        int exitCode = 0;
        try {
            String password = firstOption(args, "password");
            if (password == null || password.isEmpty()) {
                password = System.getenv(ENV_PASSWORD);
            }
            String summary = breakGlassService.restoreAdmin(
                    firstOption(args, "username"),
                    firstOption(args, "ticket"),
                    firstOption(args, "reason"),
                    password);
            log.warn("[BreakGlassRunner] {}", summary);
        } catch (Exception e) {
            log.error("[BreakGlassRunner] break-glass failed: {}", e.getMessage());
            exitCode = 1;
        }
        // 应急维护调用,执行后退出,避免以 break-glass 模式继续提供服务。
        System.exit(exitCode);
    }

    private static String firstOption(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
