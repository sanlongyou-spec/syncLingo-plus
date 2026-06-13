#!/usr/bin/env python3
"""
标点还原 + 强制分段 — 全链路 ASR 流式仿真测试 v4

测试流程：
  完整会议发言原文（无标点，含大量语气词/重复/不完整句）
  → 模拟 Azure ASR Transcribing 逐帧增长（每帧 +5~9字）
  → 每帧计算 working / safe（来自真实 stableInWorking）
  → working >= 60字 时触发 shouldForce：送标点模型 → P1/P2/P3 分段
  → 成功切段后推进 emittedLen，下帧继续
  → 展示整段发言被切成哪些子段，直接评估语义合理性

用法：
  SPEAKER_SERVICE_URL=http://127.0.0.1:7000 python3 tests/test_punctuation_segmentation.py
"""
import os, sys, time, json, urllib.request, statistics
from typing import Optional, List, Tuple

SPEAKER_SERVICE_URL = os.environ.get("SPEAKER_SERVICE_URL", "http://127.0.0.1:7000")
PUNCTUATE_URL       = f"{SPEAKER_SERVICE_URL}/punctuate"

MAX_CHARS          = 60   # shouldForce 阈值（maxSegmentZhChars）
FORCE_TAIL_MARGIN  = 4    # FORCE_TAIL_MARGIN_CHARS
PUNCT_MIN_CHARS    = 10   # Java PUNCT_MIN_CHARS

P1 = set("。！？；.!?;…")
P2 = set("，、：——,，、：")
NO_SEG = -1


# ══════════════════════════════════════════════════════════════════════════════════
# 测试数据：5 段完整会议发言（无标点，纯口语，含语气词/重复/不完整/话题跳转）
# ══════════════════════════════════════════════════════════════════════════════════

SPEECHES = [
    # ── 1. 项目进展汇报（~235字，语气词/重复）────────────────────────────────────
    {
        "title":   "项目进展汇报",
        "speaker": "项目经理A",
        "text": (
            "那个关于我们这个项目的进展情况呢就是说我们目前已经完成了第一阶段的工作"
            "正在进行第二阶段然后第二阶段主要是接口对接这块还有就是性能优化这两个方向"
            "我们内部测试下来延迟大概在两百毫秒以内嗯对就是这样"
            "然后关于人员这边目前团队是七个人我觉得可能还需要再加一两个测试同学"
            "因为测试这块工作量还是挺大的然后关于时间节点呢"
            "下个月底应该可以完成整体联调然后进入用户验收阶段"
        ),
    },
    # ── 2. 预算讨论（~265字，就是/然后重复）──────────────────────────────────────
    {
        "title":   "预算讨论",
        "speaker": "财务B",
        "text": (
            "就是这个预算的问题我想说一下"
            "我们今年实际支出和预算有一些偏差主要是因为人力成本这块超出了一些"
            "就是那个同学的薪资调整还有就是新招了两个人嗯这个是主要原因"
            "然后运营费用这块相对来说还好就是有一些弹性空间可以调剂"
            "嗯那么我们的建议是在下个季度的预算编制中适当提高人力成本这一项的比例"
            "同时压缩一些非核心的支出项目就是那种可以延后的采购啊差旅之类的"
            "这样整体应该能够平衡"
        ),
    },
    # ── 3. 技术架构方案（~270字，一方面/另一方面结构）────────────────────────────
    {
        "title":   "技术架构方案",
        "speaker": "技术负责人C",
        "text": (
            "关于这次架构调整的方案我这边想汇报一下"
            "我们经过两周的技术调研决定采用混合云的架构方式"
            "就是核心的敏感数据还是放在本地然后把一些计算密集型的任务放到公有云上面去跑"
            "这样一方面可以保证数据安全合规另一方面可以利用公有云的弹性伸缩能力"
            "应对业务高峰期的流量然后成本方面我们估算了一下"
            "每个月大概会增加三万左右但是可以节省大量的运维人力"
            "这个账算下来还是划算的我们建议Q3开始试点迁移"
        ),
    },
    # ── 4. 测试部门汇报＋下季度计划（话题切换）──────────────────────────────────
    {
        "title":   "测试汇报＋下季度计划（话题切换）",
        "speaker": "测试负责人D",
        "text": (
            "好的那么我来汇报一下测试部门这边的情况"
            "首先是自动化测试覆盖率本季度从百分之五十二提升到了百分之七十三"
            "这个提升还是比较显著的主要得益于我们引入了新的测试框架"
            "另外就是这次发布里面我们发现了三个P0级别的缺陷都已经在发布前全部修复了"
            "然后接下来说一下下个季度的计划"
            "我们会重点完善接口测试这块的建设还有就是和开发团队一起推进测试左移"
            "争取把缺陷发现时间提前到开发阶段"
        ),
    },
    # ── 5. 销售业绩＋市场策略（话题切换，嗯那个等语气词）────────────────────────
    {
        "title":   "销售业绩＋市场策略（话题切换）",
        "speaker": "销售负责人E",
        "text": (
            "嗯这个那个就是我们销售这边本季度的情况总体来说还不错"
            "就是大客户这边签了三个新合同加起来合同金额大概是一千二百万"
            "但是中小客户这边流失了一些主要原因是竞争对手在打价格战对我们影响挺大的"
            "然后我们的对策是针对高价值客户推出了专属服务包来提升粘性"
            "同时在低线城市这边我们也开始布局就是找一些本地的代理商来合作"
            "这个效果还需要观察一段时间目前数据还不太够"
        ),
    },
]


# ══════════════════════════════════════════════════════════════════════════════════
# 分段逻辑（与 Java emitForcedSegments 严格对齐，含 safe-cap 修复）
# ══════════════════════════════════════════════════════════════════════════════════

def find_first_sentence_end(text: str) -> int:
    for i, c in enumerate(text):
        if c in P1:
            return i + 1
    return NO_SEG


def find_last_comma(text: str, search_end: int) -> int:
    last = NO_SEG
    for i in range(min(search_end, len(text))):
        if text[i] in P2:
            last = i + 1
    return last


def map_to_original(original: str, punctuated: str, punct_end: int) -> int:
    orig_idx, p_idx = 0, 0
    while p_idx < punct_end and orig_idx < len(original):
        if punctuated[p_idx] == original[orig_idx]:
            p_idx += 1; orig_idx += 1
        else:
            p_idx += 1
    return orig_idx


def do_segment(working: str, punctuated: Optional[str], safe: int) -> dict:
    """
    模拟 emitForcedSegments。safe 由调用方从真实 stableInWorking 算出。
    P1 → P2（shouldForce）→ P3（shouldForce）
    返回 trigger / cut_orig / cut_text。
    """
    if safe <= 0:
        return {"trigger": "safe<=0", "cut_orig": -1, "cut_text": ""}

    det          = punctuated if punctuated else working
    det_tail_end = len(det) - FORCE_TAIL_MARGIN

    # P1：句末标点（任何长度都检查）
    ep = find_first_sentence_end(det)
    if ep != NO_SEG:
        eo = map_to_original(working, punctuated, ep) if punctuated else ep
        if 0 < eo <= safe:
            return {
                "trigger":  "sentence-punct" if punctuated else "sentence",
                "cut_orig": eo,
                "cut_text": det[:ep].rstrip() if punctuated else working[:eo].rstrip(),
            }

    # P2/P3：只在 shouldForce 时
    if len(working) < MAX_CHARS:
        return {"trigger": "no-cut", "cut_orig": -1, "cut_text": ""}

    # P2：最后逗号（safe-cap 修复：搜索上界 = min(det_tail_end, safe)）
    comma_end = min(det_tail_end, safe) if punctuated else det_tail_end
    cp = find_last_comma(det, comma_end)
    if cp != NO_SEG:
        co = map_to_original(working, punctuated, cp) if punctuated else cp
        if 0 < co <= safe:
            return {
                "trigger":  "force-comma-punct" if punctuated else "force-comma",
                "cut_orig": co,
                "cut_text": det[:cp].rstrip() if punctuated else working[:co].rstrip(),
            }

    # P3：字符数强切
    return {"trigger": "force-boundary", "cut_orig": safe, "cut_text": working[:safe]}


def common_prefix_len(a: str, b: str) -> int:
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n


def gen_frame_positions(full_text: str, seed: int = 0) -> List[int]:
    """确定性生成 ASR 帧位置（步长 5-9字循环，seed 控制起始偏移）"""
    steps = [6, 8, 7, 5, 9, 7, 6, 8, 5, 9]
    pos, si, out = 0, seed % len(steps), []
    while pos < len(full_text):
        pos = min(pos + steps[si], len(full_text))
        out.append(pos)
        si = (si + 1) % len(steps)
    return out


# ══════════════════════════════════════════════════════════════════════════════════
# HTTP
# ══════════════════════════════════════════════════════════════════════════════════

def call_punctuate(text: str) -> Tuple[Optional[str], float, bool]:
    payload = json.dumps({"text": text}).encode()
    req = urllib.request.Request(
        PUNCTUATE_URL, data=payload,
        headers={"Content-Type": "application/json"}, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            lat = (time.time() - t0) * 1000
            d   = json.loads(r.read())
            return d.get("punctuated"), lat, d.get("model_available", False)
    except Exception as e:
        return None, (time.time() - t0) * 1000, False


# ══════════════════════════════════════════════════════════════════════════════════
# 仿真核心
# ══════════════════════════════════════════════════════════════════════════════════

def simulate_speech(speech: dict, speech_idx: int) -> dict:
    """
    对一整段发言做 ASR 流式仿真：
    - 每帧 working 达到 MAX_CHARS 时触发 shouldForce
    - 调用标点服务，执行 P1/P2/P3 分段
    - 切段后推进 emitted_len
    """
    full_text   = speech["text"]
    positions   = gen_frame_positions(full_text, seed=speech_idx)
    emitted_len = 0
    prev_asr    = ""
    cuts        = []
    latencies   = []

    for frame_i, pos in enumerate(positions):
        asr_text = full_text[:pos]
        start    = emitted_len

        if start >= len(asr_text):
            prev_asr = asr_text
            continue

        working        = asr_text[start:]
        stable_abs     = common_prefix_len(asr_text, prev_asr)
        stable_in_work = max(0, stable_abs - start)
        safe           = min(stable_in_work, len(working) - FORCE_TAIL_MARGIN)
        prev_asr       = asr_text

        # 只在 shouldForce 时调标点服务（short working 几乎不含句末标点）
        if len(working) < MAX_CHARS or safe <= 0:
            continue

        punc, lat, model_ok = call_punctuate(working)
        latencies.append(lat)
        valid_punc = punc if (punc and punc != working and model_ok) else None

        result = do_segment(working, valid_punc, safe)

        if result["trigger"] in ("no-cut", "safe<=0"):
            continue

        cut_end = result["cut_orig"]
        if cut_end > 0:
            emitted_len = start + cut_end

        cuts.append({
            "frame":       frame_i,
            "asr_pos":     pos,
            "working_len": len(working),
            "safe":        safe,
            "trigger":     result["trigger"],
            "cut_text":    result["cut_text"],
            "lat_ms":      lat,
            "punc":        valid_punc,
            "working_raw": working,
        })

    return {
        "title":       speech["title"],
        "speaker":     speech["speaker"],
        "full_len":    len(full_text),
        "emitted_len": emitted_len,
        "cuts":        cuts,
        "remaining":   full_text[emitted_len:],
        "latencies":   latencies,
    }


# ══════════════════════════════════════════════════════════════════════════════════
# 输出
# ══════════════════════════════════════════════════════════════════════════════════

ICONS = {
    "sentence-punct":   "●",   # 句末标点（最优）
    "sentence":         "●",
    "force-comma-punct":"◆",   # 逗号切（CT-Transformer 加逗号后识别）
    "force-comma":      "◇",
    "force-boundary":   "△",   # 字符数强切（最差）
}


def main():
    print(f"[config] service={PUNCTUATE_URL}  speeches={len(SPEECHES)}")
    try:
        with urllib.request.urlopen(f"{SPEAKER_SERVICE_URL}/health", timeout=3) as r:
            h = json.loads(r.read())
            loaded = h.get("punct_model_loaded", False)
            print(f"[health] punct_model_loaded={loaded}  speaker={h.get('speaker_model_loaded')}")
            if not loaded:
                print("[WARN] 标点模型未加载，退出", file=sys.stderr); sys.exit(1)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr); sys.exit(1)

    all_cuts: list = []
    all_lats: list = []

    for si, speech in enumerate(SPEECHES):
        print()
        print("═" * 112)
        full = speech["text"]
        print(f"  发言 {si+1}  [{speech['speaker']}]  {speech['title']}  ({len(full)}字)")
        print(f"  原文: {full[:100]}...")
        print("─" * 112)

        res = simulate_speech(speech, si)

        if not res["cuts"]:
            print("  (未触发任何分段)")
            continue

        seg_texts = []  # 收集所有切段文本，最后拼成"还原结果"

        for cut in res["cuts"]:
            icon    = ICONS.get(cut["trigger"], "?")
            trigger = cut["trigger"]
            lat     = cut["lat_ms"]

            print(f"  {icon}  帧{cut['frame']:02d}  working={cut['working_len']:3d}字  "
                  f"safe={cut['safe']:3d}  lat={lat:5.1f}ms  [{trigger}]")
            # 送进去的原始 working 文本（前60字）
            print(f"     送入: 「{cut['working_raw'][:70]}」")
            # 切出来的段（含标点）
            print(f"     切出: 「{cut['cut_text'][:80]}」")
            if cut["punc"] and trigger != "force-boundary":
                # 展示完整标点版（帮助判断模型质量）
                punc_full = cut["punc"]
                print(f"     标点: 「{punc_full[:90]}」")
            print()

            seg_texts.append(cut["cut_text"])
            all_cuts.append(cut)
            all_lats.append(lat)

        # 剩余未切文本
        if res["remaining"]:
            pct = len(res["remaining"]) * 100 // res["full_len"]
            print(f"  ┄ 剩余 {len(res['remaining'])}字({pct}%): 「{res['remaining'][:60]}」")
            seg_texts.append(res["remaining"])

        # 触发分布
        trig: dict = {}
        for c in res["cuts"]:
            trig[c["trigger"]] = trig.get(c["trigger"], 0) + 1
        print(f"  └─ 切段数={len(res['cuts'])}  分布: {trig}")

        # 还原拼接——最直观的质量检验
        print(f"  └─ 还原段落: {'｜'.join(seg_texts)}")

    # ── 全局统计 ──────────────────────────────────────────────────────────────────
    print()
    print("═" * 112)
    print(f"  全局  speeches={len(SPEECHES)}  总切段={len(all_cuts)}")
    if all_lats:
        sl = sorted(all_lats)
        p90 = sl[int(len(sl) * 0.9)]
        print(f"  延迟  mean={statistics.mean(all_lats):.1f}ms  "
              f"p50={statistics.median(all_lats):.1f}ms  "
              f"p90={p90:.1f}ms  max={max(all_lats):.1f}ms  "
              f"≤45ms={sum(1 for x in all_lats if x<=45)}/{len(all_lats)}")

    all_trig: dict = {}
    for c in all_cuts:
        t = c["trigger"]
        all_trig[t] = all_trig.get(t, 0) + 1
    print(f"  触发分布: {all_trig}")

    n_ok = sum(1 for c in all_cuts if c["trigger"] != "force-boundary")
    if all_cuts:
        print(f"  语义切段率: {n_ok}/{len(all_cuts)} = {100*n_ok//len(all_cuts)}%")
        print()
        print("  图例: ● sentence-punct  ◆ force-comma-punct  △ force-boundary(最差)")


if __name__ == "__main__":
    main()
