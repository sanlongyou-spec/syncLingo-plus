import { useEffect, useMemo, useState } from 'react'
import { getCostMonthlySummary, getCostRates, getPreMeetingUsage, getUserInterpretationSessions } from '../api'
import { ROUTES, STORAGE_KEYS } from '../constants'
import type { CostRates, InterpretationStatus, MonthlyCostSummary, PreMeetingDailyUsage } from '../types'
import './CostAnalysisView.css'

interface Cost { asr: number; trans: number; tts: number; llm: number; total: number }

function calcCost(s: InterpretationStatus, rates: CostRates): Cost {
  const asr   = (s.asrAudioMs     ?? 0) * rates.asrPerMs
  const trans  = (s.translateChars ?? 0) * rates.transPerChar
  const tts    = (s.ttsChars       ?? 0) * rates.ttsPerChar
  // LLM 拆两档：实时压缩(Haiku) + 会议总结/文档(DeepSeek)，各按各自单价计费
  const llm    = (s.llmInputTokens ?? 0) * rates.llmInPerToken
              + (s.llmOutputTokens ?? 0) * rates.llmOutPerToken
              + (s.llmSummaryInputTokens ?? 0) * rates.summaryLlmInPerToken
              + (s.llmSummaryOutputTokens ?? 0) * rates.summaryLlmOutPerToken
  return { asr, trans, tts, llm, total: asr + trans + tts + llm }
}

const DEFAULT_RATES: CostRates = {
  asrPerMs: 1.00 / 3_600_000,
  transPerChar: 10.00 / 1_000_000,
  ttsPerChar: 35.00 / 1_000_000,
  llmInPerToken: 1.00 / 1_000_000,
  llmOutPerToken: 5.00 / 1_000_000,
  summaryLlmInPerToken: 1.74 / 1_000_000,
  summaryLlmOutPerToken: 3.48 / 1_000_000,
  monthlyBudgetUsd: 0,
  sessionBudgetUsd: 0,
}

const fmtUsd  = (v: number) => v < 0.000001 ? '< $0.000001' : `$${v.toFixed(4)}`
const fmtUsd2 = (v: number) => `$${v.toFixed(6)}`
const fmtMs   = (ms: number) => {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60_000)
  const s = Math.floor((ms % 60_000) / 1000)
  return `${m}m ${s}s`
}

type DateRange = '7d' | '30d' | '90d' | 'all'

function cutoffDate(range: DateRange): Date | null {
  if (range === 'all') return null
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  const days = range === '7d' ? 7 : range === '30d' ? 30 : 90
  d.setDate(d.getDate() - days + 1)
  return d
}

// ── SVG 工具 ──────────────────────────────────────────────
function polarXY(cx: number, cy: number, r: number, angleDeg: number) {
  const rad = ((angleDeg - 90) * Math.PI) / 180
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) }
}

function donutSlicePath(
  cx: number, cy: number, outerR: number, innerR: number,
  startDeg: number, endDeg: number
): string {
  if (endDeg - startDeg >= 360) endDeg = startDeg + 359.9
  const large = endDeg - startDeg > 180 ? 1 : 0
  const o1 = polarXY(cx, cy, outerR, startDeg)
  const o2 = polarXY(cx, cy, outerR, endDeg)
  const i1 = polarXY(cx, cy, innerR, startDeg)
  const i2 = polarXY(cx, cy, innerR, endDeg)
  return [
    `M ${o1.x.toFixed(2)} ${o1.y.toFixed(2)}`,
    `A ${outerR} ${outerR} 0 ${large} 1 ${o2.x.toFixed(2)} ${o2.y.toFixed(2)}`,
    `L ${i2.x.toFixed(2)} ${i2.y.toFixed(2)}`,
    `A ${innerR} ${innerR} 0 ${large} 0 ${i1.x.toFixed(2)} ${i1.y.toFixed(2)}`,
    'Z',
  ].join(' ')
}

// ── 饼图组件 ──────────────────────────────────────────────
const SLICE_COLORS = ['#3b82f6', '#22c55e', '#a855f7', '#f59e0b']

function DonutChart({ values }: { values: number[] }) {
  const total = values.reduce((a, b) => a + b, 0)
  if (total === 0) return (
    <div className="ca-donut-empty">暂无数据</div>
  )
  const cx = 90; const cy = 90; const outerR = 76; const innerR = 48
  let angle = 0
  const slices = values.map((v, i) => {
    const sweep = (v / total) * 360
    const start = angle; angle += sweep
    return { start, end: angle, color: SLICE_COLORS[i], pct: ((v / total) * 100).toFixed(1) }
  })
  return (
    <svg viewBox="0 0 180 180" className="ca-donut-svg">
      {slices.map((s, i) => (
        <path key={i} d={donutSlicePath(cx, cy, outerR, innerR, s.start, s.end)}
          fill={s.color} opacity={0.9} />
      ))}
      <text x={cx} y={cy - 6} textAnchor="middle" className="ca-donut-center-label">总计</text>
      <text x={cx} y={cy + 12} textAnchor="middle" className="ca-donut-center-value">
        {fmtUsd(total)}
      </text>
    </svg>
  )
}

// ── 柱状图组件 ────────────────────────────────────────────
function BarChart({ days }: { days: { label: string; asr: number; trans: number; tts: number; llm: number }[] }) {
  if (days.length === 0) return <div className="ca-bar-empty">暂无数据</div>
  const W = 560; const H = 160; const padL = 48; const padR = 12; const padT = 10; const padB = 32
  const chartW = W - padL - padR
  const chartH = H - padT - padB
  const maxVal = Math.max(...days.map(d => d.asr + d.trans + d.tts + d.llm), 0.000001)
  const barW = Math.max(4, Math.min(28, (chartW / days.length) * 0.72))
  const gap  = chartW / days.length
  const yTicks = [0, 0.25, 0.5, 0.75, 1].map(f => ({ v: maxVal * f, y: padT + chartH * (1 - f) }))

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="ca-bar-svg">
      {/* 网格线 */}
      {yTicks.map((t, i) => (
        <g key={i}>
          <line x1={padL} y1={t.y} x2={W - padR} y2={t.y} stroke="rgba(0,0,0,0.06)" strokeWidth={1} />
          <text x={padL - 5} y={t.y + 4} textAnchor="end" className="ca-axis-label">
            {t.v < 0.0001 ? '0' : `$${t.v.toFixed(4)}`}
          </text>
        </g>
      ))}
      {/* 柱 */}
      {days.map((d, i) => {
        const x = padL + i * gap + gap / 2 - barW / 2
        const segments = [
          { v: d.asr,   color: '#3b82f6' },
          { v: d.trans, color: '#22c55e' },
          { v: d.tts,   color: '#a855f7' },
          { v: d.llm,   color: '#f59e0b' },
        ]
        let stackY = padT + chartH
        return (
          <g key={i}>
            {segments.map((seg, si) => {
              const barH = (seg.v / maxVal) * chartH
              stackY -= barH
              return barH > 0.5 ? (
                <rect key={si} x={x} y={stackY} width={barW} height={barH}
                  fill={seg.color} opacity={0.85} rx={si === segments.length - 1 || segments.slice(si + 1).every(s => s.v === 0) ? 2 : 0} />
              ) : null
            })}
            {/* X 轴标签 */}
            {(days.length <= 31 || i % Math.ceil(days.length / 20) === 0) && (
              <text x={x + barW / 2} y={H - 6} textAnchor="middle" className="ca-axis-label">
                {d.label.slice(5)}
              </text>
            )}
          </g>
        )
      })}
    </svg>
  )
}

type CostTab = 'sessions' | 'monthly'

// ── 主组件 ────────────────────────────────────────────────
export default function CostAnalysisView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const [sessions, setSessions]               = useState<InterpretationStatus[]>([])
  const [preMeetingUsage, setPreMeetingUsage] = useState<PreMeetingDailyUsage[]>([])
  const [loading, setLoading]                 = useState(true)
  const [range, setRange]                     = useState<DateRange>('30d')
  const [rates, setRates]                     = useState<CostRates>(DEFAULT_RATES)
  const [monthly, setMonthly]                 = useState<MonthlyCostSummary[]>([])
  const [costTab, setCostTab]                 = useState<CostTab>('sessions')

  useEffect(() => {
    setLoading(true)
    Promise.all([
      getUserInterpretationSessions(userId).then(res => res.data || []).catch(() => []),
      getPreMeetingUsage(userId).then(res => res.data || []).catch(() => []),
      getCostRates().then(res => res.data).catch(() => null),
      getCostMonthlySummary(userId).then(res => res.data || []).catch(() => []),
    ]).then(([s, p, r, m]) => {
      setSessions(s)
      setPreMeetingUsage(p)
      if (r) setRates(r)
      setMonthly(m)
    }).finally(() => setLoading(false))
  }, [userId])

  const filtered = useMemo(() => {
    const cutoff = cutoffDate(range)
    if (!cutoff) return sessions
    return sessions.filter(s => {
      if (!s.startTime) return false
      return new Date(s.startTime) >= cutoff
    })
  }, [sessions, range])

  const filteredPreMeeting = useMemo(() => {
    const cutoff = cutoffDate(range)
    if (!cutoff) return preMeetingUsage
    return preMeetingUsage.filter(p => new Date(p.date) >= cutoff)
  }, [preMeetingUsage, range])

  // 汇总
  const totals = useMemo(() => {
    let asr = 0, trans = 0, tts = 0, llm = 0
    let asrMs = 0, transChars = 0, ttsChars = 0, llmIn = 0, llmOut = 0
    for (const s of filtered) {
      const c = calcCost(s, rates)
      asr += c.asr; trans += c.trans; tts += c.tts; llm += c.llm
      asrMs += s.asrAudioMs ?? 0
      transChars += s.translateChars ?? 0
      ttsChars += s.ttsChars ?? 0
      llmIn += (s.llmInputTokens ?? 0) + (s.llmSummaryInputTokens ?? 0)
      llmOut += (s.llmOutputTokens ?? 0) + (s.llmSummaryOutputTokens ?? 0)
    }
    for (const p of filteredPreMeeting) {
      // 会前准备用 DeepSeek(文档总结模型) → 按总结档单价
      const pmLlm = (p.llmInputTokens ?? 0) * rates.summaryLlmInPerToken + (p.llmOutputTokens ?? 0) * rates.summaryLlmOutPerToken
      llm += pmLlm
      llmIn += p.llmInputTokens ?? 0
      llmOut += p.llmOutputTokens ?? 0
    }
    return { asr, trans, tts, llm, total: asr + trans + tts + llm, asrMs, transChars, ttsChars, llmIn, llmOut }
  }, [filtered, filteredPreMeeting, rates])

  // 按日聚合（用于柱状图）
  const dailyData = useMemo(() => {
    const map: Record<string, { asr: number; trans: number; tts: number; llm: number }> = {}
    for (const s of filtered) {
      const day = s.startTime?.slice(0, 10)
      if (!day) continue
      if (!map[day]) map[day] = { asr: 0, trans: 0, tts: 0, llm: 0 }
      const c = calcCost(s, rates)
      map[day].asr += c.asr; map[day].trans += c.trans
      map[day].tts += c.tts; map[day].llm += c.llm
    }
    for (const p of filteredPreMeeting) {
      if (!map[p.date]) map[p.date] = { asr: 0, trans: 0, tts: 0, llm: 0 }
      map[p.date].llm += (p.llmInputTokens ?? 0) * rates.summaryLlmInPerToken + (p.llmOutputTokens ?? 0) * rates.summaryLlmOutPerToken
    }
    const cutoff = cutoffDate(range)
    const days: { label: string; asr: number; trans: number; tts: number; llm: number }[] = []
    if (range === 'all') {
      Object.keys(map).sort().forEach(label => days.push({ label, ...map[label] }))
    } else {
      const d = new Date(cutoff!)
      const today = new Date(); today.setHours(0, 0, 0, 0)
      while (d <= today) {
        const label = d.toISOString().slice(0, 10)
        days.push({ label, ...(map[label] ?? { asr: 0, trans: 0, tts: 0, llm: 0 }) })
        d.setDate(d.getDate() + 1)
      }
    }
    return days
  }, [filtered, filteredPreMeeting, range, rates])

  // 按费用排序的 Top 10
  const topSessions = useMemo(() =>
    [...filtered]
      .map(s => ({ ...s, cost: calcCost(s, rates) }))
      .sort((a, b) => b.cost.total - a.cost.total)
      .slice(0, 10),
    [filtered, rates]
  )

  // S12: current-month budget progress
  const currentMonth = new Date().toISOString().slice(0, 7)
  const currentMonthRow = monthly.find(m => m.month === currentMonth)
  const currentMonthCost = currentMonthRow?.estimatedCostUsd ?? 0
  const monthlyBudget = rates.monthlyBudgetUsd

  const exportMonthlyCSV = () => {
    const header = 'Month,Sessions,ASR(ms),Trans(chars),TTS(chars),LLM-in(Haiku),LLM-out(Haiku),Summary-in(DeepSeek),Summary-out(DeepSeek),Cost(USD)'
    const rows = monthly.map(m =>
      [m.month, m.sessionCount, m.totalAsrMs, m.totalTransChars, m.totalTtsChars,
       m.totalLlmIn, m.totalLlmOut, m.totalSummaryLlmIn ?? 0, m.totalSummaryLlmOut ?? 0,
       m.estimatedCostUsd.toFixed(6)].join(',')
    )
    const blob = new Blob([[header, ...rows].join('\n')], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = 'cost-monthly.csv'; a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">成本分析</h1>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="ca-main">
        {loading ? (
          <div className="ca-loading">加载中...</div>
        ) : (
          <>
            {/* S12: Monthly budget progress bar */}
            {monthlyBudget > 0 && (
              <div className="ca-budget-bar-wrap">
                <div className="ca-budget-bar-header">
                  <span>本月预算进度</span>
                  <span>{fmtUsd(currentMonthCost)} / {fmtUsd(monthlyBudget)}</span>
                </div>
                <div className="ca-budget-bar-track">
                  <div
                    className={`ca-budget-bar-fill${currentMonthCost >= monthlyBudget ? ' ca-budget-bar-fill--over' : ''}`}
                    style={{ width: `${Math.min(100, (currentMonthCost / monthlyBudget) * 100).toFixed(1)}%` }}
                  />
                </div>
              </div>
            )}

            {/* Tab switcher S8 */}
            <div className="ca-tab-bar">
              <button
                className={`ca-tab-btn${costTab === 'sessions' ? ' ca-tab-btn--active' : ''}`}
                onClick={() => setCostTab('sessions')}
              >会话明细</button>
              <button
                className={`ca-tab-btn${costTab === 'monthly' ? ' ca-tab-btn--active' : ''}`}
                onClick={() => setCostTab('monthly')}
              >月度汇总</button>
            </div>

            {costTab === 'sessions' && <>
            {/* 时间范围选择器 */}
            <div className="ca-range-bar">
              <span className="ca-range-label">时间范围</span>
              {(['7d', '30d', '90d', 'all'] as DateRange[]).map(r => (
                <button
                  key={r}
                  className={`ca-range-btn${range === r ? ' ca-range-btn--active' : ''}`}
                  onClick={() => setRange(r)}
                >
                  {r === '7d' ? '近 7 天' : r === '30d' ? '近 30 天' : r === '90d' ? '近 90 天' : '全部'}
                </button>
              ))}
              <span className="ca-range-count">{filtered.length} 条会话 · {filteredPreMeeting.length} 次会前准备</span>
            </div>

            {/* 汇总卡片 */}
            <div className="ca-stat-grid">
              <div className="ca-stat-card ca-stat-card--total">
                <div className="ca-stat-label">总费用</div>
                <div className="ca-stat-value">{fmtUsd(totals.total)}</div>
                <div className="ca-stat-sub">{filtered.length} 次会话</div>
              </div>
              <div className="ca-stat-card ca-stat-card--avg">
                <div className="ca-stat-label">平均单次费用</div>
                <div className="ca-stat-value">
                  {filtered.length ? fmtUsd(totals.total / filtered.length) : '$0'}
                </div>
                <div className="ca-stat-sub">per session</div>
              </div>
              <div className="ca-stat-card ca-stat-card--asr">
                <div className="ca-stat-label">语音识别 ASR</div>
                <div className="ca-stat-value">{fmtUsd(totals.asr)}</div>
                <div className="ca-stat-sub">{fmtMs(totals.asrMs)} 音频</div>
              </div>
              <div className="ca-stat-card ca-stat-card--trans">
                <div className="ca-stat-label">机器翻译</div>
                <div className="ca-stat-value">{fmtUsd(totals.trans)}</div>
                <div className="ca-stat-sub">{totals.transChars.toLocaleString()} 字符</div>
              </div>
              <div className="ca-stat-card ca-stat-card--tts">
                <div className="ca-stat-label">语音合成 TTS</div>
                <div className="ca-stat-value">{fmtUsd(totals.tts)}</div>
                <div className="ca-stat-sub">{totals.ttsChars.toLocaleString()} 字符</div>
              </div>
              <div className="ca-stat-card ca-stat-card--llm">
                <div className="ca-stat-label">大语言模型 LLM</div>
                <div className="ca-stat-value">{fmtUsd(totals.llm)}</div>
                <div className="ca-stat-sub">{(totals.llmIn + totals.llmOut).toLocaleString()} tokens</div>
              </div>
            </div>

            {/* 图表行 */}
            <div className="ca-chart-row">
              {/* 柱状图 */}
              <div className="ca-card ca-card--bar">
                <div className="ca-card-title">每日费用趋势</div>
                <div className="ca-bar-legend">
                  {['ASR', '翻译', 'TTS', 'LLM'].map((lbl, i) => (
                    <span key={lbl} className="ca-legend-item">
                      <span className="ca-legend-dot" style={{ background: SLICE_COLORS[i] }} />
                      {lbl}
                    </span>
                  ))}
                </div>
                <BarChart days={dailyData} />
              </div>

              {/* 饼图 */}
              <div className="ca-card ca-card--donut">
                <div className="ca-card-title">费用构成</div>
                <DonutChart values={[totals.asr, totals.trans, totals.tts, totals.llm]} />
                <div className="ca-donut-breakdown">
                  {[
                    { label: 'ASR', val: totals.asr, color: SLICE_COLORS[0] },
                    { label: '翻译', val: totals.trans, color: SLICE_COLORS[1] },
                    { label: 'TTS', val: totals.tts, color: SLICE_COLORS[2] },
                    { label: 'LLM', val: totals.llm, color: SLICE_COLORS[3] },
                  ].map(({ label, val, color }) => {
                    const pct = totals.total > 0 ? ((val / totals.total) * 100).toFixed(1) : '0.0'
                    return (
                      <div key={label} className="ca-donut-row">
                        <span className="ca-legend-dot" style={{ background: color }} />
                        <span className="ca-donut-row-label">{label}</span>
                        <span className="ca-donut-row-pct">{pct}%</span>
                        <span className="ca-donut-row-val">{fmtUsd2(val)}</span>
                      </div>
                    )
                  })}
                </div>
              </div>
            </div>

            {/* Top 10 会话表格 */}
            <div className="ca-card ca-card--table">
              <div className="ca-card-title">费用最高的会话（Top 10）</div>
              {topSessions.length === 0 ? (
                <div className="ca-table-empty">该时间段内暂无会话记录</div>
              ) : (
                <div className="ca-table-wrap">
                  <table className="ca-table">
                    <thead>
                      <tr>
                        <th>会话名称</th>
                        <th>时间</th>
                        <th className="ca-th-num">ASR</th>
                        <th className="ca-th-num">翻译</th>
                        <th className="ca-th-num">TTS</th>
                        <th className="ca-th-num">LLM</th>
                        <th className="ca-th-num">合计</th>
                      </tr>
                    </thead>
                    <tbody>
                      {topSessions.map(s => (
                        <tr key={s.sessionId}>
                          <td className="ca-td-title">{s.title || '未命名同传'}</td>
                          <td className="ca-td-meta">
                            {s.startTime ? new Date(s.startTime).toLocaleDateString() : '—'}
                          </td>
                          <td className="ca-td-num">{fmtUsd2(s.cost.asr)}</td>
                          <td className="ca-td-num">{fmtUsd2(s.cost.trans)}</td>
                          <td className="ca-td-num">{fmtUsd2(s.cost.tts)}</td>
                          <td className="ca-td-num">{fmtUsd2(s.cost.llm)}</td>
                          <td className="ca-td-num ca-td-total">{fmtUsd2(s.cost.total)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            </>}

            {/* S8: Monthly summary tab */}
            {costTab === 'monthly' && (
              <div className="ca-card ca-card--table">
                <div className="ca-monthly-header">
                  <div className="ca-card-title">月度费用汇总</div>
                  <button className="ca-range-btn" onClick={exportMonthlyCSV}>导出 CSV</button>
                </div>
                {monthly.length === 0 ? (
                  <div className="ca-table-empty">暂无月度数据</div>
                ) : (
                  <div className="ca-table-wrap">
                    <table className="ca-table">
                      <thead>
                        <tr>
                          <th>月份</th>
                          <th className="ca-th-num">会话数</th>
                          <th className="ca-th-num">ASR 时长</th>
                          <th className="ca-th-num">翻译字符</th>
                          <th className="ca-th-num">TTS 字符</th>
                          <th className="ca-th-num">LLM tokens</th>
                          <th className="ca-th-num">预计费用</th>
                        </tr>
                      </thead>
                      <tbody>
                        {monthly.map(m => (
                          <tr key={m.month} className={m.month === currentMonth ? 'ca-tr-current' : ''}>
                            <td className="ca-td-title">{m.month}</td>
                            <td className="ca-td-num">{m.sessionCount}</td>
                            <td className="ca-td-num">{fmtMs(m.totalAsrMs)}</td>
                            <td className="ca-td-num">{m.totalTransChars.toLocaleString()}</td>
                            <td className="ca-td-num">{m.totalTtsChars.toLocaleString()}</td>
                            <td className="ca-td-num">{(m.totalLlmIn + m.totalLlmOut + (m.totalSummaryLlmIn ?? 0) + (m.totalSummaryLlmOut ?? 0)).toLocaleString()}</td>
                            <td className="ca-td-num ca-td-total">{fmtUsd(m.estimatedCostUsd)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}

            <div className="ca-disclaimer">
              单价按各服务商官网公开定价（2026-06）：ASR Azure ≈$1/音频小时 · 翻译 Azure ≈$10/百万字符 ·
              TTS Cartesia Sonic ≈$35/百万字符（克隆音色更高）· LLM 实时压缩 Claude Haiku 4.5 $1/$5 每百万 tokens ·
              会议总结/文档 DeepSeek V4 Pro $1.74/$3.48 每百万 tokens。LLM tokens 按文本长度估算，实际账单以各服务商结算为准。
            </div>
          </>
        )}
      </main>
    </div>
  )
}
