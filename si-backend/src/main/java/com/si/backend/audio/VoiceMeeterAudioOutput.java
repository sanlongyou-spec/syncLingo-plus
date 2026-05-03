package com.si.backend.audio;

import com.si.backend.config.VoiceMeeterProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VoiceMeeter 虚拟设备音频输出。
 * 直接通过 Java Sound API 写入 Windows 虚拟音频设备。
 *
 * <p>配置：
 * <ul>
 *   <li>VoiceMeeter Input    → 目标语言声道（B1）</li>
 *   <li>VoiceMeeter Aux Input → 源语言声道（B2）</li>
 * </ul>
 *
 * <p>VoiceMeeter Potato 路由设置：
 * <ul>
 *   <li>VoiceMeeter Input    → 点亮 B1</li>
 *   <li>VoiceMeeter AUX Input → 点亮 B2</li>
 * </ul>
 */
@Service
public class VoiceMeeterAudioOutput {

    private static final Logger log = LoggerFactory.getLogger(VoiceMeeterAudioOutput.class);

    private static final AudioFormat FORMAT_16K = new AudioFormat(
            16000f, 16, 1, true, false);
    private static final AudioFormat FORMAT_24K = new AudioFormat(
            24000f, 16, 1, true, false);

    private static final int BUFFER_BYTES_16K = 32000;  // 1s @ 16kHz
    private static final int BUFFER_BYTES_24K = 96000;  // 2s @ 24kHz

    private final VoiceMeeterProperties properties;

    private final Map<String, LinkedBlockingQueue<byte[]>> writeQueues = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();

    public VoiceMeeterAudioOutput(VoiceMeeterProperties properties) {
        this.properties = properties;
        log.info("[VoiceMeeterAudio] 初始化，zhDevice={}, idDevice={}",
                properties.getZhDevice(), properties.getIdDevice());
        dumpAvailableMixers();
    }

    private void dumpAvailableMixers() {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        log.info("[VoiceMeeterAudio] 可用混音器数量: {}", infos.length);
        for (Mixer.Info info : infos) {
            log.info("[VoiceMeeterAudio]   - {}", info.getName());
        }
    }

    /**
     * 写入音频到指定语言声道。
     *
     * @param lang       语言标识（"zh" 或 "id"）
     * @param sampleRate 采样率（16000 或 24000）
     * @param pcm        PCM 数据
     */
    public void writeAudio(String lang, int sampleRate, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        if (!properties.isEnabled()) return;

        String key = lang + "-" + sampleRate;
        LinkedBlockingQueue<byte[]> queue = writeQueues.computeIfAbsent(key, k -> {
            LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>(200);
            AtomicBoolean running = new AtomicBoolean(true);
            runningFlags.put(k, running);

            AudioFormat format = sampleRate == 24000 ? FORMAT_24K : FORMAT_16K;
            int bufferBytes = sampleRate == 24000 ? BUFFER_BYTES_24K : BUFFER_BYTES_16K;
            SourceDataLine line = openLine(lang, format, bufferBytes);

            if (line != null) {
                startWriteThread(k, q, running, line);
            }
            return q;
        });

        if (!queue.offer(pcm)) {
            log.warn("[VoiceMeeterAudio] 队列满，丢弃音频 lang={} bytes={}", lang, pcm.length);
        }
    }

    private SourceDataLine openLine(String lang, AudioFormat format, int bufferBytes) {
        String keyword = getDeviceKeyword(lang);
        Mixer.Info target = findMixer(keyword, format);

        try {
            SourceDataLine line;
            if (target != null) {
                line = AudioSystem.getSourceDataLine(format, target);
                log.info("[VoiceMeeterAudio] 打开设备 lang={} device=\"{}\"", lang, target.getName());
            } else {
                line = AudioSystem.getSourceDataLine(format);
                log.warn("[VoiceMeeterAudio] 未找到设备 lang={} keyword=\"{}\"，使用默认设备", lang, keyword);
            }
            line.open(format, bufferBytes);
            line.start();
            return line;
        } catch (LineUnavailableException e) {
            log.error("[VoiceMeeterAudio] 打开设备失败 lang={}: {}", lang, e.getMessage());
            return null;
        }
    }

    private String getDeviceKeyword(String lang) {
        if ("zh".equalsIgnoreCase(lang)) {
            return properties.getZhDevice();
        } else {
            return properties.getIdDevice();
        }
    }

    private Mixer.Info findMixer(String keyword, AudioFormat format) {
        if (keyword == null || keyword.isBlank()) return null;
        String kw = keyword.toLowerCase();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);

        for (Mixer.Info info : infos) {
            String name = info.getName();
            if (name == null || name.startsWith("Port ")) continue;
            if (name.toLowerCase().contains(kw)) {
                if (AudioSystem.getMixer(info).isLineSupported(lineInfo)) {
                    return info;
                }
            }
        }
        log.warn("[VoiceMeeterAudio] 未找到匹配 \"{}\" 的设备", keyword);
        return null;
    }

    private void startWriteThread(String key, LinkedBlockingQueue<byte[]> queue,
                                   AtomicBoolean running, SourceDataLine line) {
        Thread t = new Thread(() -> {
            log.info("[VoiceMeeterAudio] 写线程启动 key={}", key);
            while (running.get()) {
                try {
                    byte[] pcm = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm != null) {
                        line.write(pcm, 0, pcm.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[VoiceMeeterAudio] 写线程异常 key={}: {}", key, e.getMessage());
                }
            }
            try { line.drain(); line.close(); } catch (Exception ignored) {}
            log.info("[VoiceMeeterAudio] 写线程退出 key={}", key);
        }, "voicemeeter-write-" + key);
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[VoiceMeeterAudio] 关闭中...");
        runningFlags.values().forEach(r -> r.set(false));
        writeQueues.clear();
        runningFlags.clear();
    }

    public boolean isReady() {
        return properties.isEnabled();
    }
}
