package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.SaveSystemUserInfoRequest;
import com.si.backend.facade.SystemUserInfoFacade;
import com.si.backend.vo.SystemUserImportResultVo;
import com.si.backend.vo.SystemUserInfoVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * System-wide user information endpoints.
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.DIRECTORY_ACCESS,
        scope = com.si.backend.security.authorization.ResourceScope.ALL,
        expectedStatuses = {200, 400, 401, 404})
@RequestMapping("/api/system-users")
@RequiredArgsConstructor
public class SystemUserInfoController {

    private final SystemUserInfoFacade facade;

    @GetMapping
    public Result<List<SystemUserInfoVo>> list(@RequestParam(required = false) String keyword) {
        log.info("[SystemUserInfoController] list start, keywordLen={}", keyword != null ? keyword.length() : 0);
        List<SystemUserInfoVo> result = facade.list(keyword);
        log.info("[SystemUserInfoController] list end, count={}", result.size());
        return Result.ok(result);
    }

    @PostMapping
    public Result<SystemUserInfoVo> create(@RequestBody SaveSystemUserInfoRequest request) {
        log.info("[SystemUserInfoController] create start, email={}", request != null ? request.getEmail() : null);
        SystemUserInfoVo result = facade.create(request);
        log.info("[SystemUserInfoController] create end, id={}", result.getId());
        return Result.ok(result);
    }

    @PutMapping("/{id}")
    public Result<SystemUserInfoVo> update(@PathVariable Long id, @RequestBody SaveSystemUserInfoRequest request) {
        log.info("[SystemUserInfoController] update start, id={}", id);
        SystemUserInfoVo result = facade.update(id, request);
        log.info("[SystemUserInfoController] update end, id={}", id);
        return Result.ok(result);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("[SystemUserInfoController] delete start, id={}", id);
        facade.delete(id);
        log.info("[SystemUserInfoController] delete end, id={}", id);
        return Result.ok();
    }

    @PostMapping("/import")
    public Result<SystemUserImportResultVo> importExcel(@RequestParam("file") MultipartFile file) {
        log.info("[SystemUserInfoController] importExcel start, fileName={}",
                file != null ? file.getOriginalFilename() : null);
        SystemUserImportResultVo result = facade.importExcel(file);
        log.info("[SystemUserInfoController] importExcel end, total={}", result.getTotalCount());
        return Result.ok(result);
    }
}
