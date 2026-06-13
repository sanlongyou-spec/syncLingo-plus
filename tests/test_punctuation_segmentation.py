#!/usr/bin/env python3
"""
标点还原 + 分段逻辑测试脚本 v3

模拟真实 ASR 流式场景：
  - 输入是"强切触发瞬间的部分文本"（62-90字），不是完整句子
  - 包含真实会议口语特征：语气词(嗯/那个/就是)、重复、话题跳转
  - stable = 85% * len（模拟：上一个 ASR 事件约少了 10-15 字，稳定前缀 ≈ 85%）
  - 对比：有标点时在最后一个逗号切 vs 无标点时在第 60 字任意切

关键假设：
  - shouldForce() = True（len > MAX_CHARS=60），因为我们测的就是这种超长场景
  - findCommaSegmentEnd：在 detectionText[0 : len-TAIL_MARGIN] 找最后逗号
  - mapPunctuatedToOriginal：双指针，标点中多出的字符视为插入跳过

用法：
  SPEAKER_SERVICE_URL=http://127.0.0.1:7000 python3 tests/test_punctuation_segmentation.py
"""
import os, sys, time, json, urllib.request, statistics
from typing import Optional

SPEAKER_SERVICE_URL = os.environ.get("SPEAKER_SERVICE_URL", "http://127.0.0.1:7000")
PUNCTUATE_URL = f"{SPEAKER_SERVICE_URL}/punctuate"

MAX_CHARS        = 60   # maxSegmentZhChars
FORCE_TAIL_MARGIN = 4   # FORCE_TAIL_MARGIN_CHARS
# 流式 ASR 稳定前缀模拟：当前事件比上一事件多 ~10 字，稳定前缀 ≈ len-12
# safe = min(len-12, len-4) = len-12
TAIL_UNSTABLE = 12      # 末尾不稳定字符数（模拟流式 ASR 的尾部波动）

P1 = set("。！？；.!?;…")
P2 = set("，、：——,，、：")
NO_SEG = -1

# ──────────────────────────────────────────────────────────────────────────────
# 测试数据：真实会议口语，含语气词/重复/不完整，长度 62-90 字
# 这些文本模拟"说话人一口气说了很长一段，Azure ASR 在 shouldForce 触发瞬间的输出"
# ──────────────────────────────────────────────────────────────────────────────

# A类：单一长话题（语气词+重复，62-80字）
# 期望：CT-Transformer 在自然停顿处加逗号，比字符硬切效果好
LONG_ORAL = [
    # 1. 62字，含"那个"
    "那个关于我们这个项目的进展情况呢就是说我们目前已经完成了第一阶段的工作正在进行第二阶段",
    # 2. 65字，含"就是"重复
    "就是我们这个预算方面我们需要重新评估一下整体的支出情况特别是在人力成本这块还有运营费用",
    # 3. 63字，含"嗯"开头
    "嗯对就是说我们的销售团队这边反馈说是在一些地区竞争对手在打价格战这对我们的拓客工作造成了",
    # 4. 68字，含"这个这个"重复
    "这个这个我们的技术方案我们已经和第三方供应商确认了主要的技术细节接下来还需要两周左右完成接口对接",
    # 5. 66字，含"对对"
    "对对我们上周进行了压力测试结果显示在五千并发用户的情况下系统还是比较稳定的但是响应时间有点",
    # 6. 70字，含"然后"过渡
    "我们采购流程这边存在一些冗余环节然后我们计划在下个季度完成流程再造预计可以节省百分之二十的时间成本",
    # 7. 64字，含"那么"
    "那么关于这次战略调整我们计划用六个月的时间完成整个过渡期需要做好充分的沟通确保各团队清楚新方向",
    # 8. 72字，含"其实"
    "其实我们的现金流状况目前比较紧张主要是因为这个季度市场推广费用增加了很多建议暂时放缓一些非紧急的支出",
    # 9. 63字，含"另外"话题跳
    "另外数据中心的PUE值从一点五降到了一点三五这个主要得益于新型散热系统的引入符合我们绿色运营的",
    # 10. 67字，含"我觉得"
    "我觉得关于供应商选择这个问题我们还是应该再多比较几家然后通过评分卡的方式来做决策不要急于下结论",
    # 11. 65字，含"就是说"
    "就是说我们的推荐算法经过优化之后用户的点击率提升了百分之二十二转化率也有所改善下一步要重点优化",
    # 12. 71字，含"这个"
    "这个关于云迁移的方案我们决定采用混合云架构一方面利用公有云的弹性另一方面保留核心敏感数据在本地",
    # 13. 68字，含"然后然后"
    "网络安全团队完成了年度渗透测试然后发现了几个中等级别的漏洞然后已经安排相关同学在本周内全部修复",
    # 14. 63字，含"所以"
    "所以说我们的老客户续约率本季度达到了百分之八十五这说明产品粘性在持续提升但是新客户获取这块还需要",
    # 15. 76字，含口语化表达
    "嗯嗯那个关于人才战略这个方向其实我们一直在讨论就是从培养激励留用三个维度来推进但是具体的落地方案还没有",
    # 16. 64字，含"对吧"
    "我们的移动应用在苹果应用商店的评分从三点八提升到了四点三对吧这个主要是因为这几个版本的优化工作",
    # 17. 67字，含"还有就是"
    "还有就是数据平台这边建设工作进入第二阶段重点是数据治理和数据质量的提升我们计划引入数据血缘管理",
    # 18. 65字，含"其实这个"
    "其实这个OKR对齐会议建议每月一次同时搭配每周的简短站会确保各部门的工作方向和公司战略保持一致",
    # 19. 69字，含"我的意思是"
    "我的意思是从长远来看我们需要在核心技术上建立自己的壁垒而不是单纯依赖商业化的方案这需要持续加大研发",
    # 20. 72字，含"就是那个"
    "就是那个我们的品牌定位需要进一步清晰化目前在客户心中的认知还比较模糊我们需要找到一个能够真正差异化",
]

# B类：话题切换型（说完一件事紧接着说第二件，62-80字）
# 期望：CT-Transformer 识别出第一件事的句尾加 `。`，触发 sentence-punct 切
TOPIC_SWITCH = [
    # 21. 73字，两件事
    "技术团队已经完成了第一阶段的开发工作正在进行内部测试预计下周提交验收关于接口对接我们已经和供应商确认了",
    # 22. 70字
    "本季度营收目标完成了百分之七十还有一些差距需要在最后一个月冲刺关于年终奖方案人力资源部已经准备好了",
    # 23. 66字
    "代码审查发现了几个安全漏洞已经安排开发人员本周内修复同时我们也在评估是否需要引入第三方安全审计",
    # 24. 72字
    "仓库系统升级工作已经完成库存准确率从百分之九十二提升到九十八物流配送这边我们正在和本地配送公司洽谈",
    # 25. 68字
    "质量认证工作进入最后阶段预计下个月完成ISO审核生产线良品率本月有所下降主要因为原材料质量不稳定",
    # 26. 74字
    "数据隐私保护方面我们需要对用户数据处理流程进行全面梳理另外关于知识产权保护我们已经申请了五项专利",
    # 27. 69字
    "销售团队反馈部分地区竞争对手在打价格战对拓客造成了一定压力对于高价值客户我们计划推出专属服务包",
    # 28. 71字
    "采购流程存在一些冗余环节计划下季度完成流程再造节省百分之二十时间生产计划调整后每日产能提升了十五个点",
    # 29. 67字
    "内部审计发现费用报销流程不规范财务部门已经发布新规范另外我们的OKR对齐会议建议改为每月一次",
    # 30. 75字
    "网络安全团队完成了渗透测试发现几个中级漏洞已全部修复系统监控告警误报率比较高导致运维团队浪费了大量精力",
]

ALL_CASES = (
    [("A-长话题", s) for s in LONG_ORAL] +
    [("B-话题切换", s) for s in TOPIC_SWITCH]
)
assert len(ALL_CASES) == 30, f"预期30句，实际{len(ALL_CASES)}"


# ── 分段逻辑模拟（与 Java emitForcedSegments 对齐）────────────────────────────

def find_first_sentence_end(text: str) -> int:
    """Java findSentenceSegmentEnd：从头找第一个句末标点"""
    for i, c in enumerate(text):
        if c in P1:
            return i + 1
    return NO_SEG


def find_last_comma(text: str, det_safe_end: int) -> int:
    """Java findCommaSegmentEnd：在 [0, det_safe_end) 找最后一个逗号"""
    last = NO_SEG
    for i in range(min(det_safe_end, len(text))):
        if text[i] in P2:
            last = i + 1
    return last


def map_to_original(original: str, punctuated: str, punct_end: int) -> int:
    """双指针：标点文本位置 → 原始文本位置"""
    orig_idx = 0
    p_idx = 0
    while p_idx < punct_end and orig_idx < len(original):
        if punctuated[p_idx] == original[orig_idx]:
            p_idx += 1; orig_idx += 1
        else:
            p_idx += 1  # 插入标点
    return orig_idx


def simulate(original: str, punctuated: Optional[str]) -> dict:
    """
    模拟 emitForcedSegments（shouldForce=True，流式场景）。
    safe = len - TAIL_UNSTABLE（模拟末尾 ~12 字不稳定）
    """
    n = len(original)
    safe = n - TAIL_UNSTABLE   # 稳定前缀上界
    if safe <= 0:
        return {"trigger": "safe<=0", "cut_orig": -1, "cut_text": "", "safe": safe}

    det = punctuated if punctuated else original
    det_safe_end = len(det) - FORCE_TAIL_MARGIN  # findCommaSegmentEnd 内部边界

    # P1：句末（第一个）
    ep = find_first_sentence_end(det)
    if ep != NO_SEG:
        eo = map_to_original(original, punctuated, ep) if punctuated else ep
        if 0 < eo <= safe:
            txt = det[:ep].rstrip() if punctuated else original[:eo].rstrip()
            return {"trigger": "sentence-punct" if punctuated else "sentence",
                    "cut_orig": eo, "cut_text": txt, "safe": safe}

    # P2：最后逗号（shouldForce=True 这里固定触发）
    # 当使用标点版本时，额外限制搜索上界为 safe（原始坐标）。
    # CT-Transformer 只插入字符，故 punct_pos >= orig_pos，
    # 任何 punct_pos <= safe 的逗号必然映射到 orig_pos <= safe。
    comma_det_end = min(det_safe_end, safe) if punctuated else det_safe_end
    cp = find_last_comma(det, comma_det_end)
    if cp != NO_SEG:
        co = map_to_original(original, punctuated, cp) if punctuated else cp
        if 0 < co <= safe:
            txt = det[:cp].rstrip() if punctuated else original[:co].rstrip()
            return {"trigger": "force-comma-punct" if punctuated else "force-comma",
                    "cut_orig": co, "cut_text": txt, "safe": safe}

    # P3：字符数强切
    return {"trigger": "force-boundary", "cut_orig": safe,
            "cut_text": original[:safe], "safe": safe}


# ── HTTP ────────────────────────────────────────────────────────────────────────

def call_punctuate(text: str):
    payload = json.dumps({"text": text}).encode()
    req = urllib.request.Request(PUNCTUATE_URL, data=payload,
                                 headers={"Content-Type": "application/json"}, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            lat = (time.time() - t0) * 1000
            d = json.loads(r.read())
            return d.get("punctuated"), lat, d.get("model_available", False)
    except Exception as e:
        lat = (time.time() - t0) * 1000
        print(f"  [ERROR] {lat:.0f}ms: {e}", file=sys.stderr)
        return None, lat, False


# ── 主程序 ──────────────────────────────────────────────────────────────────────

def main():
    print(f"[config] service={PUNCTUATE_URL}")
    print(f"[config] cases={len(ALL_CASES)}  safe=len-{TAIL_UNSTABLE}  max_chars={MAX_CHARS}")

    try:
        with urllib.request.urlopen(f"{SPEAKER_SERVICE_URL}/health", timeout=3) as r:
            h = json.loads(r.read())
            print(f"[health] {h}")
            if not h.get("punct_model_loaded"):
                print("[WARN] punct_model_loaded=false", file=sys.stderr); sys.exit(1)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr); sys.exit(1)

    print(); print("─" * 110)

    latencies = []
    improved = 0
    by_class: dict = {}
    trigger_before: dict = {}
    trigger_after: dict = {}

    for i, (cls, sent) in enumerate(ALL_CASES):
        punc, lat, model_ok = call_punctuate(sent)
        latencies.append(lat)

        valid_punc = punc if (punc and punc != sent) else None
        seg_b = simulate(sent, None)
        seg_a = simulate(sent, valid_punc)

        tb = seg_b["trigger"]
        ta = seg_a["trigger"]
        trigger_before[tb] = trigger_before.get(tb, 0) + 1
        trigger_after[ta]  = trigger_after.get(ta, 0) + 1

        imp = (tb != ta and "punct" in ta)
        if imp: improved += 1
        bc = by_class.setdefault(cls, {"total": 0, "imp": 0})
        bc["total"] += 1
        if imp: bc["imp"] += 1

        flag = "★" if imp else " "
        print(f"{flag}[{cls}] idx={i:2d} chars={len(sent):3d} safe={seg_a['safe']:2d} "
              f"lat={lat:5.1f}ms  before={tb:<16} after={ta:<22}")

        if valid_punc:
            cut = seg_a.get("cut_text", "")
            cut_b = seg_b.get("cut_text", "")
            if imp and cut:
                print(f"  原始切点({len(cut_b)}字): 「{cut_b[:45]}」")
                print(f"  标点切点({len(cut)}字): 「{cut[:55]}」")
            elif not imp:
                new_p = [c for c in valid_punc if c not in sent and c in (P1 | P2)]
                print(f"  标点插入: {''.join(new_p[:6])}  | {valid_punc[:60]}{'…' if len(valid_punc)>60 else ''}")

    print(); print("─" * 110)
    print("【延迟统计】")
    lat_s = sorted(latencies)
    n = len(lat_s)
    print(f"  n={n}  mean={statistics.mean(lat_s):.1f}ms  median={statistics.median(lat_s):.1f}ms  "
          f"p90={lat_s[int(n*.9)]:.1f}ms  p95={lat_s[int(n*.95)]:.1f}ms  max={lat_s[-1]:.1f}ms")
    ok45 = sum(1 for l in lat_s if l <= 45)
    print(f"  ≤45ms: {ok45}/{n} ({100*ok45/n:.0f}%)  ← 超时预算")

    print()
    print("【分段改善统计】")
    print(f"  总改善: {improved}/{len(ALL_CASES)} 句")
    for cls, d in by_class.items():
        print(f"  {cls}: {d['imp']}/{d['total']}")
    print()
    print("  加标点前触发类型:")
    for k, v in sorted(trigger_before.items(), key=lambda x: -x[1]):
        print(f"    {k:<24}: {v}")
    print("  加标点后触发类型:")
    for k, v in sorted(trigger_after.items(), key=lambda x: -x[1]):
        print(f"    {k:<24}: {v}")

    print()
    print("【问题句分析（标点后仍 force-boundary 的句子）】")
    problem = []
    for i, (cls, sent) in enumerate(ALL_CASES):
        punc, _, _ = call_punctuate(sent)
        valid_punc = punc if (punc and punc != sent) else None
        seg_a = simulate(sent, valid_punc)
        if seg_a["trigger"] == "force-boundary":
            problem.append((i, cls, sent, valid_punc, seg_a))

    if not problem:
        print("  无（所有句子均找到自然切点）✓")
    else:
        for i, cls, sent, punc, seg in problem[:5]:
            print(f"  [{i}][{cls}] chars={len(sent)} safe={seg['safe']}")
            print(f"    原文: {sent[:60]}…")
            print(f"    标点: {(punc or '无')[:65]}{'…' if punc and len(punc)>65 else ''}")
            # 分析：标点里有没有逗号，以及逗号位置是否超出 safe
            if punc:
                commas = [(j, c) for j, c in enumerate(punc) if c in P2]
                det_safe = len(punc) - FORCE_TAIL_MARGIN
                last_in = [(j, c) for j, c in commas if j < det_safe]
                print(f"    det_safe={det_safe}  逗号位置: {[(j,c) for j,c in commas[:8]]}  "
                      f"det_safe内逗号: {last_in[-3:] if last_in else '无'}")

    print(f"\n[done] 全部 {len(ALL_CASES)} 句处理完成")


if __name__ == "__main__":
    main()
