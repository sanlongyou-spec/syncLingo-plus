"""端到端冒烟：起新 speaker-service(CAM++) -> enroll 两人 -> identify 验证。"""
import base64, io, json, os, subprocess, sys, time, urllib.request, wave
import numpy as np

PORT = 7099
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for f in ("_smoke_emb.json", "_smoke_emb.model"):
    try: os.remove(os.path.join(HERE, f))
    except OSError: pass

env = dict(os.environ, PORT=str(PORT), HOST="127.0.0.1",
           EMBEDDINGS_FILE="_smoke_emb.json",
           SPEAKER_ONNX_MODEL="models/campplus_zh.onnx", PYTHONUTF8="1")
proc = subprocess.Popen([sys.executable, "main.py"], cwd=HERE, env=env)


def synth(seconds=4.0, sr=16000, f0=120.0, formants=(700, 1200, 2600), seed=0):
    rng = np.random.default_rng(seed); n = int(sr * seconds); t = np.arange(n) / sr
    sig = sum((1.0 / h) * np.sin(2 * np.pi * f0 * h * t + rng.uniform(0, .1)) for h in range(1, 25))
    sh = sum(np.sin(2 * np.pi * f * t) * .3 for f in formants)
    sig = sig * (1 + .3 * sh) * (0.5 * (1 + np.sin(2 * np.pi * 3.5 * t))) + .01 * rng.standard_normal(n)
    return (sig / (np.max(np.abs(sig)) + 1e-9) * 0.7).astype(np.float32)


def wav_b64(samples):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    return base64.b64encode(buf.getvalue()).decode()


def post(path, obj):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}",
                                 data=json.dumps(obj).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())


def get(path):
    return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=30).read())


try:
    import psutil
    sp = psutil.Process(proc.pid)
    def rss():
        m = sp.memory_info().rss
        for c in sp.children(recursive=True):
            try: m += c.memory_info().rss
            except Exception: pass
        return m / 1e6

    health = None
    for _ in range(60):
        try:
            health = get("/health"); break
        except Exception:
            time.sleep(1)
    print("[health]", health)
    print(f"[mem] 模型加载后(空库): {rss():.0f} MB")

    A = lambda s: wav_b64(synth(f0=120, formants=(700, 1200, 2600), seed=s))
    B = lambda s: wav_b64(synth(f0=210, formants=(500, 1700, 3000), seed=s))

    print("[enroll A]", post("/enroll", {"name": "Alice", "audio_base64": A(1)}))
    print("[enroll A]", post("/enroll", {"name": "Alice", "audio_base64": A(2)}))
    print("[enroll B]", post("/enroll", {"name": "Bob", "audio_base64": B(1)}))
    print("[enroll B]", post("/enroll", {"name": "Bob", "audio_base64": B(2)}))

    print(f"[mem] 注册2人后: {rss():.0f} MB")
    rA = post("/identify", {"audio_base64": A(99)})
    rB = post("/identify", {"audio_base64": B(99)})
    print("[identify A-clip] ->", rA)
    print("[identify B-clip] ->", rB)
    # 连续识别 30 次, 看内存是否稳定(无泄漏)
    for i in range(30):
        post("/identify", {"audio_base64": A(100 + i)})
    print(f"[mem] 连续识别30次后: {rss():.0f} MB")
    ok = rA.get("name") == "Alice" and rB.get("name") == "Bob"
    print("[health]", get("/health"))
    print("RESULT:", "PASS ✅" if ok else "FAIL ❌")
finally:
    proc.terminate()
    try: proc.wait(timeout=10)
    except Exception: proc.kill()
    for f in ("_smoke_emb.json", "_smoke_emb.model"):
        try: os.remove(os.path.join(HERE, f))
        except OSError: pass
