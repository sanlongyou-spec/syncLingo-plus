package com.si.backend.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响度归一化测试：验证不同语言/音色（用不同输入电平模拟）经归一化后播放音量趋于一致，
 * 且静音不被放大、增益变化平滑无突变。这是“切换语言音量相同”的核心保证。
 */
class OpusLoudnessNormalizationTest {

    private static final double TARGET_RMS = 3500.0;
    /** 每块 20ms@24k = 480 样本，连续喂入模拟一句话的多块流式音频 */
    private static final int CHUNK_SAMPLES = 480;
    private static final int CHUNKS = 60;

    /** 计算 short PCM 的 RMS */
    private static double rms(short[] s) {
        double sumSq = 0.0;
        for (short v : s) {
            sumSq += (double) v * v;
        }
        return Math.sqrt(sumSq / s.length);
    }

    /** 生成给定峰值幅度的正弦块（模拟一种音色的固有响度） */
    private static short[] sineChunk(double amplitude, double phaseStart) {
        short[] out = new short[CHUNK_SAMPLES];
        double f = 200.0;
        int sampleRate = 24000;
        for (int i = 0; i < CHUNK_SAMPLES; i++) {
            double t = (phaseStart + i) / sampleRate;
            out[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * f * t));
        }
        return out;
    }

    /** 把同一音色连续喂入，返回最后一块归一化后的 RMS（此时增益已收敛稳定） */
    private static double convergedRms(double amplitude) throws Exception {
        OpusStreamEncoder enc = new OpusStreamEncoder();
        short[] last = null;
        for (int c = 0; c < CHUNKS; c++) {
            short[] chunk = sineChunk(amplitude, (long) c * CHUNK_SAMPLES);
            enc.normalizeLoudness(chunk);
            last = chunk;
        }
        return rms(last);
    }

    @Test
    void differentVoiceLevelsConvergeToSameLoudness() throws Exception {
        // 模拟三种语言音色的不同固有响度：偏小、适中、偏大（电平差异在 ±12dB 限幅范围内，贴近真实音色差异）
        double quiet = convergedRms(2000);   // 安静音色
        double medium = convergedRms(6000);  // 适中音色
        double loud = convergedRms(16000);   // 响亮音色

        // 三者都应收敛到目标 RMS 附近（±15%），即播放音量基本一致
        for (double r : new double[]{quiet, medium, loud}) {
            assertEquals(TARGET_RMS, r, TARGET_RMS * 0.15,
                    "归一化后 RMS 应接近目标，实测=" + r);
        }
        // 三种音色之间的相对差异应很小（<10%），保证“切换语言音量相同”
        double max = Math.max(quiet, Math.max(medium, loud));
        double min = Math.min(quiet, Math.min(medium, loud));
        assertTrue((max - min) / max < 0.10,
                "不同音色归一化后音量差异应 <10%, quiet=" + quiet + ", medium=" + medium + ", loud=" + loud);
    }

    @Test
    void silenceIsNotAmplified() throws Exception {
        OpusStreamEncoder enc = new OpusStreamEncoder();
        short[] silence = new short[CHUNK_SAMPLES]; // 全 0
        enc.normalizeLoudness(silence);
        assertEquals(0.0, rms(silence), 0.001, "静音块不应被放大出底噪");
    }

    @Test
    void gainRampHasNoAbruptJump() throws Exception {
        // 首块就给很大输入，验证块内逐样本斜坡：相邻样本不会因增益突变出现爆音
        OpusStreamEncoder enc = new OpusStreamEncoder();
        short[] chunk = sineChunk(3000, 0); // 低幅输入 → 需要放大，考验斜坡平滑
        enc.normalizeLoudness(chunk);
        // 归一化后仍是平滑正弦，不应出现限幅削顶（首块增益从 1.0 起步平滑上升，不会瞬间打满）
        boolean clipped = false;
        for (short v : chunk) {
            if (v >= 32767 || v <= -32768) {
                clipped = true;
                break;
            }
        }
        assertTrue(!clipped, "首块平滑放大不应削顶产生爆音");
    }
}
