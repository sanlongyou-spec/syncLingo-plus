package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.facade.VoiceCloneFacade;
import com.si.backend.vo.CloneVoiceResponse;
import com.si.backend.entity.UserVoice;
import com.si.backend.dto.CloneVoiceRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 音色控制器，提供音色克隆的创建、查询、删除接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceCloneFacade facade;

    @PostMapping("/clone")
    public Result<CloneVoiceResponse> cloneVoice(@Valid @RequestBody CloneVoiceRequest request) {
        log.info("[VoiceController] cloneVoice start, userId={}, voiceName={}",
                request.getUserId(), request.getVoiceName());
        CloneVoiceResponse response = facade.cloneAndSaveVoice(
                request.getUserId(),
                request.getVoiceName(),
                request.getAudioSample(),
                request.getLanguage()
        );
        log.info("[VoiceController] cloneVoice end, userId={}, voiceId={}", request.getUserId(), response.getVoiceId());
        return Result.ok(response);
    }

    @GetMapping("/{userId}")
    public Result<UserVoice> getUserVoice(@PathVariable Long userId) {
        log.info("[VoiceController] getUserVoice start, userId={}", userId);
        UserVoice voice = facade.getUserVoice(userId);
        log.info("[VoiceController] getUserVoice end, userId={}, found={}", userId, voice != null);
        return Result.ok(voice);
    }

    @DeleteMapping("/{userId}")
    public Result<Void> deleteUserVoice(@PathVariable Long userId) {
        log.info("[VoiceController] deleteUserVoice start, userId={}", userId);
        facade.deleteUserVoice(userId);
        log.info("[VoiceController] deleteUserVoice end, userId={}", userId);
        return Result.ok();
    }
}
