package com.si.backend.service;

import com.si.backend.integration.VoiceMeeterIntegration;
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

    private final VoiceMeeterIntegration voicemeeter;

    /**
     * 透传源语言音频（将 ASR 采集到的原始音频写入 VoiceMeeter Strip）。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void passthroughSourceAudio(byte[] pcmFrame) {
        log.info("[AudioOutputService] passthroughSourceAudio start, bytes={}",
                pcmFrame != null ? pcmFrame.length : 0);
        if (voicemeeter.isInstalled()) {
            voicemeeter.writeSourceAudio(pcmFrame);
        }
        log.info("[AudioOutputService] passthroughSourceAudio end, bytes={}",
                pcmFrame != null ? pcmFrame.length : 0);
    }

    /**
     * 输出目标语言音频（TTS 合成音写入 VoiceMeeter Bus）。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void outputTargetAudio(byte[] pcmFrame) {
        log.info("[AudioOutputService] outputTargetAudio start, bytes={}",
                pcmFrame != null ? pcmFrame.length : 0);
        if (voicemeeter.isInstalled()) {
            voicemeeter.writeTargetAudio(pcmFrame);
        }
        log.info("[AudioOutputService] outputTargetAudio end, bytes={}",
                pcmFrame != null ? pcmFrame.length : 0);
    }
}
