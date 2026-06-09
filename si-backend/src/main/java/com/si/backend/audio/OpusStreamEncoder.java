package com.si.backend.audio;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条音频流的 Opus 编码器（用于分享页低带宽音频分发）。
 *
 * <p>统一输出 48kHz 单声道 20ms 帧、24kbps、开启 DTX。
 * 输入 PCM 可以是任意采样率（16k 原始麦克风 / 24k TTS），内部线性重采样到 48k 后分帧编码。
 * 每个 (sessionId, lang) 维护一个实例，因 OpusEncoder 有状态，{@link #feed} 方法整体加锁保证线程安全。
 */
@Slf4j
public class OpusStreamEncoder {

    /** Opus 输出采样率，固定 48k（WebCodecs AudioDecoder 兼容性最好） */
    public static final int OUTPUT_SAMPLE_RATE = 48000;
    /** 20ms 帧的样本数（48000 * 0.02） */
    private static final int FRAME_SAMPLES = 960;
    private static final int TARGET_BITRATE = 24000;
    private static final int COMPLEXITY = 5;
    private static final int MAX_PACKET_BYTES = 1500;
    /** DTX/静音帧字节阈值，小于等于此值视为不发送（静音不传） */
    private static final int DTX_MIN_BYTES = 2;

    private final OpusEncoder encoder;
    private final short[] frameBuffer = new short[FRAME_SAMPLES];
    private int frameFill = 0;

    public OpusStreamEncoder() throws OpusException {
        this.encoder = new OpusEncoder(OUTPUT_SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(TARGET_BITRATE);
        encoder.setComplexity(COMPLEXITY);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setUseDTX(true);
    }

    /**
     * 喂入一段 PCM（16-bit），按需重采样到 48k、累积分帧并编码。
     *
     * @param pcm        16-bit 小端 PCM 字节
     * @param inSampleRate 输入采样率（16000 或 24000）
     * @return 本次产出的 Opus 包列表（可能为空；静音 DTX 帧被跳过）
     */
    public synchronized List<byte[]> feed(byte[] pcm, int inSampleRate) {
        List<byte[]> packets = new ArrayList<>();
        if (pcm == null || pcm.length < 2) {
            return packets;
        }
        short[] samples = toShorts(pcm);
        short[] resampled = resampleTo48k(samples, inSampleRate);
        int offset = 0;
        while (offset < resampled.length) {
            int copy = Math.min(FRAME_SAMPLES - frameFill, resampled.length - offset);
            System.arraycopy(resampled, offset, frameBuffer, frameFill, copy);
            frameFill += copy;
            offset += copy;
            if (frameFill == FRAME_SAMPLES) {
                byte[] packet = encodeFrame();
                if (packet != null) {
                    packets.add(packet);
                }
                frameFill = 0;
            }
        }
        return packets;
    }

    private byte[] encodeFrame() {
        byte[] out = new byte[MAX_PACKET_BYTES];
        try {
            int len = encoder.encode(frameBuffer, 0, FRAME_SAMPLES, out, 0, out.length);
            if (len <= DTX_MIN_BYTES) {
                // DTX/静音帧，不发送（客户端表现为静音间隙）
                return null;
            }
            byte[] packet = new byte[len];
            System.arraycopy(out, 0, packet, 0, len);
            return packet;
        } catch (OpusException e) {
            log.warn("[OpusStreamEncoder] encode failed: {}", e.getMessage());
            return null;
        }
    }

    private static short[] toShorts(byte[] pcm) {
        int n = pcm.length / 2;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        }
        return s;
    }

    /** 线性插值重采样到 48k（逐块处理，块边界有微小不连续，对语音可接受）。 */
    private static short[] resampleTo48k(short[] in, int inSampleRate) {
        if (inSampleRate == OUTPUT_SAMPLE_RATE || in.length == 0) {
            return in;
        }
        int outLen = (int) ((long) in.length * OUTPUT_SAMPLE_RATE / inSampleRate);
        if (outLen <= 0) {
            return new short[0];
        }
        short[] out = new short[outLen];
        double step = (double) inSampleRate / OUTPUT_SAMPLE_RATE;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int idx = (int) pos;
            double frac = pos - idx;
            short a = in[Math.min(idx, in.length - 1)];
            short b = in[Math.min(idx + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * frac);
        }
        return out;
    }
}
