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

    private final FrameEncoderFactory encoderFactory;
    private FrameEncoder encoder;
    private final short[] frameBuffer = new short[FRAME_SAMPLES];
    private int frameFill = 0;

    // 跨块连续重采样状态：保留小数读取位置与上一块末样本，消除“逐块相位重置”导致的周期性突变(怪音/音乐声)
    private int resampleRate = 0;      // 当前输入采样率；变化时重置相位
    private double resamplePos = 0.0;  // 下一个输出样本在当前输入块中的读取位置(可为负，表示落在上一块末样本与本块首样本之间)
    private short resamplePrev = 0;    // 上一块的末样本(作为 index=-1)

    public OpusStreamEncoder() throws OpusException {
        this(OpusStreamEncoder::createConcentusEncoder);
    }

    OpusStreamEncoder(FrameEncoderFactory encoderFactory) throws OpusException {
        this.encoderFactory = encoderFactory;
        this.encoder = encoderFactory.create();
    }

    private static FrameEncoder createConcentusEncoder() throws OpusException {
        OpusEncoder encoder = new OpusEncoder(OUTPUT_SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(TARGET_BITRATE);
        encoder.setComplexity(COMPLEXITY);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setUseDTX(true);
        return encoder::encode;
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
            log.warn("[OpusStreamEncoder] encode failed, reset encoder: {}", e.getMessage());
            resetEncoder("opus_exception");
            return null;
        } catch (AssertionError | RuntimeException e) {
            log.warn("[OpusStreamEncoder] encode fatal, reset encoder, inputRate={}, frameSamples={}, error={}",
                    resampleRate, FRAME_SAMPLES, e.toString(), e);
            resetEncoder("fatal_encode_error");
            return null;
        }
    }

    private void resetEncoder(String reason) {
        frameFill = 0;
        resampleRate = 0;
        resamplePos = 0.0;
        resamplePrev = 0;
        try {
            encoder = encoderFactory.create();
            log.info("[OpusStreamEncoder] encoder reset, reason={}", reason);
        } catch (OpusException e) {
            log.error("[OpusStreamEncoder] encoder reset failed, reason={}, error={}",
                    reason, e.getMessage(), e);
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

    /**
     * 线性插值重采样到 48k —— 跨调用保持连续相位：
     * 用 {@link #resamplePos} 记录下一个输出在输入流中的连续位置、{@link #resamplePrev} 记录上一块末样本，
     * 块与块之间不再各自从 0 相位起算，从而消除块边界突变（之前“诡异音乐声”的主因）。
     * 输入采样率变化（如 16k 原声 与 24k TTS 混入同一编码器）时重置相位。
     */
    private short[] resampleTo48k(short[] in, int inSampleRate) {
        if (in.length == 0) {
            return in;
        }
        if (inSampleRate == OUTPUT_SAMPLE_RATE) {
            resampleRate = OUTPUT_SAMPLE_RATE;
            resamplePrev = in[in.length - 1];
            resamplePos = 0.0;
            return in;
        }
        if (inSampleRate != resampleRate) {
            resampleRate = inSampleRate;
            resamplePos = 0.0;
            resamplePrev = in[0];
        }
        double step = (double) inSampleRate / OUTPUT_SAMPLE_RATE;
        int maxOut = (int) Math.ceil((in.length - resamplePos) / step) + 1;
        short[] tmp = new short[Math.max(maxOut, 0)];
        int n = 0;
        // 仅在 i0 与 i0+1 都可用(i0+1 ≤ in.length-1)时产出；落在块末与下一块首之间的样本留待下次(用 resamplePrev 衔接)
        while (resamplePos < in.length - 1) {
            int i0 = (int) Math.floor(resamplePos);
            double f = resamplePos - i0;
            int a = (i0 < 0) ? resamplePrev : in[i0];
            int b = in[i0 + 1];
            tmp[n++] = (short) Math.round(a + (b - a) * f);
            resamplePos += step;
        }
        resamplePrev = in[in.length - 1];
        resamplePos -= in.length;   // 把剩余相位带入下一块(变为 [-1,0) 区间，下次用 resamplePrev 衔接)
        return (n == tmp.length) ? tmp : java.util.Arrays.copyOf(tmp, n);
    }

    @FunctionalInterface
    interface FrameEncoderFactory {
        FrameEncoder create() throws OpusException;
    }

    @FunctionalInterface
    interface FrameEncoder {
        int encode(short[] input, int inputOffset, int frameSize, byte[] output, int outputOffset, int maxBytes)
                throws OpusException;
    }
}
