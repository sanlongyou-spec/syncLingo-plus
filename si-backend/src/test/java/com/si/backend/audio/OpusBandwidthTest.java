package com.si.backend.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opus 编码带宽 / CPU 自动化测试。
 *
 * <p>验证：
 * <ul>
 *   <li>单路语音 Opus 码率落在 24kbps 目标附近（上限内），并据此投影 80 人会议的服务器出口带宽；</li>
 *   <li>DTX（静音不发包）确实降低码率；</li>
 *   <li>编码实时倍率（一颗核心可并行编码多少路），用于评估 4 核服务器是否够用。</li>
 * </ul>
 * 这些是确定性度量（不依赖网络），结果会打印到测试输出。
 */
class OpusBandwidthTest {

    private static final int TARGET_KBPS = 24;

    /** 生成 16-bit 小端 PCM 的“类语音”信号（基频+谐波+音节包络+轻噪声）。withSilence=true 时插入静音段以触发 DTX。 */
    private static byte[] speechLikePcm(int sampleRate, double seconds, boolean withSilence) {
        int n = (int) (sampleRate * seconds);
        byte[] pcm = new byte[n * 2];
        double f0 = 130.0;
        double harmonicNorm = 1 + 1.0 / 2 + 1.0 / 3 + 1.0 / 4 + 1.0 / 5;
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sampleRate;
            short s;
            // 每 0.5s 交替静音（仅 withSilence）
            boolean silent = withSilence && (((int) (t * 2)) % 2 == 1);
            if (silent) {
                s = 0;
            } else {
                double sample = 0;
                for (int h = 1; h <= 5; h++) {
                    sample += (1.0 / h) * Math.sin(2 * Math.PI * f0 * h * t);
                }
                sample /= harmonicNorm;
                double env = 0.5 * (1 + Math.sin(2 * Math.PI * 4 * t)); // 4Hz 音节率
                sample = sample * env + 0.02 * (rnd.nextDouble() - 0.5);
                s = (short) Math.max(-32768, Math.min(32767, sample * 0.6 * 32767));
            }
            pcm[2 * i] = (byte) (s & 0xFF);
            pcm[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return pcm;
    }

    private static long encodeAndCountBytes(OpusStreamEncoder enc, byte[] pcm, int sampleRate) {
        List<byte[]> packets = enc.feed(pcm, sampleRate);
        long bytes = 0;
        for (byte[] p : packets) {
            bytes += p.length;
        }
        return bytes;
    }

    @Test
    void singleStreamBitrateAndEightyPersonProjection() throws Exception {
        double seconds = 10.0;
        // 连续语音（无静音）= 最坏情况码率；24k 模拟 TTS 输入
        byte[] pcm = speechLikePcm(24000, seconds, false);

        OpusStreamEncoder enc = new OpusStreamEncoder();
        long bytes = encodeAndCountBytes(enc, pcm, 24000);

        double kbps = bytes * 8.0 / seconds / 1000.0;
        double egressMbps80 = 80 * kbps / 1000.0;
        double gbPerHour80 = egressMbps80 / 8.0 * 3600.0 / 1000.0;

        System.out.printf("[OpusBandwidth] 单路连续语音: %.1f kbps (%d 字节/%.0fs)%n", kbps, bytes, seconds);
        System.out.printf("[OpusBandwidth] 80 人会议服务器出口(峰值, 人均一路): %.2f Mbps%n", egressMbps80);
        System.out.printf("[OpusBandwidth] 80 人全员在线流量: %.2f GB/小时%n", gbPerHour80);
        System.out.printf("[OpusBandwidth] 单客户端下行: %.1f kbps%n", kbps);

        // 码率应在 24kbps 目标附近的合理区间
        assertTrue(kbps > 8 && kbps < 45,
                "单路 Opus 码率应接近 " + TARGET_KBPS + "kbps, 实测=" + kbps);
        // 80 人出口应远低于原始 PCM 方案（~41Mbps），证明 Opus 可行
        assertTrue(egressMbps80 < 5.0, "80 人出口应 < 5Mbps, 实测=" + egressMbps80);
    }

    @Test
    void dtxReducesBitrateOnSilence() throws Exception {
        double seconds = 10.0;
        byte[] continuous = speechLikePcm(24000, seconds, false);
        byte[] halfSilent = speechLikePcm(24000, seconds, true);

        double kbpsContinuous = encodeAndCountBytes(new OpusStreamEncoder(), continuous, 24000)
                * 8.0 / seconds / 1000.0;
        double kbpsHalfSilent = encodeAndCountBytes(new OpusStreamEncoder(), halfSilent, 24000)
                * 8.0 / seconds / 1000.0;

        System.out.printf("[OpusBandwidth] DTX 对比: 连续=%.1f kbps, 半静音=%.1f kbps%n",
                kbpsContinuous, kbpsHalfSilent);

        assertTrue(kbpsHalfSilent < kbpsContinuous,
                "半静音(DTX)码率应低于连续语音; 连续=" + kbpsContinuous + ", 半静音=" + kbpsHalfSilent);
    }

    @Test
    void encodeRealtimeFactorPerCore() throws Exception {
        // 用较长音频测编码耗时；含 16k(原始) 与 24k(TTS) 各一段，更贴近真实混合
        double seconds = 30.0;
        byte[] pcm24 = speechLikePcm(24000, seconds, false);
        byte[] pcm16 = speechLikePcm(16000, seconds, false);

        // 预热（JIT）
        encodeAndCountBytes(new OpusStreamEncoder(), speechLikePcm(24000, 2.0, false), 24000);

        long start = System.nanoTime();
        encodeAndCountBytes(new OpusStreamEncoder(), pcm24, 24000);
        encodeAndCountBytes(new OpusStreamEncoder(), pcm16, 16000);
        double encodeSeconds = (System.nanoTime() - start) / 1e9;

        double audioSeconds = seconds * 2;
        double realtimeFactor = audioSeconds / encodeSeconds;

        System.out.printf("[OpusBandwidth] 编码 %.0fs 音频耗时 %.3fs, 实时倍率=%.0f×%n",
                audioSeconds, encodeSeconds, realtimeFactor);
        System.out.printf("[OpusBandwidth] 估算: 单核可并行实时编码约 %.0f 路 (本方案每会议仅 2~3 路编码器)%n",
                realtimeFactor);

        // 一颗核心应能实时编码远多于 3 路（方案中每会议只需 2~3 个编码器）
        assertTrue(realtimeFactor > 5, "编码实时倍率应 > 5, 实测=" + realtimeFactor);
    }
}
