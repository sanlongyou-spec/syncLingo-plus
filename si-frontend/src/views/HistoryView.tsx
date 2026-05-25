import { useEffect, useMemo, useRef, useState } from 'react'
import {
  deleteInterpretationSession,
  getMeetingSummary,
  getPublicInterpretationResults,
  getUserInterpretationSessions,
  regenerateMeetingSummary,
  updateInterpretationSessionTitle,
} from '../api'
import { ROUTES, STORAGE_KEYS } from '../constants'
import type { InterpretationResultItem, InterpretationStatus } from '../types'
import './InterpretationView.css'
import './HistoryView.css'

const DEFAULT_HISTORY_TITLE = '未命名同传'

// ── 导出工具 ──────────────────────────────────────────────
const escapeHtml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const sanitizeFilename = (name: string) =>
  name.replace(/[\\/:*?"<>|]/g, '_').trim() || '同传记录'

const downloadWord = (title: string, rows: InterpretationResultItem[]) => {
  const body = rows.map(item => `
    <p>${escapeHtml(item.sourceText)}</p>
    <p>${escapeHtml(item.translatedText || '')}</p>
    <br/>
  `).join('')
  const html = `<html><head><meta charset="utf-8"/></head>
    <body style="font-family:Microsoft YaHei,Arial,sans-serif;line-height:1.8;font-size:12pt;">
      ${body || '<p></p>'}
    </body></html>`
  const blob = new Blob(['﻿', html], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${sanitizeFilename(title)}.doc`
  link.click()
  URL.revokeObjectURL(url)
}

const fmtMs = (ms: number) => {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`
}

// ── 组件 ────────────────────────────────────────────────
export default function HistoryView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const [keyword, setKeyword] = useState('')
  const [sessions, setSessions] = useState<InterpretationStatus[]>([])
  const [selectedSessionId, setSelectedSessionId] = useState('')
  const [results, setResults] = useState<InterpretationResultItem[]>([])
  const [loading, setLoading] = useState(false)
  const [activeTab, setActiveTab] = useState<'transcript' | 'summary'>('transcript')
  const [summaryText, setSummaryText] = useState<string | null>(null)
  const [summaryLoading, setSummaryLoading] = useState(false)
  const hasFetchedSummaryRef = useRef(false)
  const SUMMARY_REQ_KEY = 'si_summary_requirements'
  const [summaryRequirements, setSummaryRequirements] = useState<string>(
    () => localStorage.getItem(SUMMARY_REQ_KEY) ?? ''
  )

  const selectedSession = useMemo(
    () => sessions.find(item => item.sessionId === selectedSessionId),
    [sessions, selectedSessionId],
  )
  const selectedMeetingTitle = selectedSession?.title || DEFAULT_HISTORY_TITLE

  const loadSessions = async (nextKeyword = keyword) => {
    setLoading(true)
    try {
      const res = await getUserInterpretationSessions(userId, nextKeyword)
      const list = res.data || []
      setSessions(list)
      if (!selectedSessionId && list.length > 0) setSelectedSessionId(list[0].sessionId)
      if (selectedSessionId && !list.some(item => item.sessionId === selectedSessionId)) {
        setSelectedSessionId(list[0]?.sessionId || '')
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void loadSessions() }, [])

  useEffect(() => {
    if (!selectedSessionId) { setResults([]); return }
    getPublicInterpretationResults(selectedSessionId)
      .then(res => setResults(res.data || []))
      .catch(() => setResults([]))
  }, [selectedSessionId])

  useEffect(() => {
    setSummaryText(null)
    setSummaryLoading(false)
    hasFetchedSummaryRef.current = false
    setActiveTab('transcript')
  }, [selectedSessionId])

  useEffect(() => {
    if (activeTab !== 'summary' || !selectedSessionId) return
    if (hasFetchedSummaryRef.current) return
    const session = sessions.find(s => s.sessionId === selectedSessionId)
    if (session?.meetingSummary) return
    hasFetchedSummaryRef.current = true
    void fetchSummary(selectedSessionId)
  }, [activeTab, selectedSessionId])

  const fetchSummary = async (sessionId: string) => {
    setSummaryLoading(true)
    try {
      const res = await getMeetingSummary(sessionId)
      setSummaryText(res.data?.summary ?? '')
    } catch {
      setSummaryText('')
    } finally {
      setSummaryLoading(false)
    }
  }

  const refetchSummary = async (sessionId: string) => {
    setSummaryLoading(true)
    try {
      const res = await regenerateMeetingSummary(sessionId, summaryRequirements.trim() || undefined)
      setSummaryText(res.data?.summary ?? '')
    } catch {
      setSummaryText('')
    } finally {
      setSummaryLoading(false)
    }
  }

  const saveDefaultRequirements = () => {
    localStorage.setItem(SUMMARY_REQ_KEY, summaryRequirements)
  }

  const renameSession = async (session: InterpretationStatus) => {
    const title = window.prompt('记录名称', session.title || DEFAULT_HISTORY_TITLE)
    if (title == null) return
    await updateInterpretationSessionTitle(session.sessionId, userId, title)
    await loadSessions()
  }

  const removeSession = async (session: InterpretationStatus) => {
    if (!window.confirm(`删除"${session.title || DEFAULT_HISTORY_TITLE}"？`)) return
    await deleteInterpretationSession(session.sessionId, userId)
    if (selectedSessionId === session.sessionId) setSelectedSessionId('')
    await loadSessions()
  }

  const exportSessionWord = async (session: InterpretationStatus) => {
    const res = await getPublicInterpretationResults(session.sessionId)
    downloadWord(session.title || '同传记录', res.data || [])
  }

  const exportSummaryWord = (title: string, text: string) => {
    const html = `<html><head><meta charset="utf-8"/></head>
      <body style="font-family:Microsoft YaHei,Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;">
        <h1 style="font-size:16pt;">${escapeHtml(title)} — 会议总结</h1>
        <pre style="white-space:pre-wrap;font-family:inherit;">${escapeHtml(text)}</pre>
      </body></html>`
    const blob = new Blob(['﻿', html], { type: 'application/msword' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${sanitizeFilename(title)}-会议总结.doc`
    link.click()
    URL.revokeObjectURL(url)
  }

  const exportSummaryPdf = (title: string, text: string) => {
    const win = window.open('', '_blank')
    if (!win) return
    win.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"/>
      <title>${escapeHtml(title)} — 会议总结</title>
      <style>
        body{font-family:"Microsoft YaHei",Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;}
        h1{font-size:16pt;margin-bottom:18px;}
        pre{white-space:pre-wrap;word-break:break-word;font-family:inherit;}
        .print-btn{margin-top:24px;padding:8px 20px;font-size:13px;cursor:pointer;}
        @media print{.print-btn{display:none;}}
      </style>
      </head><body>
        <h1>${escapeHtml(title)} — 会议总结</h1>
        <pre>${escapeHtml(text)}</pre>
        <button class="print-btn" onclick="window.print()">打印 / 另存为 PDF</button>
      </body></html>`)
    win.document.close()
    win.focus()
    setTimeout(() => win.print(), 400)
  }

  const renderMeetingTitleStrip = () => selectedSession ? (
    <div className="history-meeting-title-strip">
      <span>会议名称</span>
      <strong>{selectedMeetingTitle}</strong>
    </div>
  ) : null

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">历史记录</h1>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="history-main">
        <aside className="history-sidebar">
          <form
            className="history-search"
            onSubmit={event => { event.preventDefault(); void loadSessions(keyword) }}
          >
            <input
              value={keyword}
              onChange={event => setKeyword(event.target.value)}
              placeholder="搜索名称、原文或译文"
            />
            <button type="submit">查询</button>
          </form>

          <div className="history-list">
            {loading && <div className="history-empty">加载中...</div>}
            {!loading && sessions.length === 0 && <div className="history-empty">暂无记录</div>}
            {sessions.map(session => (
              <div
                key={session.sessionId}
                className={`history-item ${selectedSessionId === session.sessionId ? 'history-item--active' : ''}`}
              >
                <button
                  className="history-item-main"
                  onClick={() => setSelectedSessionId(session.sessionId)}
                  type="button"
                >
                  <span className="history-item-title">{session.title || DEFAULT_HISTORY_TITLE}</span>
                  <span className="history-item-meta">
                    {session.startTime ? new Date(session.startTime).toLocaleString() : '未记录时间'}
                  </span>
                  <span className="history-item-meta">
                    {session.resultCount ?? 0} 条文本
                    {(session.asrAudioMs ?? 0) > 0 && (
                      <> · {fmtMs(session.asrAudioMs!)} ASR</>
                    )}
                  </span>
                </button>
                <button
                  className="history-item-export"
                  onClick={() => { void exportSessionWord(session) }}
                  disabled={(session.resultCount ?? 0) === 0}
                  type="button"
                >
                  导出
                </button>
              </div>
            ))}
          </div>
        </aside>

        <section className="history-detail">
          <div className="history-detail-toolbar">
            <div>
              <h2>{selectedSession?.title || '请选择记录'}</h2>
              {selectedSession?.startTime && (
                <p>{new Date(selectedSession.startTime).toLocaleString()}</p>
              )}
            </div>
            {selectedSession && (
              <div className="history-detail-actions">
                <button onClick={() => { void renameSession(selectedSession) }}>命名</button>
                <button
                  onClick={() => { void removeSession(selectedSession) }}
                  className="history-danger"
                >
                  删除
                </button>
              </div>
            )}
          </div>

          {/* Tab 导航 */}
          {selectedSession && (
            <div className="history-tab-nav">
              <button
                className={`history-tab-btn${activeTab === 'transcript' ? ' history-tab-btn--active' : ''}`}
                onClick={() => setActiveTab('transcript')}
              >文本记录</button>
              <button
                className={`history-tab-btn${activeTab === 'summary' ? ' history-tab-btn--active' : ''}`}
                onClick={() => setActiveTab('summary')}
              >会议总结</button>
            </div>
          )}

          {/* 文本记录 Tab */}
          {activeTab === 'transcript' && (
            <div className="history-tab-content">
              {renderMeetingTitleStrip()}
              <div className="si-tri-transcript-dock history-transcripts">
                <div className="si-tri-transcript-dock-inner">
                {!selectedSession && <div className="si-tri-empty">请从左侧选择一条记录</div>}
                {selectedSession && results.length === 0 && <div className="si-tri-empty">暂无文本</div>}
                {results.map(item => (
                  <div key={item.id} className="si-tri-block">
                    <div className="si-tri-share-line">{item.sourceText}</div>
                    <div className="si-tri-share-line si-tri-share-line--translated">
                      {item.translatedText}
                    </div>
                  </div>
                ))}
              </div>
              </div>
            </div>
          )}

          {/* 会议总结 Tab */}
          {selectedSession && activeTab === 'summary' && (() => {
            const displayed = summaryText ?? selectedSession.meetingSummary ?? null
            return (
              <div className="history-summary-tab">
                {renderMeetingTitleStrip()}
                {(summaryLoading || displayed === null) && (
                  <div className="history-summary-loading">
                    <span className="history-summary-spinner" />
                    会议总结生成中，请稍候...
                  </div>
                )}
                {!summaryLoading && displayed !== null && displayed === '' && (
                  <div className="history-summary-empty">该会话没有可用的文字记录，无法生成总结</div>
                )}
                <div className="history-summary-requirements">
                  <div className="history-summary-requirements-header">
                    <label className="history-summary-requirements-label">总结要求（可选）</label>
                    <button
                      className="history-summary-requirements-default-btn"
                      onClick={saveDefaultRequirements}
                      title="将当前内容保存为默认要求"
                    >设为默认</button>
                  </div>
                  <textarea
                    className="history-summary-requirements-input"
                    value={summaryRequirements}
                    onChange={e => setSummaryRequirements(e.target.value)}
                    placeholder="例如：重点突出决议和待办事项，输出中英双语，按议题分段..."
                    rows={3}
                  />
                </div>
                {!summaryLoading && (displayed === null || displayed === '') && (
                  <div className="history-summary-regen-row">
                    <button
                      className="history-summary-regen-btn"
                      onClick={() => { void refetchSummary(selectedSession.sessionId) }}
                    >生成总结</button>
                  </div>
                )}
                {!summaryLoading && displayed !== null && displayed !== '' && (
                  <>
                    <div className="history-summary-toolbar">
                      <div className="history-summary-toolbar-actions">
                        <button
                          className="history-summary-export-btn"
                          onClick={() => exportSummaryWord(selectedSession.title || '同传记录', displayed)}
                        >导出 Word</button>
                        <button
                          className="history-summary-export-btn"
                          onClick={() => exportSummaryPdf(selectedSession.title || '同传记录', displayed)}
                        >导出 PDF</button>
                      </div>
                      <button
                        className="history-summary-regen-btn"
                        onClick={() => { void refetchSummary(selectedSession.sessionId) }}
                      >重新生成</button>
                    </div>
                    <div className="history-summary-body">
                      <pre className="history-summary-text">{displayed}</pre>
                    </div>
                  </>
                )}
              </div>
            )
          })()}

        </section>
      </main>
    </div>
  )
}
