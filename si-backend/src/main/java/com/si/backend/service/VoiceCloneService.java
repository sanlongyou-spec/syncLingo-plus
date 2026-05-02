package com.si.backend.service;

import com.si.backend.entity.UserVoice;
import com.si.backend.mapper.UserVoiceMapper;
import com.si.backend.integration.CartesiaTtsIntegration;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

/**
 * 音色克隆服务，管理音色克隆业务流程与数据库持久化。
 */
@Slf4j
@Service
public class VoiceCloneService {

    private final UserVoiceMapper userVoiceMapper;
    private final CartesiaTtsIntegration cartesiaIntegration;

    public VoiceCloneService(UserVoiceMapper userVoiceMapper, CartesiaTtsIntegration cartesiaIntegration) {
        this.userVoiceMapper = userVoiceMapper;
        this.cartesiaIntegration = cartesiaIntegration;
    }

    /**
     * 克隆音色：上传音频样本 → Cartesia API → 获取音色 ID → 持久化到数据库。
     *
     * @param userId              用户 ID
     * @param voiceName           音色名称
     * @param audioSampleBase64   音频样本（Base64 编码）
     * @param language            音色语言代码（zh / id / en）
     * @return 克隆后的音色实体
     */
    @Transactional
    public UserVoice cloneVoice(Long userId, String voiceName, String audioSampleBase64, String language) {
        log.info("[VoiceCloneService] cloneVoice start, userId={}, voiceName={}, language={}, audioSampleLen={}",
                userId, voiceName, language, audioSampleBase64 != null ? audioSampleBase64.length() : 0);

        if (language == null || language.isBlank()) {
            language = Constants.LANG_CLONE_ZH;
        }

        byte[] audioSample;
        try {
            audioSample = Base64.getDecoder().decode(audioSampleBase64);
        } catch (Exception e) {
            log.error("[VoiceCloneService] cloneVoice base64 decode error, userId={}", userId, e);
            throw BizException.of(ErrorCode.VOICE_SAMPLE_INVALID, "音频样本 Base64 解码失败");
        }

        if (audioSample.length < Constants.MIN_AUDIO_SAMPLE_BYTES) {
            log.warn("[VoiceCloneService] cloneVoice sample too short, userId={}, length={}",
                    userId, audioSample.length);
            throw BizException.of(ErrorCode.VOICE_SAMPLE_TOO_SHORT,
                    "音色样本时长不足，最少需要 0.5 秒音频");
        }

        long ttsStart = System.currentTimeMillis();
        String voiceId = cartesiaIntegration.createVoice(audioSample, voiceName, language);
        long ttsCost = System.currentTimeMillis() - ttsStart;
        log.info("[VoiceCloneService] createVoice cost, userId={}, voiceId={}, costMs={}",
                userId, voiceId, ttsCost);

        UserVoice userVoice = new UserVoice();
        userVoice.setUserId(userId);
        userVoice.setVoiceId(voiceId);
        userVoice.setVoiceName(voiceName);
        userVoice.setDurationSeconds(audioSample.length / (Constants.DEFAULT_SAMPLE_RATE_ASR * 2));
        userVoiceMapper.insert(userVoice);

        log.info("[VoiceCloneService] cloneVoice end, userId={}, voiceId={}, voiceName={}, durationSec={}",
                userId, voiceId, voiceName, userVoice.getDurationSeconds());
        return userVoice;
    }

    /**
     * 获取用户当前克隆音色。
     *
     * @param userId 用户 ID
     * @return 音色实体，若不存在返回 null
     */
    public UserVoice getUserVoice(Long userId) {
        log.info("[VoiceCloneService] getUserVoice start, userId={}", userId);
        UserVoice voice = userVoiceMapper.findByUserId(userId);
        log.info("[VoiceCloneService] getUserVoice end, userId={}, found={}", userId, voice != null);
        return voice;
    }

    /**
     * 根据用户 ID 获取音色 ID，若未克隆则抛异常。
     *
     * @param userId 用户 ID
     * @return 音色 ID
     */
    public String getVoiceIdByUserId(Long userId) {
        log.info("[VoiceCloneService] getVoiceIdByUserId start, userId={}", userId);
        UserVoice voice = userVoiceMapper.findByUserId(userId);
        if (voice == null) {
            log.warn("[VoiceCloneService] getVoiceIdByUserId voice not found, userId={}", userId);
            throw BizException.of(ErrorCode.TTS_VOICE_NOT_FOUND, "用户音色不存在");
        }
        log.info("[VoiceCloneService] getVoiceIdByUserId end, userId={}, voiceId={}", userId, voice.getVoiceId());
        return voice.getVoiceId();
    }

    /**
     * 删除用户克隆音色。
     *
     * @param userId 用户 ID
     */
    @Transactional
    public void deleteUserVoice(Long userId) {
        log.info("[VoiceCloneService] deleteUserVoice start, userId={}", userId);
        userVoiceMapper.deleteByUserId(userId);
        log.info("[VoiceCloneService] deleteUserVoice end, userId={}", userId);
    }
}
