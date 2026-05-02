package com.si.backend.controller;

import com.si.backend.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 健康检查控制器，提供服务状态检查接口。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        log.info("[HealthController] health check");
        return Result.ok(Map.of(
                "status", "UP",
                "service", "si-backend"
        ));
    }
}
