package com.si.backend.facade;

import com.si.backend.service.VoiceCloneService;
import com.si.backend.entity.UserVoice;
import com.si.backend.vo.CloneVoiceResponse;
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

    public UserVoice getUserVoice(Long userId) {
        log.info("[VoiceCloneFacade] getUserVoice, userId={}", userId);
        UserVoice voice = voiceCloneService.getUserVoice(userId);
        log.info("[VoiceCloneFacade] getUserVoice end, userId={}, found={}", userId, voice != null);
        return voice;
    }

    public void deleteUserVoice(Long userId) {
        log.info("[VoiceCloneFacade] deleteUserVoice start, userId={}", userId);
        voiceCloneService.deleteUserVoice(userId);
        log.info("[VoiceCloneFacade] deleteUserVoice end, userId={}", userId);
    }
}
