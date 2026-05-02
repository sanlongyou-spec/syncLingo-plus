package com.si.backend.service;

import com.si.backend.config.VoiceMeeterProperties;
import com.si.backend.integration.VoiceMeeterIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音频路由服务，封装 VoiceMeeter 音频写入与参数控制。
 * 作为 service 层，对 facade 屏蔽 integration 层细节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioRoutingService {

    private final VoiceMeeterIntegration voicemeeter;
    private final VoiceMeeterProperties properties;

    /**
     * 写入源语言音频帧到 VoiceMeeter Strip 声道。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void writeSourceAudio(byte[] pcmFrame) {
        log.info("[AudioRoutingService] writeSourceAudio start, channel={}, bytes={}",
                properties.getSourceChannel(), pcmFrame != null ? pcmFrame.length : 0);
        if (!isReady()) {
            log.debug("[AudioRoutingService] writeSourceAudio skip, not ready");
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        voicemeeter.writeSourceAudio(pcmFrame);
        log.info("[AudioRoutingService] writeSourceAudio end, channel={}, bytes={}",
                properties.getSourceChannel(), pcmFrame.length);
    }

    /**
     * 写入目标语言音频帧（TTS 合成音）到 VoiceMeeter Bus 声道。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void writeTargetAudio(byte[] pcmFrame) {
        log.info("[AudioRoutingService] writeTargetAudio start, channel={}, bytes={}",
                properties.getTargetChannel(), pcmFrame != null ? pcmFrame.length : 0);
        if (!isReady()) {
            log.debug("[AudioRoutingService] writeTargetAudio skip, not ready");
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        voicemeeter.writeTargetAudio(pcmFrame);
        log.info("[AudioRoutingService] writeTargetAudio end, channel={}, bytes={}",
                properties.getTargetChannel(), pcmFrame.length);
    }

    /**
     * 设置源声道 Strip 增益。
     *
     * @param stripIndex Strip 索引（0=Strip1）
     * @param gainDb     增益 dB（-60 ~ +12）
     */
    public void setStripGain(int stripIndex, float gainDb) {
        log.info("[AudioRoutingService] setStripGain start, stripIndex={}, gainDb={}", stripIndex, gainDb);
        if (!isReady()) {
            log.debug("[AudioRoutingService] setStripGain skip, not ready");
            return;
        }
        voicemeeter.setStripGain(stripIndex, gainDb);
        log.info("[AudioRoutingService] setStripGain end, stripIndex={}, gainDb={}", stripIndex, gainDb);
    }

    /**
     * 设置输出母线 Bus 增益。
     *
     * @param busIndex Bus 索引（0=Bus1）
     * @param gainDb   增益 dB
     */
    public void setBusGain(int busIndex, float gainDb) {
        log.info("[AudioRoutingService] setBusGain start, busIndex={}, gainDb={}", busIndex, gainDb);
        if (!isReady()) {
            log.debug("[AudioRoutingService] setBusGain skip, not ready");
            return;
        }
        voicemeeter.setBusGain(busIndex, gainDb);
        log.info("[AudioRoutingService] setBusGain end, busIndex={}, gainDb={}", busIndex, gainDb);
    }

    /**
     * 检查 VoiceMeeter 是否就绪（已安装 + 已登录）。
     *
     * @return 是否就绪
     */
    public boolean isReady() {
        boolean ready = properties.isEnabled() && voicemeeter.isInstalled() && voicemeeter.isLoggedIn();
        log.debug("[AudioRoutingService] isReady={}", ready);
        return ready;
    }

    /**
     * 检查 VoiceMeeter 功能是否启用（配置层面）。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
