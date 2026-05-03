package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.config.VoiceMeeterProperties;
import com.si.backend.integration.VoiceMeeterIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 当前正在写入 TTS 的声道集合，用于原声丢弃判断 */
    private final Set<Integer> ttsActiveChannels = ConcurrentHashMap.newKeySet();

    /**
     * 写入源语言音频帧到默认声道（语种尚未识别时使用）。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void writeSourceAudio(byte[] pcmFrame) {
        log.info("[AudioRoutingService] writeSourceAudio start, channel={}, bytes={}",
                properties.getZhChannel(), pcmFrame != null ? pcmFrame.length : 0);
        if (!isReady()) {
            log.debug("[AudioRoutingService] writeSourceAudio skip, not ready");
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        if (ttsActiveChannels.contains(properties.getZhChannel())) {
            log.debug("[AudioRoutingService] writeSourceAudio discard, TTS active on channel={}", properties.getZhChannel());
            return;
        }
        voicemeeter.writeSourceAudio(pcmFrame);
        log.info("[AudioRoutingService] writeSourceAudio end, channel={}, bytes={}",
                properties.getZhChannel(), pcmFrame.length);
    }

    /**
     * 根据检测到的语种，将源语言音频帧写入对应语言声道。
     * 中文路由到 zhChannel，印尼语路由到 idChannel，未知语种回退到 zhChannel。
     *
     * @param pcmFrame     16-bit PCM 音频数据
     * @param detectedLang 已归一化的语种代码（如 "zh-CN" 或 "id"）
     */
    public void writeSourceAudioByLang(byte[] pcmFrame, String detectedLang) {
        log.info("[AudioRoutingService] writeSourceAudioByLang start, lang={}, bytes={}",
                detectedLang, pcmFrame != null ? pcmFrame.length : 0);
        if (!isReady()) {
            log.debug("[AudioRoutingService] writeSourceAudioByLang skip, not ready");
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        int channel = resolveChannelByLang(detectedLang);
        if (ttsActiveChannels.contains(channel)) {
            log.debug("[AudioRoutingService] writeSourceAudioByLang discard, TTS active on channel={}", channel);
            return;
        }
        voicemeeter.writeAudioToChannel(pcmFrame, channel);
        log.info("[AudioRoutingService] writeSourceAudioByLang end, lang={}, channel={}, bytes={}",
                detectedLang, channel, pcmFrame.length);
    }

    /**
     * 写入 TTS 合成音频帧到默认声道（语种未知时使用）。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void writeTargetAudio(byte[] pcmFrame) {
        log.info("[AudioRoutingService] writeTargetAudio start, channel={}, bytes={}",
                properties.getZhChannel(), pcmFrame != null ? pcmFrame.length : 0);
        if (!isReady()) {
            log.debug("[AudioRoutingService] writeTargetAudio skip, not ready");
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        voicemeeter.writeTargetAudio(pcmFrame);
        log.info("[AudioRoutingService] writeTargetAudio end, channel={}, bytes={}",
                properties.getZhChannel(), pcmFrame.length);
    }

    /**
     * 根据目标语种，将 TTS 合成音频帧写入对应语言声道。
     * 中文路由到 zhChannel，印尼语路由到 idChannel，未知语种回退到 zhChannel。
     *
     * @param pcmFrame   16-bit PCM 音频数据
     * @param targetLang 已归一化的目标语种代码（如 "zh-CN" 或 "id"）
     */
    public void writeTargetAudioByLang(byte[] pcmFrame, String targetLang) {
        log.info("[AudioRoutingService] writeTargetAudioByLang start, lang={}, bytes={}",
                targetLang, pcmFrame != null ? pcmFrame.length : 0);
        if (!isReady()) {
            log.debug("[AudioRoutingService] writeTargetAudioByLang skip, not ready");
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        int channel = resolveChannelByLang(targetLang);
        voicemeeter.writeAudioToChannel(pcmFrame, channel);
        log.info("[AudioRoutingService] writeTargetAudioByLang end, lang={}, channel={}, bytes={}",
                targetLang, channel, pcmFrame.length);
    }

    /**
     * 标记指定目标语种的 TTS 开始写入，原声写入同一声道时将被丢弃。
     *
     * @param targetLang 已归一化的目标语种代码
     */
    public void markTtsStart(String targetLang) {
        log.info("[AudioRoutingService] markTtsStart start, lang={}", targetLang);
        int channel = resolveChannelByLang(targetLang);
        ttsActiveChannels.add(channel);
        log.info("[AudioRoutingService] markTtsStart end, lang={}, channel={}", targetLang, channel);
    }

    /**
     * 标记指定目标语种的 TTS 写入结束，恢复原声写入该声道。
     *
     * @param targetLang 已归一化的目标语种代码
     */
    public void markTtsEnd(String targetLang) {
        log.info("[AudioRoutingService] markTtsEnd start, lang={}", targetLang);
        int channel = resolveChannelByLang(targetLang);
        ttsActiveChannels.remove(channel);
        log.info("[AudioRoutingService] markTtsEnd end, lang={}, channel={}", targetLang, channel);
    }

    /**
     * 根据语种代码解析对应的 VoiceMeeter 声道索引。
     * 原声与 TTS 共用同一套通道映射。
     *
     * @param lang 已归一化的语种代码
     * @return 声道索引
     */
    private int resolveChannelByLang(String lang) {
        if (Constants.LANG_ID_SHORT.equalsIgnoreCase(lang)) {
            return properties.getIdChannel();
        }
        return properties.getZhChannel();
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
