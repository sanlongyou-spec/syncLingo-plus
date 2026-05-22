package com.si.backend.facade;

import com.si.backend.dto.SaveSpeakerIdentityRequest;
import com.si.backend.service.VoiceCloneService;
import com.si.backend.service.SpeakerIdentityService;
import com.si.backend.entity.UserVoice;
import com.si.backend.vo.CloneVoiceResponse;
import com.si.backend.vo.UserVoiceVo;
import com.si.backend.vo.SpeakerIdentityVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 音色克隆门面层，聚合 VoiceCloneService，统一对外提供音色克隆能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceCloneFacade {

    private final VoiceCloneService voiceCloneService;
    private final SpeakerIdentityService speakerIdentityService;

    public CloneVoiceResponse cloneAndSaveVoice(Long userId, String voiceName, String audioSample, String language) {
        log.info("[VoiceCloneFacade] cloneAndSaveVoice start, userId={}, voiceName={}, language={}, audioSampleLen={}",
                userId, voiceName, language, audioSample != null ? audioSample.length() : 0);
        UserVoice voice = voiceCloneService.cloneVoice(userId, voiceName, audioSample, language);
        CloneVoiceResponse response = CloneVoiceResponse.builder()
                .voiceId(voice.getVoiceId())
                .voiceName(voice.getVoiceName())
                .durationSeconds(voice.getDurationSeconds())
                .createTime(voice.getCreateTime() != null ?
                        voice.getCreateTime().toString() : null)
                .build();
        log.info("[VoiceCloneFacade] cloneAndSaveVoice end, userId={}, voiceId={}, voiceName={}",
                userId, voice.getVoiceId(), voice.getVoiceName());
        return response;
    }

    public UserVoiceVo getUserVoice(Long userId) {
        log.info("[VoiceCloneFacade] getUserVoice, userId={}", userId);
        UserVoice voice = voiceCloneService.getUserVoice(userId);
        log.info("[VoiceCloneFacade] getUserVoice end, userId={}, found={}", userId, voice != null);
        if (voice == null) {
            return null;
        }
        return UserVoiceVo.builder()
                .voiceId(voice.getVoiceId())
                .voiceName(voice.getVoiceName())
                .durationSeconds(voice.getDurationSeconds())
                .authorized(voice.getAuthorized())
                .scope(voice.getScope())
                .disabled(voice.getDisabled())
                .createTime(voice.getCreateTime() != null ? voice.getCreateTime().toString() : null)
                .updateTime(voice.getUpdateTime() != null ? voice.getUpdateTime().toString() : null)
                .build();
    }

    public void deleteUserVoice(Long userId) {
        log.info("[VoiceCloneFacade] deleteUserVoice start, userId={}", userId);
        voiceCloneService.deleteUserVoice(userId);
        log.info("[VoiceCloneFacade] deleteUserVoice end, userId={}", userId);
    }

    public void updateAuthorization(String voiceId, Boolean authorized, Boolean disabled, String scope) {
        log.info("[VoiceCloneFacade] updateAuthorization start, voiceId={}, authorized={}, disabled={}, scope={}",
                voiceId, authorized, disabled, scope);
        voiceCloneService.updateAuthorization(voiceId, authorized, disabled, scope);
        log.info("[VoiceCloneFacade] updateAuthorization end, voiceId={}", voiceId);
    }

    public java.util.List<SpeakerIdentityVo> listSpeakerIdentities() {
        log.info("[VoiceCloneFacade] listSpeakerIdentities start");
        java.util.List<SpeakerIdentityVo> identities = speakerIdentityService.listIdentities();
        log.info("[VoiceCloneFacade] listSpeakerIdentities end, count={}", identities.size());
        return identities;
    }

    public SpeakerIdentityVo saveSpeakerIdentity(Long id, SaveSpeakerIdentityRequest request) {
        log.info("[VoiceCloneFacade] saveSpeakerIdentity start, id={}, personName={}", id, request.getPersonName());
        SpeakerIdentityVo saved = speakerIdentityService.saveIdentity(id, request);
        log.info("[VoiceCloneFacade] saveSpeakerIdentity end, id={}", saved != null ? saved.getId() : null);
        return saved;
    }

    public void deleteSpeakerIdentity(Long id) {
        log.info("[VoiceCloneFacade] deleteSpeakerIdentity start, id={}", id);
        speakerIdentityService.deleteIdentity(id);
        log.info("[VoiceCloneFacade] deleteSpeakerIdentity end, id={}", id);
    }

}
