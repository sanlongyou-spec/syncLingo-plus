#!/usr/bin/env python3
"""
延迟分析脚本 v2 — syncLingo 全链路延迟分析

Usage:
    docker logs --since 120m si-backend 2>&1 | python3 tests/analyze_latency_v2.py
    docker logs --since 120m si-backend 2>&1 | python3 tests/analyze_latency_v2.py --lang id-ID
    docker logs --since 60m si-backend 2>&1 | python3 tests/analyze_latency_v2.py --min-total 0
    cat logfile.txt | python3 tests/analyze_latency_v2.py
"""
import sys
import re
import argparse
import collections
from statistics import mean, median, stdev

SEP = "─" * 78


def parse_kv(line):
    """Parse key=value pairs from a log line. Returns dict of str keys to typed values."""
    result = {}
    for k, v in re.findall(r'(\w+)=(true|false|-?\d+(?:\.\d+)?)', line):
        if v == 'true':
            result[k] = True
        elif v == 'false':
            result[k] = False
        else:
            try:
                result[k] = int(v)
            except ValueError:
                try:
                    result[k] = float(v)
                except ValueError:
                    result[k] = v
    # Also capture string values like lang=id-ID, voiceId=..., by=sentence, speakerId=...
    for k, v in re.findall(r'(\w+)=([A-Za-z][A-Za-z0-9\-_]*)', line):
        if k not in result:
            result[k] = v
    return result


def pct(data, p):
    """Return p-th percentile of data list."""
    if not data:
        return 0
    s = sorted(data)
    k = (len(s) - 1) * p / 100.0
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def stats_dict(data):
    """Return statistics dict for a list of numbers, or None if empty."""
    if not data:
        return None
    n = len(data)
    return {
        'n': n,
        'mean': mean(data),
        'median': median(data),
        'p90': pct(data, 90),
        'p95': pct(data, 95),
        'min': min(data),
        'max': max(data),
        'stdev': stdev(data) if n >= 2 else 0.0,
    }


def stats(data, label, unit='ms', indent=''):
    """Print statistics for a list of numbers."""
    s = stats_dict(data)
    if s is None:
        print(f"{indent}{label}: 无数据")
        return
    print(
        f"{indent}{label}: n={s['n']}  "
        f"均值={s['mean']:.0f}{unit}  "
        f"中位={s['median']:.0f}{unit}  "
        f"p90={s['p90']:.0f}{unit}  "
        f"p95={s['p95']:.0f}{unit}  "
        f"min={s['min']:.0f}{unit}  "
        f"max={s['max']:.0f}{unit}"
    )


def stats_table_row(name, data, unit='ms'):
    s = stats_dict(data)
    if s is None:
        return f"  {name:<22}  无数据"
    return (
        f"  {name:<22}"
        f"  {s['n']:>5}"
        f"  {s['mean']:>8.0f}{unit}"
        f"  {s['median']:>8.0f}{unit}"
        f"  {s['p90']:>8.0f}{unit}"
        f"  {s['p95']:>8.0f}{unit}"
        f"  {s['min']:>8.0f}{unit}"
        f"  {s['max']:>8.0f}{unit}"
    )


def histogram(data, bucket_sec=1, max_sec=16):
    """Print ASCII histogram with bucket_sec-second buckets."""
    if not data:
        return
    n = len(data)
    edges = list(range(0, max_sec + 1)) + [999999]
    for i in range(len(edges) - 1):
        lo, hi = edges[i], edges[i + 1]
        cnt = sum(1 for v in data if lo * 1000 <= v < hi * 1000)
        if cnt == 0:
            continue
        pct_val = cnt / n * 100
        label = f"{lo}-{hi}s" if hi != 999999 else f">{lo}s"
        bar = "█" * int(round(pct_val / 2))
        print(f"  {label:>8} | {cnt:>4} ({pct_val:>5.1f}%) {bar}")


def section(title):
    print()
    print(SEP)
    print(f"  {title}")
    print(SEP)


def main():
    parser = argparse.ArgumentParser(description="syncLingo 全链路延迟分析 v2")
    parser.add_argument('--lang', default=None, help='Filter by lang (e.g. id-ID, zh-CN)')
    parser.add_argument('--min-total', type=int, default=500,
                        help='Minimum totalMs to include in analysis (default 500)')
    parser.add_argument('--since', default=None, help='Filter logs after this time (HH:MM format, not implemented — use docker logs --since instead)')
    args = parser.parse_args()

    # ── Parse all log lines ────────────────────────────────────────────────────
    breakdowns = []       # latency-breakdown
    first_chunks = []     # tts-first-chunk-sent
    audio_durations = []  # tts-audio-duration
    client_e2e = []       # e2e client latency
    asr_segments = []     # [AsrSession] force-segment
    asr_emit = []         # [AsrSession] emitForcedSegments
    cartesia_done = []    # [CartesiaWsClient] done
    cartesia_first = []   # [CartesiaWsClient] first-chunk

    line_count = 0
    for line in sys.stdin:
        line_count += 1
        if 'latency-breakdown' in line:
            d = parse_kv(line)
            if not d:
                continue
            if args.lang and d.get('lang') != args.lang:
                continue
            total = d.get('totalMs', 0)
            if isinstance(total, int) and total < args.min_total:
                continue
            breakdowns.append(d)
        elif 'tts-first-chunk-sent' in line:
            d = parse_kv(line)
            if d:
                if not args.lang or d.get('lang') == args.lang:
                    first_chunks.append(d)
        elif 'tts-audio-duration' in line:
            d = parse_kv(line)
            if d:
                if not args.lang or d.get('lang') == args.lang:
                    audio_durations.append(d)
        elif 'e2e client latency' in line:
            d = parse_kv(line)
            if not d:
                continue
            if args.lang and d.get('lang') != args.lang:
                continue
            e2e = d.get('e2eMs', 0)
            if isinstance(e2e, int) and e2e < 200:
                continue
            client_e2e.append(d)
        elif '[AsrSession] force-segment' in line:
            d = parse_kv(line)
            if d:
                if not args.lang or d.get('lang') == args.lang:
                    asr_segments.append(d)
        elif '[AsrSession] emitForcedSegments' in line or 'emitForcedSegments' in line:
            d = parse_kv(line)
            if d:
                asr_emit.append(d)
        elif '[CartesiaWsClient] done' in line:
            d = parse_kv(line)
            if d:
                cartesia_done.append(d)
        elif '[CartesiaWsClient] first-chunk' in line:
            d = parse_kv(line)
            if d:
                cartesia_first.append(d)

    print(f"  已扫描 {line_count} 行日志")
    if args.lang:
        print(f"  语言过滤: {args.lang}")
    if args.min_total > 0:
        print(f"  最小 totalMs 过滤: {args.min_total}ms")

    # ── 1. 服务端流水线 ────────────────────────────────────────────────────────
    section("1. 服务端流水线延迟  latency-breakdown")
    if not breakdowns:
        print("  无数据")
    else:
        total_ms_vals = [d['totalMs'] for d in breakdowns if isinstance(d.get('totalMs'), int)]
        total_mean = mean(total_ms_vals) if total_ms_vals else 1

        header = f"  {'阶段':<22}  {'样本':>5}  {'均值':>8}  {'中位':>8}  {'p90':>8}  {'p95':>8}  {'最小':>8}  {'最大':>8}"
        print(header)
        print("  " + "-" * 74)

        for key, label in [
            ('asrMs', 'ASR(含说话+静音判断)'),
            ('translateMs', '翻译'),
            ('gapMs', '排队/调度'),
            ('ttsMs', 'TTS首音'),
            ('totalMs', '合计'),
        ]:
            vals = [d[key] for d in breakdowns if isinstance(d.get(key), int)]
            row = stats_table_row(label, vals)
            if vals and key != 'totalMs' and total_mean > 0:
                pct_val = mean(vals) / total_mean * 100
                row += f"  ({pct_val:.0f}%)"
            print(row)

    # ── 2. TTS 首音发送 ────────────────────────────────────────────────────────
    section("2. TTS 首音发送  tts-first-chunk-sent")
    if not first_chunks:
        print("  无数据")
    else:
        for key, label in [
            ('captureToSentMs', '说话→首音发出'),
            ('speakMs', '说话时长'),
            ('translateMs', '翻译(含压缩)'),
            ('ttsGenMs', 'TTS生成'),
            ('sendQueueWaitMs', '发送排队等待'),
        ]:
            vals = [d[key] for d in first_chunks if isinstance(d.get(key), int) and d[key] >= 0]
            stats(vals, label)

        # Compressed vs not-compressed breakdown
        comp = [d['translateMs'] for d in first_chunks
                if d.get('compressed') is True and isinstance(d.get('translateMs'), int)]
        nocomp = [d['translateMs'] for d in first_chunks
                  if d.get('compressed') is False and isinstance(d.get('translateMs'), int)]
        if comp or nocomp:
            print()
            if comp:
                stats(comp, f"翻译耗时·压缩({len(comp)}句)")
            if nocomp:
                stats(nocomp, f"翻译耗时·未压缩({len(nocomp)}句)")
            if comp and nocomp:
                diff = mean(comp) - mean(nocomp)
                print(f"  → 压缩平均多花 {diff:.0f}ms")

    # ── 3. 音频时长比 ──────────────────────────────────────────────────────────
    section("3. 音频时长比  tts-audio-duration")
    if not audio_durations:
        print("  无数据")
    else:
        dur_vals = [d['audioDurationMs'] for d in audio_durations
                    if isinstance(d.get('audioDurationMs'), int) and d['audioDurationMs'] > 0]
        win_vals = [d['sourceSpeechWindowMs'] for d in audio_durations
                    if isinstance(d.get('sourceSpeechWindowMs'), int) and d['sourceSpeechWindowMs'] > 0]
        ratios = [d['audioDurationMs'] / d['sourceSpeechWindowMs']
                  for d in audio_durations
                  if isinstance(d.get('audioDurationMs'), int) and isinstance(d.get('sourceSpeechWindowMs'), int)
                  and d['audioDurationMs'] > 0 and d['sourceSpeechWindowMs'] > 0]
        stats(dur_vals, '译文音频时长')
        stats(win_vals, '原声说话窗口')
        if ratios:
            r_mean = mean(ratios)
            r_p90 = pct(ratios, 90)
            note = " ← >1 表示译音比原声长，必然积压" if r_mean > 1 else ""
            print(f"  音频/说话 比值: n={len(ratios)}  均值={r_mean:.2f}  p50={median(ratios):.2f}  p90={r_p90:.2f}{note}")

        # Per-lang breakdown
        by_lang: dict[str, list] = collections.defaultdict(list)
        for d in audio_durations:
            lang = d.get('lang', 'unknown')
            if isinstance(d.get('audioDurationMs'), int) and isinstance(d.get('sourceSpeechWindowMs'), int):
                if d['audioDurationMs'] > 0 and d['sourceSpeechWindowMs'] > 0:
                    by_lang[str(lang)].append(d['audioDurationMs'] / d['sourceSpeechWindowMs'])
        if len(by_lang) > 1:
            print()
            for lang, rs in sorted(by_lang.items()):
                print(f"    {lang}: n={len(rs)}  均值={mean(rs):.2f}  p50={median(rs):.2f}  p90={pct(rs, 90):.2f}")

    # ── 4. ASR 分段 ────────────────────────────────────────────────────────────
    section("4. ASR 分段  force-segment / emitForcedSegments")
    if not asr_segments:
        print("  force-segment: 无数据")
    else:
        by_reason: dict[str, int] = collections.Counter()
        for d in asr_segments:
            by_reason[str(d.get('by', 'unknown'))] += 1
        total_segs = len(asr_segments)
        print(f"  force-segment 总计: {total_segs}")
        for reason, cnt in sorted(by_reason.items(), key=lambda x: -x[1]):
            print(f"    by={reason}: {cnt} ({cnt/total_segs*100:.1f}%)")

        len_vals = [d['len'] for d in asr_segments if isinstance(d.get('len'), int)]
        if len_vals:
            stats(len_vals, '  分段文本长度', unit='字')

    if asr_emit:
        working_vals = [d['workingLen'] for d in asr_emit if isinstance(d.get('workingLen'), int)]
        stable_vals = [d['stable'] for d in asr_emit if isinstance(d.get('stable'), int)]
        safe_vals = [d['safe'] for d in asr_emit if isinstance(d.get('safe'), int)]
        if working_vals:
            print()
            stats(working_vals, '  emitForcedSegments workingLen', unit='字')
        if stable_vals:
            stats(stable_vals, '  stable', unit='字')
        if safe_vals:
            stats(safe_vals, '  safe', unit='字')

    # ── 5. Cartesia TTS ────────────────────────────────────────────────────────
    section("5. Cartesia TTS")
    if cartesia_first:
        fc_vals = [d['firstChunkMs'] for d in cartesia_first if isinstance(d.get('firstChunkMs'), int)]
        stats(fc_vals, 'first-chunk 延迟')
        # Per voiceId group
        by_voice: dict[str, list] = collections.defaultdict(list)
        for d in cartesia_first:
            vid = str(d.get('voiceId', 'unknown'))
            if isinstance(d.get('firstChunkMs'), int):
                by_voice[vid].append(d['firstChunkMs'])
        if len(by_voice) > 1:
            for vid, vals in sorted(by_voice.items()):
                stats(vals, f"  voiceId={vid[:16]}")
    else:
        print("  first-chunk: 无数据")

    if cartesia_done:
        total_ms_vals = [d['totalMs'] for d in cartesia_done if isinstance(d.get('totalMs'), int)]
        chunk_vals = [d['chunks'] for d in cartesia_done if isinstance(d.get('chunks'), int)]
        stats(total_ms_vals, 'done totalMs')
        if chunk_vals:
            stats(chunk_vals, 'done chunks', unit='块')
    else:
        print("  done: 无数据")

    # ── 6. 客户端端到端延迟 ────────────────────────────────────────────────────
    section("6. 客户端端到端延迟  e2e client latency")
    if not client_e2e:
        print("  无数据")
    else:
        header = f"  {'指标':<22}  {'样本':>5}  {'均值':>8}  {'中位':>8}  {'p90':>8}  {'p95':>8}  {'最小':>8}  {'最大':>8}"
        print(header)
        print("  " + "-" * 74)
        for key, label in [
            ('e2eMs', '端到端(总)'),
            ('captureMs', '服务端段'),
            ('rttMs', '网络RTT'),
            ('tailMs', '解码/播放缓冲'),
            ('outputLatencyMs', '输出设备尾延迟'),
            ('backlogMs', '播放积压'),
            ('playbackRateMilli', '播放倍速(x1000)'),
        ]:
            vals = [d[key] for d in client_e2e if isinstance(d.get(key), int)]
            print(stats_table_row(label, vals))

        # Playback rate groups
        if any('playbackRateMilli' in d for d in client_e2e):
            print()
            print(f"  {'倍速组':<22}  {'样本':>5}  {'e2e均值':>8}  {'e2e中位':>8}  {'e2e p90':>8}  {'积压均值':>8}  {'积压p90':>8}")
            print("  " + "-" * 74)
            groups = [
                ("1.00x(未加速)", lambda r: r <= 1000),
                ("加速中(1.0-1.35x)", lambda r: 1000 < r < 1350),
                ("1.35x(满速)", lambda r: r >= 1350),
            ]
            for name, predicate in groups:
                rows = [d for d in client_e2e if predicate(d.get('playbackRateMilli', 0))]
                e2e_vals = [d['e2eMs'] for d in rows if isinstance(d.get('e2eMs'), int)]
                bl_vals = [d['backlogMs'] for d in rows if isinstance(d.get('backlogMs'), int)]
                if not e2e_vals:
                    print(f"  {name:<22}  无数据")
                    continue
                e2e_s = stats_dict(e2e_vals)
                bl_s = stats_dict(bl_vals)
                bl_mean = f"{bl_s['mean']:.0f}ms" if bl_s else '-'
                bl_p90 = f"{bl_s['p90']:.0f}ms" if bl_s else '-'
                print(
                    f"  {name:<22}"
                    f"  {e2e_s['n']:>5}"
                    f"  {e2e_s['mean']:>8.0f}ms"
                    f"  {e2e_s['median']:>8.0f}ms"
                    f"  {e2e_s['p90']:>8.0f}ms"
                    f"  {bl_mean:>8}"
                    f"  {bl_p90:>8}"
                )

        # e2eMs histogram
        e2e_vals = [d['e2eMs'] for d in client_e2e if isinstance(d.get('e2eMs'), int)]
        if e2e_vals:
            print()
            print(f"  e2eMs 分布直方图 (每格 1 秒):")
            histogram(e2e_vals)

    # ── 7. 按语言分组 ──────────────────────────────────────────────────────────
    all_langs: set[str] = set()
    for d in breakdowns:
        if d.get('lang'):
            all_langs.add(str(d['lang']))
    for d in client_e2e:
        if d.get('lang'):
            all_langs.add(str(d['lang']))

    if len(all_langs) > 1 and not args.lang:
        section("7. 按语言分组")
        for lang in sorted(all_langs):
            bd_lang = [d for d in breakdowns if str(d.get('lang', '')) == lang]
            e2e_lang = [d for d in client_e2e if str(d.get('lang', '')) == lang]
            if not bd_lang and not e2e_lang:
                continue
            print(f"  [{lang}]")
            if bd_lang:
                total_vals = [d['totalMs'] for d in bd_lang if isinstance(d.get('totalMs'), int)]
                asr_vals = [d['asrMs'] for d in bd_lang if isinstance(d.get('asrMs'), int)]
                tts_vals = [d['ttsMs'] for d in bd_lang if isinstance(d.get('ttsMs'), int)]
                stats(total_vals, '    服务端 totalMs')
                stats(asr_vals, '    asrMs')
                stats(tts_vals, '    ttsMs')
            if e2e_lang:
                e2e_vals = [d['e2eMs'] for d in e2e_lang if isinstance(d.get('e2eMs'), int)]
                stats(e2e_vals, '    客户端 e2eMs')
            print()

    print()
    print(SEP)
    print("  分析完成")
    print(SEP)


if __name__ == "__main__":
    main()
