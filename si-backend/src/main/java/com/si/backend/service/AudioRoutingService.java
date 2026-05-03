package com.si.backend.service;

import com.si.backend.audio.VoiceMeeterAudioOutput;
import com.si.backend.common.Constants;
import com.si.backend.config.VoiceMeeterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音频路由服务，封装 VoiceMeeter 音频写入。
 * 作为 service 层，对 facade 屏蔽 audio 层细节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioRoutingService {

    private final VoiceMeeterAudioOutput voiceMeeterAudioOutput;
    private final VoiceMeeterProperties properties;

    /**
     * 写入源语言音频帧到对应语言声道。
     *
     * @param pcmFrame    16-bit PCM 音频数据
     * @param sampleRate  采样率
     * @param detectedLang 语种代码（如 "zh" 或 "id"）
     */
    public void writeSourceAudio(byte[] pcmFrame, int sampleRate, String detectedLang) {
        if (!isEnabled() || pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        String lang = normalizeLang(detectedLang);
        voiceMeeterAudioOutput.writeAudio(lang, sampleRate, pcmFrame);
        log.debug("[AudioRoutingService] writeSourceAudio, lang={}, bytes={}", lang, pcmFrame.length);
    }

    /**
     * 写入 TTS 音频帧到对应语言声道。
     *
     * @param pcmFrame   16-bit PCM 音频数据
     * @param sampleRate 采样率
     * @param targetLang 目标语种代码
     */
    public void writeTargetAudio(byte[] pcmFrame, int sampleRate, String targetLang) {
        if (!isEnabled() || pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        String lang = normalizeLang(targetLang);
        voiceMeeterAudioOutput.writeAudio(lang, sampleRate, pcmFrame);
        log.debug("[AudioRoutingService] writeTargetAudio, lang={}, bytes={}", lang, pcmFrame.length);
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "zh";
        }
        if (lang.toLowerCase().startsWith("id")) {
            return "id";
        }
        return "zh";
    }

    /**
     * 检查 VoiceMeeter 功能是否启用。
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
