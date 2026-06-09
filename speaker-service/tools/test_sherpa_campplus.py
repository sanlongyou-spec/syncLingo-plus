"""sherpa-onnx CAM++ 快速验证：维度 / CPU 基准 / 区分度 sanity（合成语音）。"""
import os, time
import numpy as np
import sherpa_onnx

MODEL = os.environ.get("ECAPA_ONNX_PATH", "models/campplus_zh.onnx")

def synth_voice(seconds=4.0, sr=16000, f0=120.0, formants=(700, 1200, 2600), seed=0):
    rng = np.random.default_rng(seed)
    n = int(sr * seconds); t = np.arange(n) / sr
    sig = np.zeros(n)
    for h in range(1, 25):  # 谐波激励
        sig += (1.0 / h) * np.sin(2 * np.pi * f0 * h * t + rng.uniform(0, 0.1))
    # 简单共振峰着色
    sh = np.zeros(n)
    for f in formants:
        sh += np.sin(2 * np.pi * f * t) * 0.3
    sig = sig * (1 + 0.3 * sh)
    env = 0.5 * (1 + np.sin(2 * np.pi * 3.5 * t))  # 音节包络
    sig = sig * env + 0.01 * rng.standard_normal(n)
    sig = sig / (np.max(np.abs(sig)) + 1e-9) * 0.7
    return sig.astype(np.float32)

def main():
    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=MODEL, num_threads=1, provider="cpu", debug=False)
    ex = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)
    print(f"[model] {MODEL}  dim={ex.dim}")

    def embed(samples):
        s = ex.create_stream()
        s.accept_waveform(16000, samples)
        s.input_finished()
        assert ex.is_ready(s)
        return np.array(ex.compute(s), dtype=np.float32)

    def cos(a, b):
        return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))

    # 区分度 sanity：同"嗓音"两段 vs 不同嗓音
    A1 = embed(synth_voice(f0=120, formants=(700, 1200, 2600), seed=1))
    A2 = embed(synth_voice(f0=120, formants=(700, 1200, 2600), seed=2))
    B1 = embed(synth_voice(f0=210, formants=(500, 1700, 3000), seed=3))
    B2 = embed(synth_voice(f0=210, formants=(500, 1700, 3000), seed=4))
    print("[determinism] same input cos =", round(cos(A1, embed(synth_voice(f0=120, formants=(700,1200,2600), seed=1))), 6))
    print(f"[discrim] intra-A={cos(A1,A2):.3f}  intra-B={cos(B1,B2):.3f}  inter-AB={cos(A1,B1):.3f}")
    print("  (期望 intra > inter；合成音仅作管线 sanity, 真实准确率需会议音频)")

    # CPU 基准
    x = synth_voice(seconds=4.0, seed=9)
    embed(x)  # warmup
    N = 20
    t0 = time.perf_counter()
    for _ in range(N):
        embed(x)
    ms = (time.perf_counter() - t0) / N * 1000
    print(f"[cpu] 单线程 4s 音频 每次嵌入: {ms:.1f} ms (avg of {N})")

    try:
        import psutil, os as _os
        rss = psutil.Process(_os.getpid()).memory_info().rss / 1e6
        print(f"[mem] 进程 RSS ≈ {rss:.0f} MB (含 numpy/sherpa, 无 torch)")
    except Exception:
        print("[mem] psutil 未装, 跳过内存测量")

if __name__ == "__main__":
    main()
