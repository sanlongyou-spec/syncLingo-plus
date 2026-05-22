package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.facade.VoiceCloneFacade;
import com.si.backend.vo.CloneVoiceResponse;
import com.si.backend.dto.CloneVoiceRequest;
import com.si.backend.dto.SaveSpeakerIdentityRequest;
import com.si.backend.vo.SpeakerIdentityVo;
import com.si.backend.vo.UserVoiceVo;
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
    public Result<UserVoiceVo> getUserVoice(@PathVariable Long userId) {
        log.info("[VoiceController] getUserVoice start, userId={}", userId);
        UserVoiceVo voice = facade.getUserVoice(userId);
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

    @PatchMapping("/{voiceId}/authorization")
    public Result<Void> updateAuthorization(
            @PathVariable String voiceId,
            @RequestParam Boolean authorized,
            @RequestParam Boolean disabled,
            @RequestParam(required = false) String scope
    ) {
        log.info("[VoiceController] updateAuthorization start, voiceId={}, authorized={}, disabled={}, scope={}",
                voiceId, authorized, disabled, scope);
        facade.updateAuthorization(voiceId, authorized, disabled, scope);
        log.info("[VoiceController] updateAuthorization end, voiceId={}", voiceId);
        return Result.ok();
    }

    @GetMapping("/speaker-identities")
    public Result<java.util.List<SpeakerIdentityVo>> listSpeakerIdentities() {
        log.info("[VoiceController] listSpeakerIdentities start");
        java.util.List<SpeakerIdentityVo> identities = facade.listSpeakerIdentities();
        log.info("[VoiceController] listSpeakerIdentities end, count={}", identities.size());
        return Result.ok(identities);
    }

    @PostMapping("/speaker-identities")
    public Result<SpeakerIdentityVo> createSpeakerIdentity(@Valid @RequestBody SaveSpeakerIdentityRequest request) {
        log.info("[VoiceController] createSpeakerIdentity start, personName={}", request.getPersonName());
        SpeakerIdentityVo identity = facade.saveSpeakerIdentity(null, request);
        log.info("[VoiceController] createSpeakerIdentity end, id={}", identity != null ? identity.getId() : null);
        return Result.ok(identity);
    }

    @PutMapping("/speaker-identities/{id}")
    public Result<SpeakerIdentityVo> updateSpeakerIdentity(
            @PathVariable Long id,
            @Valid @RequestBody SaveSpeakerIdentityRequest request
    ) {
        log.info("[VoiceController] updateSpeakerIdentity start, id={}, personName={}", id, request.getPersonName());
        SpeakerIdentityVo identity = facade.saveSpeakerIdentity(id, request);
        log.info("[VoiceController] updateSpeakerIdentity end, id={}", id);
        return Result.ok(identity);
    }

    @DeleteMapping("/speaker-identities/{id}")
    public Result<Void> deleteSpeakerIdentity(@PathVariable Long id) {
        log.info("[VoiceController] deleteSpeakerIdentity start, id={}", id);
        facade.deleteSpeakerIdentity(id);
        log.info("[VoiceController] deleteSpeakerIdentity end, id={}", id);
        return Result.ok();
    }

}
