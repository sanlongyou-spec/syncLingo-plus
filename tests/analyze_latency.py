#!/usr/bin/env python3
"""
延迟分段统计分析。
用法（在服务器上）:
  docker logs --since 120m si-backend 2>&1 | python3 tests/analyze_latency.py

解析两类日志:
  - latency-breakdown (服务端分段): asrMs / translateMs / gapMs / ttsMs / totalMs
  - e2e client latency (客户端真实出声): e2eMs / captureMs / rttMs / tailMs / outputLatencyMs / backlogMs / playbackRateMilli
输出每段的 样本数/均值/中位数/p90/p95/最小/最大, 以及各段占总时长的百分比。
客户端延迟额外按 1.00x / 加速中 / 1.35x 分组，验证加速后的真实端到端延迟。
"""
import sys
import re

def pctile(values, p):
    if not values:
        return 0
    s = sorted(values)
    k = (len(s) - 1) * p / 100.0
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)

def stats(values):
    if not values:
        return None
    return {
        "n": len(values),
        "mean": sum(values) / len(values),
        "p50": pctile(values, 50),
        "p90": pctile(values, 90),
        "p95": pctile(values, 95),
        "min": min(values),
        "max": max(values),
    }

def parse_kv(line):
    return {k: int(v) for k, v in re.findall(r"(\w+)=(-?\d+)", line)}

def main():
    bd = {"asrMs": [], "translateMs": [], "gapMs": [], "ttsMs": [], "totalMs": []}
    cl = {
        "e2eMs": [],
        "captureMs": [],
        "rttMs": [],
        "tailMs": [],
        "outputLatencyMs": [],
        "backlogMs": [],
        "playbackRateMilli": [],
    }
    # 新埋点(阶段0): 首音"实际发送"口径 + 真实音频时长
    sent = {"captureToSentMs": [], "sendQueueWaitMs": [], "speakMs": [], "translateMs": [], "ttsGenMs": []}
    sent_rows = []  # 每句一行(逐句归因 + 按 compressed 分组)
    dur = {"audioDurationMs": [], "ratio": []}
    client_rows = []
    for line in sys.stdin:
        if "latency-breakdown" in line:
            kv = parse_kv(line)
            for k in bd:
                if k in kv:
                    bd[k].append(kv[k])
        elif "tts-first-chunk-sent" in line:
            kv = parse_kv(line)
            for k in ("captureToSentMs", "speakMs", "translateMs", "ttsGenMs"):
                if k in kv and kv[k] >= 0:
                    sent[k].append(kv[k])
            if kv.get("sendQueueWaitMs", -1) >= 0:
                sent["sendQueueWaitMs"].append(kv["sendQueueWaitMs"])
            if kv.get("captureToSentMs", 0) > 0:
                sent_rows.append(kv)
        elif "tts-audio-duration" in line:
            kv = parse_kv(line)
            ad = kv.get("audioDurationMs", -1)
            sw = kv.get("sourceSpeechWindowMs", 0)
            if ad > 0:
                dur["audioDurationMs"].append(ad)
            if ad > 0 and sw > 0:
                dur["ratio"].append(ad / sw)
        elif "e2e client latency" in line:
            kv = parse_kv(line)
            # 过滤明显噪声: 总时长 < 200ms 视为异常样本
            if kv.get("e2eMs", 0) < 200:
                continue
            client_rows.append(kv)
            for k in cl:
                if k in kv:
                    cl[k].append(kv[k])

    print("=" * 78)
    print("【服务端 分段耗时】 收音→首音发出 (asr 含说话时长+静音判定, 非纯处理)")
    print("=" * 78)
    total_mean = (sum(bd["totalMs"]) / len(bd["totalMs"])) if bd["totalMs"] else 0
    order = [("asrMs", "ASR(含说话+静音)"), ("translateMs", "翻译"), ("gapMs", "排队/调度"),
             ("ttsMs", "TTS首音"), ("totalMs", "合计")]
    print(f"{'阶段':<18}{'样本':>5}{'均值ms':>9}{'占比':>7}{'p50':>8}{'p90':>8}{'p95':>8}{'最大':>8}")
    print("-" * 78)
    for key, name in order:
        st = stats(bd[key])
        if not st:
            print(f"{name:<18}  无数据")
            continue
        pct = (st["mean"] / total_mean * 100) if (total_mean and key != "totalMs") else (100 if key == "totalMs" else 0)
        pct_s = f"{pct:.0f}%" if key != "totalMs" else "100%"
        print(f"{name:<18}{st['n']:>5}{st['mean']:>9.0f}{pct_s:>7}{st['p50']:>8.0f}{st['p90']:>8.0f}{st['p95']:>8.0f}{st['max']:>8.0f}")

    # ── 开始说话 → 开始播放 (e2eMs) 的分布直方图 + 百分位 ──
    e2e = cl["e2eMs"]
    if e2e:
        print()
        print("=" * 78)
        print("【开始说话 → 开始播放 分布】 e2eMs (含说话时长, 已过滤 <200ms 噪声)")
        print("=" * 78)
        st = stats(e2e)
        print(f"样本={st['n']}  最小={st['min']/1000:.1f}s  p25={pctile(e2e,25)/1000:.1f}s  "
              f"中位={st['p50']/1000:.1f}s  p75={pctile(e2e,75)/1000:.1f}s  "
              f"p90={st['p90']/1000:.1f}s  p95={st['p95']/1000:.1f}s  最大={st['max']/1000:.1f}s  均值={st['mean']/1000:.1f}s")
        print("-" * 78)
        # 1 秒一档的直方图, 直观看分布形状(是否双峰/集中在哪)
        edges = list(range(0, 17)) + [999]  # 0..16s, 然后 16s+
        n = len(e2e)
        for i in range(len(edges) - 1):
            lo, hi = edges[i], edges[i + 1]
            cnt = sum(1 for v in e2e if lo * 1000 <= v < hi * 1000)
            if cnt == 0:
                continue
            pct = cnt / n * 100
            label = f"{lo}-{hi}s" if hi != 999 else f">{lo}s"
            bar = "█" * int(round(pct / 2))
            print(f"{label:>8} | {cnt:>3} ({pct:>4.1f}%) {bar}")

    # ── 阶段0 新埋点: 真实"说话→首音实际发送"(含发送排队) + 印尼语音频时长比 ──
    if sent["captureToSentMs"] or dur["ratio"]:
        print()
        print("=" * 78)
        print("【真实首音(发送口径) + 音频时长比】 阶段0 新埋点, 修正之前漏掉的发送排队")
        print("=" * 78)
        st = stats(sent["captureToSentMs"])
        if st:
            print(f"说话→首音实际发送 captureToSentMs: 样本={st['n']}  均值={st['mean']/1000:.1f}s  "
                  f"p50={st['p50']/1000:.1f}s  p90={st['p90']/1000:.1f}s  p95={st['p95']/1000:.1f}s  最大={st['max']/1000:.1f}s")
        st = stats(sent["sendQueueWaitMs"])
        if st:
            print(f"生成→发送 排队等待 sendQueueWaitMs:  样本={st['n']}  均值={st['mean']:.0f}ms  "
                  f"p50={st['p50']:.0f}  p90={st['p90']:.0f}  p95={st['p95']:.0f}  最大={st['max']:.0f}  (之前 latency-breakdown 完全漏掉这块)")
        st = stats(dur["audioDurationMs"])
        if st:
            print(f"印尼/译文 音频真实时长 audioDurationMs: 样本={st['n']}  均值={st['mean']/1000:.1f}s  p50={st['p50']/1000:.1f}s  p90={st['p90']/1000:.1f}s")
        if dur["ratio"]:
            r = dur["ratio"]
            print(f"音频时长 / 说话窗口 比值: 样本={len(r)}  均值={sum(r)/len(r):.2f}  "
                  f"p50={pctile(r,50):.2f}  p90={pctile(r,90):.2f}  "
                  f"(>1 表示译音比说话还长→必然积压, 这是压缩目标的依据)")

    # ── 延迟归因(逐句平均): 说话 / 翻译(含压缩) / TTS生成 / 发送排队 各占多少 ──
    if sent_rows:
        print()
        print("=" * 78)
        print("【延迟归因·逐句平均】 说话开始→首音发出 = 说话 + 翻译(含压缩) + TTS生成 + 发送排队")
        print("=" * 78)
        n = len(sent_rows)
        def avg(key):
            vals = [r[key] for r in sent_rows if key in r and r[key] >= 0]
            return (sum(vals) / len(vals)) if vals else 0
        speak = avg("speakMs"); trans = avg("translateMs"); ttsgen = avg("ttsGenMs"); sendq = avg("sendQueueWaitMs")
        total = avg("captureToSentMs")
        base = total if total > 0 else 1
        print(f"样本={n}  总(说话→首音发出)均值={total/1000:.1f}s")
        print(f"{'环节':<16}{'均值ms':>9}{'占比':>8}{'p50':>8}{'p90':>8}")
        print("-" * 78)
        for key, name in [("speakMs", "说话(含静音)"), ("translateMs", "翻译(含压缩)"),
                          ("ttsGenMs", "TTS生成"), ("sendQueueWaitMs", "发送排队"),
                          ("captureToSentMs", "合计→发出")]:
            st = stats([r[key] for r in sent_rows if key in r and r[key] >= 0])
            if not st:
                continue
            pct = (st["mean"] / base * 100) if key != "captureToSentMs" else 100
            print(f"{name:<16}{st['mean']:>9.0f}{pct:>7.0f}%{st['p50']:>8.0f}{st['p90']:>8.0f}")
        # 翻译耗时: 压缩 vs 不压(看压缩到底加了多少)
        comp = [r["translateMs"] for r in sent_rows if r.get("compressed") == 1 and "translateMs" in r]
        nocomp = [r["translateMs"] for r in sent_rows if r.get("compressed") == 0 and "translateMs" in r]
        sc = stats(comp); snc = stats(nocomp)
        print("-" * 78)
        if sc:
            print(f"翻译·压缩了 ({sc['n']}句):  均值={sc['mean']:.0f}ms  p50={sc['p50']:.0f}")
        if snc:
            print(f"翻译·没压缩({snc['n']}句):  均值={snc['mean']:.0f}ms  p50={snc['p50']:.0f}")
        if sc and snc:
            print(f"→ 压缩平均多花 {sc['mean']-snc['mean']:.0f}ms")
        print("注: 客户端实听还要加上'播放积压'(见下表 backlog), 服务端首音发出只是到这一步。")

    print()
    print("=" * 78)
    print("【客户端 输出设备估算延迟】 收音→浏览器估算音频输出设备开始播放 (已过滤 e2eMs<200ms 噪声)")
    print("=" * 78)
    print(f"{'指标':<18}{'样本':>5}{'均值ms':>9}{'p50':>8}{'p90':>8}{'p95':>8}{'最小':>8}{'最大':>8}")
    print("-" * 78)
    for key, name in [
        ("e2eMs", "端到端(总)"),
        ("captureMs", "服务端段"),
        ("rttMs", "网络RTT"),
        ("tailMs", "解码/播放缓冲"),
        ("outputLatencyMs", "输出设备尾延迟"),
        ("backlogMs", "播放积压"),
        ("playbackRateMilli", "播放倍速(x1000)"),
    ]:
        st = stats(cl[key])
        if not st:
            print(f"{name:<18}  无数据")
            continue
        print(f"{name:<18}{st['n']:>5}{st['mean']:>9.0f}{st['p50']:>8.0f}{st['p90']:>8.0f}{st['p95']:>8.0f}{st['min']:>8.0f}{st['max']:>8.0f}")
    print()

    if any("playbackRateMilli" in row for row in client_rows):
        print("=" * 78)
        print("【按前端播放倍速分组】 倍速=两次上报间的峰值(封顶 1.3x); 看加速段的延迟与积压")
        print("=" * 78)
        print(f"{'倍速组':<18}{'样本':>5}{'e2e均值ms':>12}{'e2e中位':>10}{'e2e p90':>10}{'积压均值ms':>12}{'积压p90':>10}")
        print("-" * 78)
        groups = [
            ("1.00x(未加速)", lambda rate: rate <= 1000),
            ("加速中(1.0-1.3x)", lambda rate: 1000 < rate < 1300),
            ("1.3x(满速)", lambda rate: rate >= 1300),
        ]
        for name, matches in groups:
            rows = [row for row in client_rows if matches(row.get("playbackRateMilli", 0))]
            e2e_stats = stats([row["e2eMs"] for row in rows if "e2eMs" in row])
            backlog_stats = stats([row["backlogMs"] for row in rows if "backlogMs" in row])
            if not e2e_stats:
                print(f"{name:<18}  无数据")
                continue
            backlog_mean = backlog_stats["mean"] if backlog_stats else 0
            backlog_p90 = backlog_stats["p90"] if backlog_stats else 0
            print(f"{name:<18}{e2e_stats['n']:>5}{e2e_stats['mean']:>12.0f}{e2e_stats['p50']:>10.0f}"
                  f"{e2e_stats['p90']:>10.0f}{backlog_mean:>12.0f}{backlog_p90:>10.0f}")
        print()

if __name__ == "__main__":
    main()
