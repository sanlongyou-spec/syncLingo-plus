#!/usr/bin/env python3
"""
延迟分段统计分析。
用法（在服务器上）:
  docker logs --since 120m si-backend 2>&1 | python3 tests/analyze_latency.py

解析两类日志:
  - latency-breakdown (服务端分段): asrMs / translateMs / gapMs / ttsMs / totalMs
  - e2e client latency (客户端真实出声): e2eMs / captureMs / rttMs / tailMs
输出每段的 样本数/均值/中位数/p90/p95/最小/最大, 以及各段占总时长的百分比。
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
    cl = {"e2eMs": [], "captureMs": [], "rttMs": [], "tailMs": []}
    for line in sys.stdin:
        if "latency-breakdown" in line:
            kv = parse_kv(line)
            for k in bd:
                if k in kv:
                    bd[k].append(kv[k])
        elif "e2e client latency" in line:
            kv = parse_kv(line)
            # 过滤明显噪声: 总时长 < 200ms 视为异常样本
            if kv.get("e2eMs", 0) < 200:
                continue
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

    print()
    print("=" * 78)
    print("【客户端 真实出声延迟】 收音→听众耳朵 (已过滤 e2eMs<200ms 噪声)")
    print("=" * 78)
    print(f"{'指标':<18}{'样本':>5}{'均值ms':>9}{'p50':>8}{'p90':>8}{'p95':>8}{'最小':>8}{'最大':>8}")
    print("-" * 78)
    for key, name in [("e2eMs", "端到端(总)"), ("captureMs", "服务端段"), ("rttMs", "网络RTT"), ("tailMs", "解码/播放缓冲")]:
        st = stats(cl[key])
        if not st:
            print(f"{name:<18}  无数据")
            continue
        print(f"{name:<18}{st['n']:>5}{st['mean']:>9.0f}{st['p50']:>8.0f}{st['p90']:>8.0f}{st['p95']:>8.0f}{st['min']:>8.0f}{st['max']:>8.0f}")
    print()

if __name__ == "__main__":
    main()
