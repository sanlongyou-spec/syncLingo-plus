package com.si.backend.service;

import com.si.backend.audio.VoiceMeeterAudioOutput;
import com.si.backend.config.VoiceMeeterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音频输出服务，封装 VoiceMeeter 的音频透传与目标音频输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioOutputService {

    private final VoiceMeeterAudioOutput voiceMeeterAudioOutput;
    private final VoiceMeeterProperties properties;

    /**
     * 透传源语言音频（将 ASR 采集到的原始音频写入 VoiceMeeter）。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void passthroughSourceAudio(byte[] pcmFrame) {
        if (!properties.isEnabled() || pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        voiceMeeterAudioOutput.writeAudio("zh", 16000, pcmFrame);
        log.debug("[AudioOutputService] passthroughSourceAudio, bytes={}", pcmFrame.length);
    }

    /**
     * 输出目标语言音频（TTS 合成音写入 VoiceMeeter）。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void outputTargetAudio(byte[] pcmFrame) {
        if (!properties.isEnabled() || pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        voiceMeeterAudioOutput.writeAudio("id", 24000, pcmFrame);
        log.debug("[AudioOutputService] outputTargetAudio, bytes={}", pcmFrame.length);
    }
}
