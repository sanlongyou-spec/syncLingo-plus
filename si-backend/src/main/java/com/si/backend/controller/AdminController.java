package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.Result;
import com.si.backend.config.AppAdminProperties;
import com.si.backend.service.InterpretationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";

    private final InterpretationResultService interpretationResultService;
    private final AppAdminProperties adminProperties;

    /**
     * Rebuild missing embeddings for interpretation results.
     * Requires X-Admin-Secret header when app.admin.api-secret is configured.
     */
    @PostMapping("/embeddings/rebuild")
    public Result<Map<String, Object>> rebuildEmbeddings(
            @RequestHeader(value = ADMIN_SECRET_HEADER, required = false) String secret,
            @RequestParam(defaultValue = "200") int batchLimit) {
        ensureAuthorized(secret);
        log.info("[AdminController] rebuildEmbeddings start, batchLimit={}", batchLimit);
        int created = interpretationResultService.rebuildEmbeddings(batchLimit);
        log.info("[AdminController] rebuildEmbeddings done, created={}", created);
        return Result.ok(Map.of("created", created, "batchLimit", batchLimit));
    }

    private void ensureAuthorized(String secret) {
        String configured = adminProperties.getApiSecret();
        if (configured == null || configured.isBlank()) {
            log.warn("[AdminController] app.admin.api-secret is not set; admin endpoint is unprotected.");
            return;
        }
        if (!configured.equals(secret)) {
            log.warn("[AdminController] unauthorized admin request.");
            throw BizException.of(Constants.HTTP_UNAUTHORIZED, "Admin 接口未授权");
        }
    }
}
