package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.service.UserVoiceService;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.CloneVoiceResponse;
import com.si.backend.vo.UserVoiceVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * User-owned cloned voice management.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/voices")
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.VOICE_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.OWN,
        expectedStatuses = {200, 400, 401, 403, 404, 502})
public class UserVoiceController {

    private final UserVoiceService userVoiceService;

    @GetMapping
    public Result<List<UserVoiceVo>> listVoices() {
        log.info("[UserVoiceController] listVoices");
        return Result.ok(userVoiceService.listVoices(AuthContext.requireActor()));
    }

    @PostMapping("/clone")
    public Result<CloneVoiceResponse> cloneVoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam("voiceName") String voiceName,
            @RequestParam(value = "language", required = false, defaultValue = "zh") String language,
            @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds
    ) throws IOException {
        log.info("[UserVoiceController] cloneVoice, fileName={}, voiceName={}, language={}, durationSeconds={}",
                file.getOriginalFilename(), voiceName, language, durationSeconds);
        return Result.ok(userVoiceService.cloneVoice(
                AuthContext.requireActor(), file, voiceName, language, durationSeconds));
    }

    @DeleteMapping("/{voiceId}")
    public Result<Void> deleteVoice(@PathVariable String voiceId) {
        log.info("[UserVoiceController] deleteVoice, voiceId={}", voiceId);
        userVoiceService.deleteVoice(AuthContext.requireActor(), voiceId);
        return Result.ok();
    }
}
