#!/usr/bin/env python3
"""
标点还原 + 分段逻辑测试脚本

测试目标：
  1. /punctuate 端点延迟（p50/p90/max）
  2. 标点质量：输出是否含有意义的句末/子句标点
  3. 分段决策模拟：对每句话，判断加标点后是否能触发"sentence"切段（vs 原来需要字数兜底）
  4. 逐句日志：便于人工核查

用法（在服务器上运行，或配置 SPEAKER_SERVICE_URL 指向服务器）：
  SPEAKER_SERVICE_URL=http://127.0.0.1:7000 python3 tests/test_punctuation_segmentation.py

关键日志格式（供 grep/awk 分析）：
  [result] idx=N in_chars=M out_chars=K latency_ms=X sentence_found=T/F comma_found=T/F
"""
import os
import sys
import time
import json
import urllib.request
import urllib.error
import statistics
from dataclasses import dataclass, field
from typing import Optional

SPEAKER_SERVICE_URL = os.environ.get("SPEAKER_SERVICE_URL", "http://127.0.0.1:7000")
PUNCTUATE_URL = f"{SPEAKER_SERVICE_URL}/punctuate"

# 分段参数（与 application.yml 一致）
MIN_CHARS        = 10   # PUNCT_MIN_CHARS（才调用标点服务）
CLAUSE_THRESHOLD = 10   # 超过才看逗号
MAX_CHARS        = 60   # 强切阈值（maxSegmentZhChars）
FORCE_TAIL_MARGIN = 4   # FORCE_TAIL_MARGIN_CHARS

# 句末标点（P1）
P1_SENTENCE_END = set("。！？；.!?;…？！；…")
# 子句标点（P2）
P2_CLAUSE      = set("，、：——,，、：")

# ── 100 句真实会议 ASR 文本（无标点，模拟 Azure zh-CN 输出）─────────────────
# 来源：真实会议场景，涵盖预算/项目进展/人员安排/决策等主题，长度 15-80 字
SENTENCES = [
    # 预算/财务类
    "今天这次会议主要是想跟大家分享一下我们项目的最新进展情况以及下一步的规划",
    "关于预算方面我们需要重新评估一下整体的支出情况特别是在人力成本和运营费用上",
    "根据财务部门的数据显示本季度的收入增长了百分之十五但是成本也相应上升了百分之八",
    "我们需要在下个月底之前完成预算审批并且把最终的数字提交给董事会",
    "对于这个项目的投资回报率我们预计在三年内可以实现盈亏平衡然后开始产生净利润",
    "资金方面我们还有一些缺口大概是两百万左右需要通过融资或者其他渠道来补足",
    "在成本控制方面建议我们从采购环节入手通过集中采购来降低单价",
    "本季度的营收目标是五千万我们目前完成了大概百分之七十还有一些差距需要在最后一个月冲刺",
    "关于年终奖的发放方案人力资源部已经准备了三个版本分别对应不同的业绩情况",
    "我们的现金流状况目前比较紧张建议暂时放缓一些非紧急的支出计划",
    # 项目进展类
    "技术团队这边已经完成了第一阶段的开发工作正在进行内部测试预计下周可以提交给产品部门验收",
    "目前遇到的主要问题是数据库的性能瓶颈在高并发情况下响应时间明显变长",
    "前端部分的改版工作已经完成了百分之八十剩余的工作主要集中在移动端的适配上",
    "我们计划在本月底发布新版本主要包括三个核心功能的升级和十几个小的问题修复",
    "测试团队反馈了一些比较严重的bug其中有两个涉及到数据安全需要优先处理",
    "关于接口的对接工作我们已经和第三方供应商确认了技术方案预计需要两周时间完成",
    "产品经理那边提出了一些新的需求变更我们需要评估一下对现有开发计划的影响",
    "服务器的扩容工作已经完成新增了三台高性能服务器集群的处理能力提升了百分之四十",
    "代码审查发现了一些安全漏洞已经安排相关开发人员在本周内完成修复",
    "我们的应用程序在上周进行了一次压力测试结果显示在五千并发用户的情况下系统运行稳定",
    # 人员/团队类
    "关于新员工的招聘计划我们希望在第三季度再补充十五名工程师主要集中在后端和算法岗位",
    "张总监下周将前往上海出差主要目的是拜访几个重要客户同时也会参加行业峰会",
    "研发部门的团队扩张计划已经得到了批准我们会从大学和行业内同步招聘",
    "关于绩效考核的方式我们计划从下个季度开始引入OKR的管理模式",
    "李工程师提出申请希望能够参加下个月在北京举办的技术大会公司决定支持并报销相关费用",
    "目前团队的人员流失率有所上升主要集中在初级工程师这个层级我们需要分析原因并采取措施",
    "新入职的三位产品经理已经完成了入职培训今天开始正式接手各自负责的业务线",
    "关于远程办公的政策我们决定继续保持每周两天在家办公的安排但需要保证工作成果",
    "人事部门正在梳理各部门的职级体系预计下个月会发布新的职级说明和薪资区间",
    "团队建设活动计划在本季度末安排一次户外活动具体地点和时间会在下周确认",
    # 决策/会议类
    "经过讨论大家一致同意先推进第一期的试点项目等拿到数据之后再决定是否全面推广",
    "关于供应商的选择问题我建议我们再多比较几家然后通过评分卡的方式做出最终决定",
    "今天会议的主要决议有三点第一是确认项目启动时间第二是明确各方责任第三是建立周期性汇报机制",
    "这个方案还需要法务部门审核一下主要是看看是否存在合规方面的风险",
    "市场部门提出的这个营销方案创意不错但是执行成本比较高我们需要做一些取舍",
    "对于竞争对手最近推出的新产品我们需要尽快分析其功能特点并评估对我们业务的潜在影响",
    "合同谈判的进展比较顺利对方已经基本接受了我们的主要条款还有几个细节需要进一步确认",
    "关于这次战略调整的时间表我们计划用六个月的时间完成整个过渡期",
    "董事会对我们提交的年度计划总体表示认可但是要求我们在收入预测上更加保守一些",
    "我们决定在本季度暂停一些边缘项目把资源集中到最核心的三个业务上",
    # 客户/市场类
    "这个客户的需求比较特殊我们需要针对他们的实际情况做一些定制化的开发",
    "根据最新的市场调研数据我们的品牌认知度在目标人群中已经达到了百分之六十",
    "客户反馈我们的售后服务响应速度有待提升建议增加客服人手或者引入智能客服系统",
    "我们计划在下半年进入东南亚市场首先从印度尼西亚和越南开始试水",
    "这次参加展会的效果不错共收集了大约三百个有效商业线索销售团队会在本周内跟进",
    "对于高价值客户我们计划推出一个专属的服务包括专属客户经理和优先技术支持",
    "社交媒体上关于我们产品的讨论量明显增加主要集中在新功能的体验反馈",
    "我们的老客户续约率本季度达到了百分之八十五这说明产品的粘性在持续提升",
    "针对中小企业客户的定价策略需要重新考虑因为现在的价格对他们来说可能偏高",
    "销售团队反馈在一些地区竞争对手在打价格战这对我们的拓客工作造成了一定压力",
    # 运营/流程类
    "仓库管理系统的升级工作已经完成库存准确率从百分之九十二提升到了百分之九十八",
    "物流配送的时效问题主要集中在最后一公里的配送环节我们正在和几家本地配送公司洽谈合作",
    "生产线的良品率本月有所下降主要原因是原材料的质量不稳定已经向供应商提出改进要求",
    "客户投诉处理的平均时长从三天缩短到了一天半主要得益于新的工单系统的上线",
    "我们的采购流程存在一些冗余环节计划在下个季度完成流程再造预计可以节省百分之二十的时间",
    "数据中心的能耗指标有所改善PUE值从一点五降到了一点三五这符合我们的绿色运营目标",
    "供应链管理团队正在评估多元化采购策略以降低对单一供应商的依赖风险",
    "内部审计发现费用报销流程中存在一些不规范的情况财务部门已经发布了新的报销规范",
    "质量管理体系认证工作已经进入最后阶段预计下个月可以完成ISO认证的审核",
    "生产计划调整之后每日产能提升了百分之十五但是需要加班来完成增量部分",
    # 技术/产品类
    "人工智能模型的训练工作已经完成在测试集上的准确率达到了百分之九十二",
    "我们的移动应用最新版本在苹果应用商店的评分从三点八提升到了四点三",
    "大数据平台的建设工作进入了第二阶段重点是数据治理和数据质量的提升",
    "关于云迁移的方案我们决定采用混合云架构同时保留一部分敏感数据在本地服务器",
    "网络安全团队完成了年度渗透测试发现了几个中等级别的漏洞已经全部修复",
    "我们的API接口文档已经更新完成开发者可以在文档平台上查看最新版本",
    "用户行为分析显示用户在完成注册之后平均有百分之三十五的人在三天内流失",
    "新推出的AI助手功能用户使用率持续增长目前日活跃用户已经超过了五万",
    "系统监控告警的误报率比较高导致运维团队投入了大量精力在排查假警报上",
    "我们的推荐算法经过优化之后用户的点击率提升了百分之二十二转化率也有所改善",
    # 合规/法律类
    "数据隐私保护方面我们需要按照最新的法规要求对用户数据处理流程进行全面梳理",
    "关于知识产权的保护我们已经申请了五项专利其中两项已经获得授权",
    "劳动合同的续签工作本月要完成对于劳动合同即将到期的员工人事部门已经逐一沟通",
    "我们的财务报告需要符合新的会计准则要求审计师已经开始了相关的审核工作",
    "在环保合规方面工厂的排放数据已经达标但是废水处理能力需要进一步提升",
    "公司治理委员会建议加强信息披露的及时性和准确性以提升投资者信心",
    "关于这起侵权诉讼法务团队评估了之后认为我们的胜诉概率较高建议积极应诉",
    "新版用户协议和隐私政策已经由法务审核完成计划在下周正式发布",
    "对于这个市场我们需要提前了解当地的行业监管政策避免进入之后遇到合规障碍",
    "商标注册在东南亚五个国家的申请已经全部提交等待审批结果",
    # 跨部门协作类
    "这个项目需要市场技术和运营三个部门紧密协作建议成立一个跨部门的项目小组",
    "关于接口数据格式的标准化问题我们下周安排一次技术对齐会议把相关团队都拉进来",
    "销售部门反映产品文档的更新不够及时经常出现客户拿到的是过期版本的情况",
    "我们需要建立一个更顺畅的需求传递机制避免业务部门的诉求在传递过程中失真",
    "市场活动的素材需求每次都很紧急建议提前建立一个内容素材库减少临时赶工的情况",
    "各业务线的数据孤岛问题比较严重我们需要推进数据中台的建设把数据打通",
    "关于项目优先级的排序问题各部门存在不同意见需要管理层来做最终决策",
    "跨区域的业务拓展需要总部给予更多的支持特别是在资源调配和政策授权方面",
    "我们的内部沟通效率有待提升会议过多有效决策少建议推行会议管理的最佳实践",
    "OKR对齐会议建议每月一次确保各部门的工作方向和公司战略保持一致",
    # 战略/规划类
    "从长远来看我们需要在核心技术上建立自己的壁垒而不是单纯依赖商业化的解决方案",
    "对于这个新兴市场我们的策略是先占据细分领域的头部地位然后再横向扩展",
    "下一个五年规划的核心主题是数字化转型和生态系统建设这两个方向相互支撑",
    "竞争格局正在发生变化一些新进入者凭借差异化的产品在某些细分市场取得了突破",
    "我们的品牌定位需要进一步清晰化目前在客户心中的认知还比较模糊",
    "关于并购机会我们有几个潜在标的正在进行初步的尽职调查",
    "人才战略是我们中长期发展最重要的支撑之一需要从培养激励留用三个维度系统推进",
    "我们计划在两年内将研发投入占营收的比例从百分之八提升到百分之十二",
    "关于国际化的节奏我们决定采取稳健推进的策略而不是激进扩张避免资源分散",
    "数字化转型不仅仅是技术问题更是组织文化和业务模式的全面升级",
]

assert len(SENTENCES) == 100, f"需要100句，实际{len(SENTENCES)}句"


# ── 标点字符判断 ───────────────────────────────────────────────────────────────

def has_sentence_end(text: str) -> bool:
    """是否含句末标点（P1）"""
    return any(c in P1_SENTENCE_END for c in text)


def has_clause_punct(text: str) -> bool:
    """是否含子句标点（P2）"""
    return any(c in P2_CLAUSE for c in text)


def find_last_sentence_end(text: str, safe: int) -> int:
    """在 text[:safe] 中找最后一个句末标点的下标+1，未找到返回 -1"""
    for i in range(min(safe, len(text)) - 1, -1, -1):
        if text[i] in P1_SENTENCE_END:
            return i + 1
    return -1


def find_last_clause_punct(text: str, safe: int) -> int:
    """在 text[:safe] 中找最后一个子句标点的下标+1，未找到返回 -1"""
    for i in range(min(safe, len(text)) - 1, -1, -1):
        if text[i] in P2_CLAUSE:
            return i + 1
    return -1


def simulate_segmentation(original: str, punctuated: str) -> dict:
    """
    模拟 Java emitForcedSegments 分段决策。
    safe = len - FORCE_TAIL_MARGIN (原始文本)
    """
    safe = max(0, len(original) - FORCE_TAIL_MARGIN)

    # 用标点文本做判断
    det = punctuated if punctuated else original

    # P1: 句末标点
    ep = find_last_sentence_end(det, len(det))
    if ep > 0:
        # 映射回原始下标
        eo = map_to_original(original, punctuated, ep) if punctuated else ep
        if 0 < eo <= safe:
            cut_text = det[:ep].rstrip()
            return {"trigger": "sentence-punct" if punctuated else "sentence",
                    "cut_original": eo, "cut_text": cut_text, "safe": safe}

    # P2: 子句（模拟 shouldForce = True，即已超过阈值）
    if len(original) >= MAX_CHARS:
        cp = find_last_clause_punct(det, len(det))
        if cp > 0:
            co = map_to_original(original, punctuated, cp) if punctuated else cp
            if 0 < co <= safe:
                cut_text = det[:cp].rstrip()
                return {"trigger": "force-comma-punct" if punctuated else "force-comma",
                        "cut_original": co, "cut_text": cut_text, "safe": safe}

    # P3: 字符数强切
    if len(original) >= MAX_CHARS:
        return {"trigger": "force-boundary", "cut_original": safe,
                "cut_text": original[:safe], "safe": safe}

    return {"trigger": "no-cut", "cut_original": -1, "cut_text": "", "safe": safe}


def map_to_original(original: str, punctuated: str, punct_end: int) -> int:
    """双指针：将标点文本位置映射回原始文本位置"""
    orig_idx = 0
    p_idx = 0
    while p_idx < punct_end and orig_idx < len(original):
        if punctuated[p_idx] == original[orig_idx]:
            p_idx += 1
            orig_idx += 1
        else:
            p_idx += 1  # 插入标点，只推进 punctuated
    return orig_idx


# ── HTTP 调用 ─────────────────────────────────────────────────────────────────

def call_punctuate(text: str) -> tuple[Optional[str], float, bool]:
    """返回 (punctuated_text, latency_ms, model_available)"""
    payload = json.dumps({"text": text}).encode("utf-8")
    req = urllib.request.Request(
        PUNCTUATE_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            latency = (time.time() - t0) * 1000
            data = json.loads(resp.read())
            return data.get("punctuated"), latency, data.get("model_available", False)
    except Exception as e:
        latency = (time.time() - t0) * 1000
        print(f"  [ERROR] call failed after {latency:.0f}ms: {e}", file=sys.stderr)
        return None, latency, False


# ── 主测试逻辑 ────────────────────────────────────────────────────────────────

@dataclass
class Result:
    idx: int
    original: str
    punctuated: Optional[str]
    latency_ms: float
    model_available: bool
    seg_before: dict   # 不用标点的分段决策
    seg_after: dict    # 用标点的分段决策


def main():
    print(f"[config] service={PUNCTUATE_URL}")
    print(f"[config] sentences={len(SENTENCES)}")
    print()

    # 检查服务可用性
    try:
        with urllib.request.urlopen(f"{SPEAKER_SERVICE_URL}/health", timeout=3) as r:
            h = json.loads(r.read())
            model_ok = h.get("punct_model_loaded", False)
            print(f"[health] {h}")
            if not model_ok:
                print("[WARN] punct_model_loaded=false — 模型未加载，测试无意义，请先下载模型", file=sys.stderr)
                sys.exit(1)
    except Exception as e:
        print(f"[ERROR] 无法连接服务 {SPEAKER_SERVICE_URL}: {e}", file=sys.stderr)
        sys.exit(1)

    print()
    print("─" * 80)

    results: list[Result] = []
    latencies: list[float] = []
    errors = 0

    for i, sent in enumerate(SENTENCES):
        punctuated, latency_ms, model_available = call_punctuate(sent)

        seg_before = simulate_segmentation(sent, None)
        seg_after  = simulate_segmentation(sent, punctuated) if punctuated else seg_before

        r = Result(
            idx=i, original=sent, punctuated=punctuated,
            latency_ms=latency_ms, model_available=model_available,
            seg_before=seg_before, seg_after=seg_after,
        )
        results.append(r)

        if not model_available:
            errors += 1

        latencies.append(latency_ms)

        # 单句日志（便于分析）
        s_end_before = seg_before["trigger"] in ("sentence", "sentence-punct")
        s_end_after  = seg_after["trigger"] in ("sentence", "sentence-punct")
        comma_after  = seg_after["trigger"] in ("force-comma", "force-comma-punct")
        improved = (not s_end_before and s_end_after) or (
            seg_before["trigger"] == "force-boundary" and seg_after["trigger"] in ("sentence-punct", "force-comma-punct"))

        print(
            f"[result] idx={i:3d} chars={len(sent):3d} "
            f"latency={latency_ms:6.1f}ms "
            f"model={str(model_available):<5} "
            f"before={seg_before['trigger']:<16} "
            f"after={seg_after['trigger']:<20} "
            f"improved={improved}"
        )
        if punctuated:
            # 显示有意义的标点差异
            new_puncts = [c for c in punctuated if c not in sent and c in (P1_SENTENCE_END | P2_CLAUSE)]
            if new_puncts:
                print(f"         inserted={''.join(new_puncts)} | out={punctuated[:60]}{'…' if len(punctuated) > 60 else ''}")

    print()
    print("─" * 80)
    print("【延迟统计】")
    if latencies:
        latencies.sort()
        n = len(latencies)
        print(f"  样本数: {n}")
        print(f"  mean  : {statistics.mean(latencies):.1f}ms")
        print(f"  median: {statistics.median(latencies):.1f}ms")
        print(f"  p90   : {latencies[int(n * 0.9)]:.1f}ms")
        print(f"  p95   : {latencies[int(n * 0.95)]:.1f}ms")
        print(f"  max   : {latencies[-1]:.1f}ms")
        budget_ok = sum(1 for l in latencies if l <= 45)
        print(f"  ≤45ms : {budget_ok}/{n} ({100*budget_ok/n:.0f}%)  ← 我们的超时预算")

    print()
    print("【分段改善统计】")
    before_triggers = {}
    after_triggers = {}
    improved_count = 0
    for r in results:
        before_triggers[r.seg_before["trigger"]] = before_triggers.get(r.seg_before["trigger"], 0) + 1
        after_triggers[r.seg_after["trigger"]]   = after_triggers.get(r.seg_after["trigger"], 0) + 1
        bt = r.seg_before["trigger"]
        at = r.seg_after["trigger"]
        if bt != at and at in ("sentence-punct", "force-comma-punct"):
            improved_count += 1

    print("  加标点前分段类型:")
    for k, v in sorted(before_triggers.items(), key=lambda x: -x[1]):
        print(f"    {k:<22}: {v}")
    print("  加标点后分段类型:")
    for k, v in sorted(after_triggers.items(), key=lambda x: -x[1]):
        print(f"    {k:<22}: {v}")
    print(f"  因标点改善分段决策: {improved_count}/{len(results)} 句")

    print()
    print("【标点质量抽查（前10句详细对比）】")
    for r in results[:10]:
        print(f"  [{r.idx}] 原文: {r.original[:50]}{'…' if len(r.original)>50 else ''}")
        print(f"       标点: {(r.punctuated or '(无)')[:60]}{'…' if r.punctuated and len(r.punctuated)>60 else ''}")
        print()

    if errors > 0:
        print(f"[WARN] {errors} 句模型未返回结果 (model_available=false)")
    print(f"[done] 全部 {len(results)} 句处理完成")


if __name__ == "__main__":
    main()
