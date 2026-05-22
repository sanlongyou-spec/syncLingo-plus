import { useEffect, useMemo, useState } from 'react'
import {
  generateMeetingMaterialSummary,
  getMeetingMaterial,
  getUserInterpretationSessions,
  saveMeetingMaterial,
} from '../api'
import { ROUTES, STORAGE_KEYS } from '../constants'
import type { InterpretationStatus, MeetingMaterial } from '../types'
import './InterpretationView.css'
import './MeetingMaterialsView.css'

const escapeHtml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const sanitizeFilename = (name: string) =>
  name.replace(/[\\/:*?"<>|]/g, '_').trim() || 'meeting-summary'

const downloadWord = (title: string, summary: string) => {
  const html = `<html><head><meta charset="utf-8"/></head>
    <body style="font-family:Microsoft YaHei,Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;">
      <h1 style="font-size:18pt;">${escapeHtml(title)}</h1>
      <pre style="white-space:pre-wrap;font-family:inherit;">${escapeHtml(summary)}</pre>
    </body></html>`
  const blob = new Blob(['\ufeff', html], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${sanitizeFilename(title)}.doc`
  link.click()
  URL.revokeObjectURL(url)
}

const exportPdf = (title: string, summary: string) => {
  const win = window.open('', '_blank')
  if (!win) return
  win.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"/>
    <title>${escapeHtml(title)}</title>
    <style>
      body{font-family:"Microsoft YaHei",Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;color:#111827;}
      h1{font-size:18pt;margin-bottom:18px;}
      pre{white-space:pre-wrap;word-break:break-word;font-family:inherit;}
      button{margin-top:24px;padding:8px 18px;border:1px solid #d0d5dd;border-radius:8px;background:#fff;cursor:pointer;}
      @media print{button{display:none;}}
    </style></head><body>
      <h1>${escapeHtml(title)}</h1>
      <pre>${escapeHtml(summary)}</pre>
      <button onclick="window.print()">打印 / 另存为 PDF</button>
    </body></html>`)
  win.document.close()
  win.focus()
  setTimeout(() => win.print(), 400)
}

export default function MeetingMaterialsView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const activeSessionId = localStorage.getItem(STORAGE_KEYS.CURRENT_SESSION_ID) || ''
  const [sessions, setSessions] = useState<InterpretationStatus[]>([])
  const [sessionId, setSessionId] = useState(activeSessionId)
  const [form, setForm] = useState<MeetingMaterial>({
    title: '',
    agendaText: '',
    reportText: '',
    executiveNames: '',
    summaryText: '',
  })
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [message, setMessage] = useState('')

  const selectedSession = useMemo(
    () => sessions.find(item => item.sessionId === sessionId),
    [sessions, sessionId],
  )

  const title = form.title || selectedSession?.title || '会议资料总结'

  useEffect(() => {
    getUserInterpretationSessions(userId)
      .then(res => {
        const list = res.data || []
        setSessions(list)
        if (!sessionId && list.length > 0) {
          setSessionId(list[0].sessionId)
        }
      })
      .catch(() => setSessions([]))
  }, [userId])

  useEffect(() => {
    if (!sessionId) return
    setLoading(true)
    getMeetingMaterial(sessionId)
      .then(res => {
        const data = res.data || {}
        setForm({
          title: data.title || '',
          agendaText: data.agendaText || '',
          reportText: data.reportText || '',
          executiveNames: data.executiveNames || '',
          summaryText: data.summaryText || '',
        })
      })
      .catch(() => {
        setForm({ title: '', agendaText: '', reportText: '', executiveNames: '', summaryText: '' })
      })
      .finally(() => setLoading(false))
  }, [sessionId])

  const updateField = (key: keyof MeetingMaterial, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }))
  }

  const save = async () => {
    if (!sessionId) return
    setSaving(true)
    setMessage('')
    try {
      const res = await saveMeetingMaterial(sessionId, form)
      const data = res.data || {}
      setForm(prev => ({ ...prev, ...data }))
      setMessage('会议安排和报告已保存')
    } finally {
      setSaving(false)
    }
  }

  const generate = async () => {
    if (!sessionId) return
    setGenerating(true)
    setMessage('')
    try {
      await saveMeetingMaterial(sessionId, form)
      const res = await generateMeetingMaterialSummary(sessionId)
      const data = res.data || {}
      setForm(prev => ({ ...prev, ...data }))
      setMessage('总结已生成并绑定到当前会议')
    } finally {
      setGenerating(false)
    }
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">会议资料总结</h1>
          <span className="si-brand-sub">用会议安排、报告和实时文本生成绑定会议的管理层纪要</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="materials-main">
        <section className="materials-panel materials-config">
          <div className="materials-field">
            <label>绑定会议</label>
            <select value={sessionId} onChange={event => setSessionId(event.target.value)}>
              {activeSessionId && <option value={activeSessionId}>当前会议 {activeSessionId.slice(0, 8)}</option>}
              {sessions.map(session => (
                <option key={session.sessionId} value={session.sessionId}>
                  {session.title || session.sessionId.slice(0, 8)}
                </option>
              ))}
            </select>
          </div>

          <div className="materials-field">
            <label>总结标题</label>
            <input
              value={form.title || ''}
              onChange={event => updateField('title', event.target.value)}
              placeholder="例如：013B 号会议总结"
            />
          </div>

          <div className="materials-field">
            <label>高管/重点发言人</label>
            <input
              value={form.executiveNames || ''}
              onChange={event => updateField('executiveNames', event.target.value)}
              placeholder="例如：Ma Diyong, JL, Zhang Bing"
            />
          </div>

          <div className="materials-field">
            <label>会议安排</label>
            <textarea
              value={form.agendaText || ''}
              onChange={event => updateField('agendaText', event.target.value)}
              placeholder="粘贴会议通知、议程、参会人员、时间安排..."
            />
          </div>

          <div className="materials-field">
            <label>会议报告</label>
            <textarea
              value={form.reportText || ''}
              onChange={event => updateField('reportText', event.target.value)}
              placeholder="粘贴会前报告、项目说明、数据分析材料..."
            />
          </div>

          <div className="materials-actions">
            <button onClick={save} disabled={!sessionId || saving || loading}>
              {saving ? '保存中...' : '保存资料'}
            </button>
            <button className="materials-primary" onClick={generate} disabled={!sessionId || generating || loading}>
              {generating ? '生成中...' : '生成总结'}
            </button>
          </div>
          {message && <div className="materials-message">{message}</div>}
        </section>

        <section className="materials-panel materials-summary">
          <div className="materials-summary-head">
            <div>
              <h2>{title}</h2>
              <p>{form.summaryText ? '已生成总结' : '总结会显示在这里'}</p>
            </div>
            <div className="materials-export-actions">
              <button disabled={!form.summaryText} onClick={() => downloadWord(title, form.summaryText || '')}>
                导出 Word
              </button>
              <button disabled={!form.summaryText} onClick={() => exportPdf(title, form.summaryText || '')}>
                导出 PDF
              </button>
            </div>
          </div>
          <pre className={`materials-summary-text${form.summaryText ? '' : ' materials-summary-text--empty'}`}>
            {form.summaryText || '先保存会议安排和报告，再点击“生成总结”。系统会结合本会议的实时文本记录，并优先整理高管发言。'}
          </pre>
        </section>
      </main>
    </div>
  )
}
