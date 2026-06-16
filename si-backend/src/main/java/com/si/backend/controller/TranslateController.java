package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.facade.TranslateFacade;
import com.si.backend.dto.TranslateTextRequest;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 翻译控制器，提供文本翻译 HTTP 接口。
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.TRANSLATE_USE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 400, 401, 403})
@RequestMapping("/api/translate")
@RequiredArgsConstructor
public class TranslateController {

    private final TranslateFacade translateFacade;

    @PostMapping
    public Result<String> translate(@RequestBody TranslateTextRequest request) {
        Long userId = AuthContext.requireActor().userId();
        log.info("[TranslateController] translate start, textLen={}, sourceLang={}, targetLang={}",
                request.getText() != null ? request.getText().length() : 0,
                request.getSourceLang(), request.getTargetLang());
        String result = translateFacade.translate(
                request.getText(),
                request.getSourceLang(),
                request.getTargetLang(),
                userId
        );
        log.info("[TranslateController] translate end, resultLen={}", result != null ? result.length() : 0);
        return Result.ok(result);
    }
}
