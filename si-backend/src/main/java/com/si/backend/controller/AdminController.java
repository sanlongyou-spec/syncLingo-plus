package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.Result;
import com.si.backend.config.AppAdminProperties;
import com.si.backend.service.ContentEmbeddingService;
import com.si.backend.service.InterpretationResultService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.ADMIN_SECRET,
        permission = com.si.backend.security.authorization.PermissionCode.OPS_EXECUTE,
        scope = com.si.backend.security.authorization.ResourceScope.ALL,
        expectedStatuses = {200, 401, 404})
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";

    private final InterpretationResultService interpretationResultService;
    private final ContentEmbeddingService contentEmbeddingService;
    private final com.si.backend.service.HierarchicalSummaryService hierarchicalSummaryService;
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

    /**
     * Rebuild missing embeddings for all content types:
     * meeting summaries, speaker summaries, file summaries, file content, action items.
     * batchPerType controls how many candidates per type are processed per call.
     */
    @PostMapping("/embeddings/rebuild-all")
    public Result<Map<String, Object>> rebuildAllEmbeddings(
            @RequestHeader(value = ADMIN_SECRET_HEADER, required = false) String secret,
            @RequestParam(defaultValue = "50") int batchPerType) {
        ensureAuthorized(secret);
        log.info("[AdminController] rebuildAllEmbeddings start, batchPerType={}", batchPerType);
        int resultCreated = interpretationResultService.rebuildEmbeddings(batchPerType);
        int contentCreated = contentEmbeddingService.rebuildBatch(batchPerType);
        int total = resultCreated + contentCreated;
        log.info("[AdminController] rebuildAllEmbeddings done, results={}, content={}, total={}",
                resultCreated, contentCreated, total);
        return Result.ok(Map.of(
                "resultEmbeddings", resultCreated,
                "contentEmbeddings", contentCreated,
                "total", total,
                "batchPerType", batchPerType));
    }

    /** P2-7: build/refresh the cross-meeting overview node for a user. */
    @PostMapping("/embeddings/rebuild-overview")
    public Result<Map<String, Object>> rebuildOverview(
            @RequestHeader(value = ADMIN_SECRET_HEADER, required = false) String secret,
            @RequestParam long userId) {
        ensureAuthorized(secret);
        log.info("[AdminController] rebuildOverview start, userId={}", userId);
        int aggregated = hierarchicalSummaryService.rebuildForUser(userId);
        log.info("[AdminController] rebuildOverview done, userId={}, aggregated={}", userId, aggregated);
        return Result.ok(Map.of("userId", userId, "aggregatedMeetings", aggregated));
    }

    @GetMapping("/logs/download")
    public void downloadLogs(
            @RequestHeader(value = ADMIN_SECRET_HEADER, required = false) String secret,
            HttpServletResponse response) throws IOException {
        ensureAuthorized(secret);
        Path logFile = Path.of("/app/logs/app.log");
        if (!Files.exists(logFile)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "日志文件不存在，请确认服务已重启");
            return;
        }
        String filename = "si-backend-" + LocalDate.now() + ".log";
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        log.info("[AdminController] logs download requested, file={}", logFile);
        Files.copy(logFile, response.getOutputStream());
    }

    private void ensureAuthorized(String secret) {
        String configured = adminProperties.getApiSecret();
        if (configured == null || configured.isBlank()) {
            log.error("[AdminController] app.admin.api-secret is not configured — all admin requests rejected.");
            throw BizException.of(Constants.HTTP_UNAUTHORIZED, "Admin 接口未配置 api-secret，拒绝访问");
        }
        if (!configured.equals(secret)) {
            log.warn("[AdminController] unauthorized admin request.");
            throw BizException.of(Constants.HTTP_UNAUTHORIZED, "Admin 接口未授权");
        }
    }
}
