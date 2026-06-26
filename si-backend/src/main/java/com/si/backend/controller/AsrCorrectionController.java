package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.MineAsrCorrectionRequest;
import com.si.backend.facade.AsrCorrectionFacade;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.AsrCorrectionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ASR 错词库控制器：查看累积错词、会后用"文档×ASR"挖词入库。
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.TERMINOLOGY_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 400, 401, 403})
@RequestMapping("/api/asr-corrections")
@RequiredArgsConstructor
public class AsrCorrectionController {

    private final AsrCorrectionFacade facade;

    /** 列出当前账号累积的错词(供"瞄一眼"汇总)。 */
    @GetMapping
    public Result<List<AsrCorrectionVo>> list() {
        Long userId = AuthContext.requireActor().userId();
        log.info("[AsrCorrectionController] list start, userId={}", userId);
        List<AsrCorrectionVo> list = facade.list(userId);
        log.info("[AsrCorrectionController] list end, userId={}, count={}", userId, list.size());
        return Result.ok(list);
    }

    /** 会后:用会议转写 × 参考文件挖掘 ASR 错词并自动入库,返回入库条数。 */
    @PostMapping("/mine")
    public Result<Integer> mine(@RequestBody MineAsrCorrectionRequest request) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[AsrCorrectionController] mine start, userId={}", userId);
        int stored = facade.mine(userId, request.getTranscript(), request.getDocument());
        log.info("[AsrCorrectionController] mine end, userId={}, stored={}", userId, stored);
        return Result.ok(stored);
    }
}
