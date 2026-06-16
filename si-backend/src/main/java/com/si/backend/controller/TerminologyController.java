package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.SaveTerminologyRequest;
import com.si.backend.facade.TerminologyFacade;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.TerminologyVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 术语控制器，提供术语新增、查询与启停接口。
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.TERMINOLOGY_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 400, 401, 403, 404})
@RequestMapping("/api/terminology")
@RequiredArgsConstructor
public class TerminologyController {

    private final TerminologyFacade facade;

    @PostMapping
    public Result<TerminologyVo> createTerminology(@RequestBody SaveTerminologyRequest request) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] createTerminology start, userId={}, category={}", userId, request.getCategory());
        TerminologyVo created = facade.create(userId, request);
        log.info("[TerminologyController] createTerminology end, id={}", created.getId());
        return Result.ok(created);
    }

    @GetMapping
    public Result<List<TerminologyVo>> listTerminologies(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled
    ) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] listTerminologies start, keywordLen={}, enabled={}",
                keyword != null ? keyword.length() : 0, enabled);
        List<TerminologyVo> terminologies = facade.list(userId, keyword, enabled);
        log.info("[TerminologyController] listTerminologies end, count={}", terminologies.size());
        return Result.ok(terminologies);
    }

    @PostMapping("/batch")
    public Result<List<TerminologyVo>> createTerminologies(@RequestBody List<SaveTerminologyRequest> requests) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] createTerminologies start, count={}",
                requests != null ? requests.size() : 0);
        List<TerminologyVo> created = facade.createBatch(userId, requests);
        log.info("[TerminologyController] createTerminologies end, count={}", created.size());
        return Result.ok(created);
    }

    @PostMapping("/import")
    public Result<com.si.backend.vo.TerminologyImportResultVo> importExcel(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] importExcel start, userId={}, fileName={}",
                userId, file != null ? file.getOriginalFilename() : null);
        com.si.backend.vo.TerminologyImportResultVo result = facade.importExcel(userId, file);
        log.info("[TerminologyController] importExcel end, userId={}, total={}", userId, result.getTotalCount());
        return Result.ok(result);
    }

    @PatchMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable Long id, @RequestParam Boolean enabled) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] updateEnabled start, id={}, enabled={}", id, enabled);
        facade.updateEnabled(id, userId, enabled);
        log.info("[TerminologyController] updateEnabled end, id={}", id);
        return Result.ok();
    }

    @PutMapping("/{id}")
    public Result<Void> updateTerminology(@PathVariable Long id, @RequestBody SaveTerminologyRequest request) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] updateTerminology start, id={}", id);
        facade.update(id, userId, request);
        log.info("[TerminologyController] updateTerminology end, id={}", id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteTerminology(@PathVariable Long id) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TerminologyController] deleteTerminology start, id={}", id);
        facade.delete(id, userId);
        log.info("[TerminologyController] deleteTerminology end, id={}", id);
        return Result.ok();
    }
}
