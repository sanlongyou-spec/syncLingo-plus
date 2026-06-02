import { useEffect, useMemo, useRef, useState } from 'react'
import {
  deleteInterpretationSession,
  deleteMeeting,
  extractActionItems,
  getActionItems,
  getMeetings,
  getMeetingSessions,
  getMeetingSummary,
  getPublicInterpretationResults,
  getSpeakerSummaries,
  regenerateMeetingSummary,
  regenerateSpeakerSummary,
  sendSummaryToMeetingChat,
  sendTeamsSummaryToUsers,
  updateActionItemStatus,
} from '../api'
import { ROUTES, STORAGE_KEYS, TEAMS_BOT_STORAGE_KEYS } from '../constants'
import type {
  InterpretationResultItem,
  InterpretationStatus,
  Meeting,
  MeetingActionItem,
  MeetingFile,
  MeetingParticipant,
  PreMeetingAttendanceResult,
  SpeakerSummaryRecord,
} from '../types'
import './InterpretationView.css'
import './HistoryView.css'

const DEFAULT_TITLE = '未命名会议'
const SUMMARY_REQ_KEY = 'si_summary_requirements'

type SpeakerActionStatus = 'idle' | 'loading' | 'done' | 'error'

const mergeParticipants = (participants: MeetingParticipant[]) => {
  const byAadId = new Map<string, MeetingParticipant>()
  participants.forEach(participant => {
    if (!participant.aadId) return
    const previous = byAadId.get(participant.aadId)
    byAadId.set(participant.aadId, {
      aadId: participant.aadId,
      displayName: participant.displayName || previous?.displayName || null,
      email: participant.email || previous?.email || null,
    })
  })
  return Array.from(byAadId.values())
}

const readStoredKnownParticipants = () => {
  try {
    const saved = localStorage.getItem(TEAMS_BOT_STORAGE_KEYS.ALL_KNOWN_PARTICIPANTS)
    return saved ? mergeParticipants(JSON.parse(saved) as MeetingParticipant[]) : []
  } catch {
    return []
  }
}

const readStoredRecipientSet = (key: string) => {
  try {
    const saved = localStorage.getItem(key)
    if (!saved) return new Set<string>()
    const parsed = JSON.parse(saved)
    if (!Array.isArray(parsed)) return new Set<string>()
    return new Set(parsed.filter((item): item is string => typeof item === 'string' && item.trim() !== ''))
  } catch {
    return new Set<string>()
  }
}

const saveStoredRecipientSet = (key: string, recipients: Set<string>) => {
  localStorage.setItem(key, JSON.stringify(Array.from(recipients)))
}

const parseSavedAttendance = (json?: string | null) => {
  if (!json) return { result: null, participants: [] as MeetingParticipant[] }
  try {
    const parsed = JSON.parse(json)
    if (parsed.result && parsed.participants) {
      return {
        result: parsed.result as PreMeetingAttendanceResult,
        participants: mergeParticipants(parsed.participants as MeetingParticipant[]),
      }
    }
    return { result: parsed as PreMeetingAttendanceResult, participants: [] as MeetingParticipant[] }
  } catch {
    return { result: null, participants: [] as MeetingParticipant[] }
  }
}

const escapeHtml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const sanitizeFilename = (name: string) =>
  name.replace(/[\\/:*?"<>|]/g, '_').trim() || '记录'

const downloadWord = (title: string, rows: InterpretationResultItem[]) => {
  const body = rows.map(item => `
    <p>${escapeHtml(item.sourceText)}</p>
    <p>${escapeHtml(item.translatedText || '')}</p><br/>
  `).join('')
  const html = `<html><head><meta charset="utf-8"/></head>
    <body style="font-family:Microsoft YaHei,Arial,sans-serif;line-height:1.8;font-size:12pt;">${body}</body></html>`
  const blob = new Blob(['﻿', html], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `${sanitizeFilename(title)}.doc`; a.click()
  URL.revokeObjectURL(url)
}

const exportSummaryWord = (title: string, text: string) => {
  const html = `<html><head><meta charset="utf-8"/></head>
    <body style="font-family:Microsoft YaHei,Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;">
      <h1 style="font-size:16pt;">${escapeHtml(title)} — 会议总结</h1>
      <pre style="white-space:pre-wrap;font-family:inherit;">${escapeHtml(text)}</pre>
    </body></html>`
  const blob = new Blob(['﻿', html], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `${sanitizeFilename(title)}-会议总结.doc`; a.click()
  URL.revokeObjectURL(url)
}

const exportSummaryPdf = (title: string, text: string) => {
  const win = window.open('', '_blank')
  if (!win) return
  win.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"/>
    <title>${escapeHtml(title)} — 会议总结</title>
    <style>body{font-family:"Microsoft YaHei",Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;}
    h1{font-size:16pt;}pre{white-space:pre-wrap;word-break:break-word;font-family:inherit;}
    .btn{margin-top:24px;padding:8px 20px;cursor:pointer;}@media print{.btn{display:none;}}</style>
    </head><body><h1>${escapeHtml(title)} — 会议总结</h1><pre>${escapeHtml(text)}</pre>
    <button class="btn" onclick="window.print()">打印 / 另存为 PDF</button></body></html>`)
  win.document.close(); win.focus()
  setTimeout(() => win.print(), 400)
}

export default function HistoryView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')

  // ── Meeting list ────────────────────────────────────────
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [meetingsLoading, setMeetingsLoading] = useState(false)
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)
  const [activeTab, setActiveTab] = useState<'files' | 'transcript' | 'speakers' | 'summary' | 'attendance'>('files')

  // ── Session within meeting ───────────────────────────────
  const [sessions, setSessions] = useState<InterpretationStatus[]>([])
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null)

  // ── Transcript tab ──────────────────────────────────────
  const [results, setResults] = useState<InterpretationResultItem[]>([])
  const [resultsLoading, setResultsLoading] = useState(false)

  // ── Summary tab ─────────────────────────────────────────
  const [summaryText, setSummaryText] = useState<string | null>(null)
  const [summaryLoading, setSummaryLoading] = useState(false)
  const [summaryRequirements, setSummaryRequirements] = useState(
    () => localStorage.getItem(SUMMARY_REQ_KEY) ?? ''
  )
  const [summaryReqSaved, setSummaryReqSaved] = useState(false)
  const [teamsPushStatus, setTeamsPushStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const [chatPushStatus, setChatPushStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const [includeMeetingChat, setIncludeMeetingChat] = useState(
    () => localStorage.getItem(STORAGE_KEYS.MEETING_SUMMARY_INCLUDE_CHAT) === 'true'
  )
  const [selectedRecipients, setSelectedRecipients] = useState<Set<string>>(
    () => readStoredRecipientSet(STORAGE_KEYS.MEETING_SUMMARY_RECIPIENTS)
  )
  const hasFetchedSummaryRef = useRef(false)

  // ── Speaker tab ─────────────────────────────────────────
  const [speakerRecords, setSpeakerRecords] = useState<SpeakerSummaryRecord[]>([])
  const [speakerLoading, setSpeakerLoading] = useState(false)
  const [speakerRequirements, setSpeakerRequirements] = useState(
    () => localStorage.getItem(STORAGE_KEYS.SPEAKER_SUMMARY_REQUIREMENTS) ?? ''
  )
  const [speakerReqSaved, setSpeakerReqSaved] = useState(false)
  const [selectedSpeakerRecipients, setSelectedSpeakerRecipients] = useState<Set<string>>(
    () => readStoredRecipientSet(STORAGE_KEYS.SPEAKER_SUMMARY_RECIPIENTS)
  )
  const [speakerRegenStatus, setSpeakerRegenStatus] = useState<Record<string, SpeakerActionStatus>>({})
  const [speakerPushStatus, setSpeakerPushStatus] = useState<Record<string, SpeakerActionStatus>>({})

  // ── Action items tab ─────────────────────────────────────
  const [actionItems, setActionItems] = useState<MeetingActionItem[]>([])
  const [actionItemsLoading, setActionItemsLoading] = useState(false)
  const [extractingActionItems, setExtractingActionItems] = useState(false)

  const selectedMeeting = meetings.find(m => m.id === selectedMeetingId) ?? null
  const allKnownParticipants = useMemo(() => {
    const participantsFromMeetings = meetings.flatMap(meeting =>
      parseSavedAttendance(meeting.attendanceJson).participants
    )
    return mergeParticipants([...readStoredKnownParticipants(), ...participantsFromMeetings])
  }, [meetings])

  useEffect(() => {
    if (allKnownParticipants.length === 0) return
    localStorage.setItem(
      TEAMS_BOT_STORAGE_KEYS.ALL_KNOWN_PARTICIPANTS,
      JSON.stringify(allKnownParticipants),
    )
  }, [allKnownParticipants])

  // ── Load meetings ────────────────────────────────────────
  useEffect(() => {
    setMeetingsLoading(true)
    getMeetings(userId)
      .then(res => {
        const list = res.data || []
        setMeetings(list)
        if (list.length > 0 && selectedMeetingId === null) setSelectedMeetingId(list[0].id)
      })
      .catch(() => {})
      .finally(() => setMeetingsLoading(false))
  }, [userId])

  // ── When meeting changes, reset tabs and load sessions ──
  useEffect(() => {
    if (selectedMeetingId === null) return
    setActiveTab('files')
    setResults([])
    setSummaryText(null)
    setSpeakerRecords([])
    setSessions([])
    setSelectedSessionId(null)
    setSelectedRecipients(readStoredRecipientSet(STORAGE_KEYS.MEETING_SUMMARY_RECIPIENTS))
    setSelectedSpeakerRecipients(readStoredRecipientSet(STORAGE_KEYS.SPEAKER_SUMMARY_RECIPIENTS))
    setSpeakerRegenStatus({})
    setSpeakerPushStatus({})
    setTeamsPushStatus('idle')
    setChatPushStatus('idle')
    hasFetchedSummaryRef.current = false

    getMeetingSessions(selectedMeetingId)
      .then(res => {
        const list = res.data || []
        setSessions(list)
        if (list.length > 0) setSelectedSessionId(list[0].sessionId)
      })
      .catch(() => {})
  }, [selectedMeetingId])

  // ── Load tab data lazily ─────────────────────────────────
  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'transcript') return
    setResultsLoading(true)
    getPublicInterpretationResults(selectedSessionId)
      .then(res => setResults(res.data || []))
      .catch(() => setResults([]))
      .finally(() => setResultsLoading(false))
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'summary') return

    if (hasFetchedSummaryRef.current) return
    hasFetchedSummaryRef.current = true
    setSummaryLoading(true)
    getMeetingSummary(selectedSessionId)
      .then(res => setSummaryText(res.data?.summary ?? ''))
      .catch(() => setSummaryText(''))
      .finally(() => setSummaryLoading(false))
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'speakers') return
    setSpeakerLoading(true)
    getSpeakerSummaries(selectedSessionId)
      .then(res => setSpeakerRecords(res.data || []))
      .catch(() => setSpeakerRecords([]))
      .finally(() => setSpeakerLoading(false))
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) { setActionItems([]); return }
    setActionItemsLoading(true)
    getActionItems(selectedSessionId)
      .then(res => setActionItems(res.data || []))
      .catch(() => setActionItems([]))
      .finally(() => setActionItemsLoading(false))
  }, [selectedSessionId])

  const handleExtractActionItems = async () => {
    if (!selectedSessionId) return
    setExtractingActionItems(true)
    try {
      const res = await extractActionItems(selectedSessionId, selectedMeetingId, userId)
      setActionItems(res.data || [])
    } catch { /* ignore */ }
    finally { setExtractingActionItems(false) }
  }

  const handleToggleActionItem = async (item: MeetingActionItem) => {
    const next = item.status === 'done' ? 'pending' : 'done'
    try {
      const res = await updateActionItemStatus(item.id, next)
      setActionItems(prev => prev.map(a => a.id === item.id ? res.data : a))
    } catch { /* ignore */ }
  }

  const refetchSummary = async () => {
    if (!selectedSessionId) return
    setSummaryLoading(true)
    hasFetchedSummaryRef.current = true
    try {
      const res = await regenerateMeetingSummary(selectedSessionId, summaryRequirements.trim() || undefined)
      setSummaryText(res.data?.summary ?? '')
    } catch { setSummaryText('') }
    finally { setSummaryLoading(false) }
  }

  const pushSummaryToChat = async (text: string) => {
    setChatPushStatus('loading')
    try {
      const res = await sendSummaryToMeetingChat(text)
      if (!res.sent) throw new Error('发送失败')
      setChatPushStatus('done')
      setTimeout(() => setChatPushStatus('idle'), 3000)
    } catch {
      setChatPushStatus('error')
      setTimeout(() => setChatPushStatus('idle'), 3000)
    }
  }

  const pushSummaryToTeams = async (text: string, recipients: string[]) => {
    if (recipients.length === 0) return
    setTeamsPushStatus('loading')
    try {
      const res = await sendTeamsSummaryToUsers(text, recipients)
      if (!res.sent) throw new Error(res.failures[0]?.error || '没有 Teams 用户收到摘要')
      setTeamsPushStatus('done')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
    } catch {
      setTeamsPushStatus('error')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
    }
  }

  const saveSummaryRequirementsDefault = () => {
    localStorage.setItem(SUMMARY_REQ_KEY, summaryRequirements)
    setSummaryReqSaved(true)
    setTimeout(() => setSummaryReqSaved(false), 1500)
  }

  const saveSpeakerRequirementsDefault = () => {
    localStorage.setItem(STORAGE_KEYS.SPEAKER_SUMMARY_REQUIREMENTS, speakerRequirements)
    setSpeakerReqSaved(true)
    setTimeout(() => setSpeakerReqSaved(false), 1500)
  }

  const toggleSummaryRecipient = (aadId: string) => {
    setSelectedRecipients(prev => {
      const next = new Set(prev)
      next.has(aadId) ? next.delete(aadId) : next.add(aadId)
      saveStoredRecipientSet(STORAGE_KEYS.MEETING_SUMMARY_RECIPIENTS, next)
      return next
    })
  }

  const toggleSpeakerRecipient = (aadId: string) => {
    setSelectedSpeakerRecipients(prev => {
      const next = new Set(prev)
      next.has(aadId) ? next.delete(aadId) : next.add(aadId)
      saveStoredRecipientSet(STORAGE_KEYS.SPEAKER_SUMMARY_RECIPIENTS, next)
      return next
    })
  }

  const regenerateSpeakerRecord = async (record: SpeakerSummaryRecord, statusKey: string) => {
    if (!record.id) return
    setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'loading' }))
    try {
      const res = await regenerateSpeakerSummary(record.id, speakerRequirements.trim() || undefined)
      setSpeakerRecords(prev => prev.map(item =>
        item.id === record.id
          ? {
              ...item,
              title: res.data?.title ?? null,
              summary: res.data?.summary ?? '',
            }
          : item
      ))
      setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'done' }))
      setTimeout(() => {
        setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 1500)
    } catch {
      setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'error' }))
      setTimeout(() => {
        setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    }
  }

  const pushSpeakerSummaryToTeams = async (
    record: SpeakerSummaryRecord,
    recipients: string[],
    statusKey: string,
  ) => {
    if (recipients.length === 0) return
    setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'loading' }))
    try {
      const titlePart = record.title ? `【${record.title}】` : ''
      const res = await sendTeamsSummaryToUsers(
        `${record.speakerName}${titlePart} 发言摘要：\n\n${record.summary}`,
        recipients,
      )
      if (!res.sent) throw new Error(res.failures[0]?.error || '没有 Teams 用户收到摘要')
      setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'done' }))
      setTimeout(() => {
        setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    } catch {
      setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'error' }))
      setTimeout(() => {
        setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    }
  }

  const deleteSessionAndRefresh = async (sessionId: string) => {
    if (!window.confirm('删除该同传会话记录？')) return
    await deleteInterpretationSession(sessionId, userId)
    setSessions(prev => prev.filter(s => s.sessionId !== sessionId))
    if (selectedSessionId === sessionId) {
      const remaining = sessions.filter(s => s.sessionId !== sessionId)
      setSelectedSessionId(remaining[0]?.sessionId ?? null)
    }
  }

  const deleteMeetingAndRefresh = async (meetingId: number) => {
    if (!window.confirm('删除该会议及其所有文件记录？')) return
    await deleteMeeting(meetingId)
    setMeetings(prev => prev.filter(m => m.id !== meetingId))
    if (selectedMeetingId === meetingId) {
      const remaining = meetings.filter(m => m.id !== meetingId)
      setSelectedMeetingId(remaining[0]?.id ?? null)
    }
  }

  const downloadOriginalFile = (meetingId: number, fileId: number, fileName: string) => {
    const a = document.createElement('a')
    a.href = `/api/meetings/${meetingId}/files/${fileId}/download`
    a.download = fileName
    a.click()
  }

  // ── Renders ──────────────────────────────────────────────
  const renderFiles = (files: MeetingFile[], meetingId: number) => {
    if (files.length === 0) return (
      <div className="si-tri-empty">暂无上传文件</div>
    )
    return files.map(f => (
      <div key={f.id} className="history-meeting-file-card">
        <div className="history-meeting-file-header">
          <span className="history-meeting-file-name">{f.fileName}</span>
          {f.fileType && <span className="history-meeting-file-type">{f.fileType.toUpperCase()}</span>}
          {f.createTime && <span className="history-meeting-file-time">{f.createTime}</span>}
          <button
            className="history-file-toggle-btn"
            onClick={() => downloadOriginalFile(meetingId, f.id, f.fileName)}
            title="下载原始文件"
          >下载原文件</button>
        </div>
        {f.summary ? (
          <pre className="history-meeting-file-summary">{f.summary}</pre>
        ) : (
          <div className="history-meeting-file-nosummary">暂无 AI 总结（请在会前管理页生成）</div>
        )}
      </div>
    ))
  }

  const renderAttendance = (attendanceJson?: string | null) => {
    const { result: att } = parseSavedAttendance(attendanceJson)
    if (!att) return (
      <div className="history-attendance-empty">
        <div className="history-attendance-empty-icon">📋</div>
        <div>暂无参会情况</div>
        <div className="history-attendance-empty-hint">请在 Teams Bot 页选择关联会议并点击"刷新并生成实际参加情况"</div>
      </div>
    )
    const statusText = (s: string) => s === 'present' ? '已到' : s === 'absent' ? '未到' : s === 'unexpected' ? '未在安排中' : s
    const statusCls = (s: string) => s === 'present' ? 'tb-attendance-status--present' : s === 'absent' ? 'tb-attendance-status--absent' : s === 'unexpected' ? 'tb-attendance-status--unexpected' : ''
    const rows = att.rows ?? []
    return (
      <>
        {att.fileName && <div style={{ fontSize: '0.82rem', color: '#64748b', marginBottom: '0.5rem' }}>会议安排文件：{att.fileName}</div>}
        <div className="tb-attendance-stats">
          <div className="tb-attendance-stat"><span>应到</span><strong>{att.expectedCount}</strong></div>
          <div className="tb-attendance-stat"><span>Teams 实到</span><strong>{att.actualCount}</strong></div>
          <div className="tb-attendance-stat"><span>安排内实到</span><strong>{att.presentCount}</strong></div>
          <div className="tb-attendance-stat"><span>未到</span><strong>{att.absentCount}</strong></div>
          <div className="tb-attendance-stat"><span>未在安排中</span><strong>{att.unexpectedCount}</strong></div>
        </div>
        <div className="tb-attendance-table-wrap" style={{ marginTop: '0.75rem' }}>
          <table className="tb-attendance-table">
            <thead><tr><th>状态</th><th>安排姓名</th><th>分组</th><th>Teams 实到</th><th>邮箱</th></tr></thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i}>
                  <td><span className={`tb-attendance-status ${statusCls(row.status)}`}>{statusText(row.status)}</span></td>
                  <td>{row.status === 'unexpected' ? '-' : row.name || '-'}</td>
                  <td>{row.department || '-'}</td>
                  <td>{row.actualName || (row.status === 'unexpected' ? row.name : '-')}</td>
                  <td>{row.actualEmail || row.email || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    )
  }

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
        {/* ── 左侧：会议列表 ── */}
        <aside className="history-sidebar">
          <div className="history-list">
            {meetingsLoading && <div className="history-empty">加载中...</div>}
            {!meetingsLoading && meetings.length === 0 && (
              <div className="history-empty">暂无会议，请先在会前管理页上传会议安排</div>
            )}
            {meetings.map(m => (
              <div
                key={m.id}
                className={`history-item ${selectedMeetingId === m.id ? 'history-item--active' : ''}`}
              >
                <button
                  className="history-item-main"
                  onClick={() => setSelectedMeetingId(m.id)}
                  type="button"
                >
                  <span className="history-item-title">{m.title || DEFAULT_TITLE}</span>
                  {m.scheduledTime && (
                    <span className="history-item-meta">{m.scheduledTime}</span>
                  )}
                  <span className="history-item-meta">
                    {(m.files?.length ?? 0)} 个文件
                    {sessions.length > 0 && selectedMeetingId === m.id
                      ? ` · ${sessions.length} 场同传`
                      : ''}
                  </span>
                </button>
              </div>
            ))}
          </div>
        </aside>

        {/* ── 右侧：会议详情 ── */}
        <section className="history-detail">
          {!selectedMeeting ? (
            <div className="si-tri-empty" style={{ marginTop: '4rem' }}>请从左侧选择一场会议</div>
          ) : (
            <>
              <div className="history-detail-toolbar">
                <div>
                  <h2>{selectedMeeting.title || DEFAULT_TITLE}</h2>
                  {selectedMeeting.scheduledTime && <p>{selectedMeeting.scheduledTime}</p>}
                </div>
                <div className="history-detail-actions">
                  <button
                    className="history-danger"
                    onClick={() => { void deleteMeetingAndRefresh(selectedMeeting.id) }}
                  >删除会议</button>
                </div>
              </div>

              {/* 会话选择（若有多场同传） */}
              {sessions.length > 1 && (
                <div className="history-session-selector">
                  <label className="history-session-label">同传会话</label>
                  <select
                    value={selectedSessionId ?? ''}
                    onChange={e => {
                      setSelectedSessionId(e.target.value || null)
                      hasFetchedSummaryRef.current = false
                      setSummaryText(null)
                      setResults([])
                      setSpeakerRecords([])
                      setSelectedRecipients(new Set())
                      setSelectedSpeakerRecipients(new Set())
                      setSpeakerRegenStatus({})
                      setSpeakerPushStatus({})
                    }}
                  >
                    {sessions.map(s => (
                      <option key={s.sessionId} value={s.sessionId}>
                        {s.startTime ? new Date(s.startTime).toLocaleString() : s.sessionId}
                        {' '}· {s.resultCount ?? 0} 条文本
                      </option>
                    ))}
                  </select>
                  {selectedSessionId && (
                    <button
                      className="history-danger"
                      style={{ fontSize: '0.8rem', padding: '3px 8px' }}
                      onClick={() => { void deleteSessionAndRefresh(selectedSessionId) }}
                    >删除</button>
                  )}
                </div>
              )}
              {sessions.length === 1 && selectedSessionId && (
                <div className="history-session-selector">
                  <span className="history-session-label">同传会话</span>
                  <span style={{ fontSize: '0.85rem', color: '#475569' }}>
                    {sessions[0].startTime ? new Date(sessions[0].startTime).toLocaleString() : sessions[0].sessionId}
                    {' '}· {sessions[0].resultCount ?? 0} 条文本
                  </span>
                  <button
                    className="history-danger"
                    style={{ fontSize: '0.8rem', padding: '3px 8px' }}
                    onClick={() => { void deleteSessionAndRefresh(selectedSessionId) }}
                  >删除</button>
                </div>
              )}

              {/* Tab 导航 */}
              <div className="history-tab-nav">
                <button className={`history-tab-btn${activeTab === 'files' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('files')}>文件总结</button>
                <button className={`history-tab-btn${activeTab === 'attendance' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('attendance')}>
                  参会情况{selectedMeeting.attendanceJson ? ' ✓' : ''}
                </button>
                <button className={`history-tab-btn${activeTab === 'transcript' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('transcript')}>文本记录</button>
                <button className={`history-tab-btn${activeTab === 'speakers' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('speakers')}>发言摘要</button>
                <button className={`history-tab-btn${activeTab === 'summary' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('summary')}>会议总结</button>
              </div>

              {/* ── 文件总结 Tab ── */}
              {activeTab === 'files' && (
                <div className="history-tab-content">
                  {renderFiles(selectedMeeting.files ?? [], selectedMeeting.id)}
                </div>
              )}

              {/* ── 参会情况 Tab ── */}
              {activeTab === 'attendance' && (
                <div className="history-tab-content">
                  {renderAttendance(selectedMeeting.attendanceJson)}
                </div>
              )}

              {/* ── 文本记录 Tab ── */}
              {activeTab === 'transcript' && (
                <div className="history-tab-content">
                  {!selectedSessionId && <div className="si-tri-empty">该会议尚未进行同传，暂无文本记录</div>}
                  {selectedSessionId && resultsLoading && <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>}
                  {selectedSessionId && !resultsLoading && (
                    <>
                      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.5rem' }}>
                        <button
                          className="history-summary-export-btn"
                          onClick={() => downloadWord(selectedMeeting.title || '同传记录', results)}
                          disabled={results.length === 0}
                        >导出 Word</button>
                      </div>
                      <div className="si-tri-transcript-dock history-transcripts">
                        <div className="si-tri-transcript-dock-inner">
                          {results.length === 0 && <div className="si-tri-empty">暂无文本记录</div>}
                          {(() => {
                            type Group = { id: number; sourceText: string; speakerId?: string; speakerName?: string; translations: string[] }
                            const groups: Group[] = []
                            for (const item of results) {
                              const last = groups[groups.length - 1]
                              if (last && last.sourceText === item.sourceText && (last.speakerId === item.speakerId || !item.speakerId)) {
                                if (item.translatedText) last.translations.push(item.translatedText)
                              } else {
                                groups.push({ id: item.id, sourceText: item.sourceText || '', speakerId: item.speakerId, speakerName: item.speakerName, translations: item.translatedText ? [item.translatedText] : [] })
                              }
                            }
                            return groups.map(g => {
                              const speaker = g.speakerName || g.speakerId || null
                              return (
                                <div key={g.id} className="si-tri-block">
                                  <div className="si-tri-share-line">
                                    {speaker ? <span className="si-tri-line-lang-badge">{speaker}</span> : <span className="si-tri-line-lang-badge si-tri-line-lang-badge--unknown">?</span>}
                                    {g.sourceText}
                                  </div>
                                  {g.translations.map((t, ti) => (
                                    <div key={ti} className="si-tri-share-line si-tri-share-line--translated">{t}</div>
                                  ))}
                                </div>
                              )
                            })
                          })()}
                        </div>
                      </div>
                    </>
                  )}
                </div>
              )}

              {/* ── 发言摘要 Tab ── */}
              {activeTab === 'speakers' && (() => {
                const validParticipants = allKnownParticipants.filter(p => p.aadId)
                const chosenRecipients = validParticipants
                  .filter(p => selectedSpeakerRecipients.has(p.aadId))
                  .map(p => p.aadId)
                const allSelected = validParticipants.length > 0
                  && validParticipants.every(p => selectedSpeakerRecipients.has(p.aadId))
                const toggleAll = () => {
                  const next = allSelected ? new Set<string>() : new Set(validParticipants.map(p => p.aadId))
                  saveStoredRecipientSet(STORAGE_KEYS.SPEAKER_SUMMARY_RECIPIENTS, next)
                  setSelectedSpeakerRecipients(next)
                }
                return (
                  <div className="history-tab-content">
                    <div className="history-summary-requirements">
                      <div className="history-summary-requirements-header">
                        <label className="history-summary-requirements-label">发言摘要提示词（可选）</label>
                        <button
                          className="history-summary-requirements-default-btn"
                          onClick={saveSpeakerRequirementsDefault}
                          title="保存为默认提示词"
                        >
                          {speakerReqSaved ? '已保存' : '设为默认'}
                        </button>
                      </div>
                      <textarea
                        className="history-summary-requirements-input"
                        value={speakerRequirements}
                        onChange={e => setSpeakerRequirements(e.target.value)}
                        placeholder="例如：突出决策、风险和行动项；每条要点不超过 30 字；保留产品名和数字。"
                        rows={3}
                      />
                    </div>
                    {validParticipants.length === 0 ? (
                      <div className="history-summary-send-hint">
                        暂无可选账号。请先在 Teams Bot 页面刷新参会人员或生成参会情况。
                      </div>
                    ) : (
                      <div className="history-summary-send-panel">
                        <div className="history-summary-send-header">
                          <span className="history-summary-send-label">发言摘要发送账号</span>
                          <button className="history-summary-send-all-btn" onClick={toggleAll}>
                            {allSelected ? '取消全选' : '全选'}
                          </button>
                        </div>
                        <div className="history-summary-send-list">
                          {validParticipants.map(p => (
                            <label key={p.aadId} className="history-summary-send-item">
                              <input
                                type="checkbox"
                                checked={selectedSpeakerRecipients.has(p.aadId)}
                                onChange={() => toggleSpeakerRecipient(p.aadId)}
                              />
                              <span>{p.displayName || p.email || p.aadId}</span>
                            </label>
                          ))}
                        </div>
                      </div>
                    )}
                    {!selectedSessionId && <div className="si-tri-empty">该会议尚未进行同传，暂无发言摘要</div>}
                    {selectedSessionId && speakerLoading && <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>}
                    {selectedSessionId && !speakerLoading && speakerRecords.length === 0 && (
                      <div className="history-summary-empty">暂无发言人摘要（同传结束后自动生成）</div>
                    )}
                    {selectedSessionId && !speakerLoading && speakerRecords.map((rec, idx) => {
                      const statusKey = String(rec.id ?? `${rec.sessionId || 'speaker'}-${idx}`)
                      const regenStatus = speakerRegenStatus[statusKey] || 'idle'
                      const pushStatus = speakerPushStatus[statusKey] || 'idle'
                      return (
                        <div key={statusKey} className="history-speaker-record">
                          <div className="history-speaker-record-header">
                            <div className="history-speaker-record-title-group">
                              <span className="history-speaker-record-name">{rec.speakerName}</span>
                              {rec.title && <span className="history-speaker-record-title">· {rec.title}</span>}
                              {rec.createTime && (
                                <span className="history-speaker-record-time">{new Date(rec.createTime).toLocaleTimeString()}</span>
                              )}
                            </div>
                            <div className="history-speaker-record-actions">
                              <button
                                className="history-summary-regen-btn"
                                onClick={() => { void regenerateSpeakerRecord(rec, statusKey) }}
                                disabled={!rec.id || regenStatus === 'loading'}
                              >
                                {regenStatus === 'done' ? '已重新生成' : regenStatus === 'error' ? '生成失败' : regenStatus === 'loading' ? '生成中...' : '按提示词重新生成'}
                              </button>
                              <button
                                className="history-summary-export-btn"
                                onClick={() => { void pushSpeakerSummaryToTeams(rec, chosenRecipients, statusKey) }}
                                disabled={pushStatus === 'loading' || chosenRecipients.length === 0}
                              >
                                {pushStatus === 'done' ? '已发送 ✓' : pushStatus === 'error' ? '发送失败' : pushStatus === 'loading' ? '发送中...' : `发送到 Teams（${chosenRecipients.length} 人）`}
                              </button>
                            </div>
                          </div>
                          <textarea
                            className="history-speaker-record-edit"
                            value={rec.summary}
                            onChange={e => setSpeakerRecords(prev => prev.map((r, i) => i === idx ? { ...r, summary: e.target.value } : r))}
                            spellCheck={false}
                          />
                          <div className="history-summary-edit-hint">可手动修改后点「发送到 Teams」发送修改后的版本</div>
                        </div>
                      )
                    })}
                  </div>
                )
              })()}

              {/* ── 会议总结 Tab ── */}
              {activeTab === 'summary' && (() => {
                const displayed = summaryText
                const validP = allKnownParticipants.filter(p => p.aadId)
                const allSummarySelected = validP.length > 0 && validP.every(p => selectedRecipients.has(p.aadId))
                const toggleAllSummary = () => {
                  const next = allSummarySelected ? new Set<string>() : new Set(validP.map(p => p.aadId))
                  saveStoredRecipientSet(STORAGE_KEYS.MEETING_SUMMARY_RECIPIENTS, next)
                  setSelectedRecipients(next)
                }
                const chosenSummaryRecipients = validP.filter(p => selectedRecipients.has(p.aadId)).map(p => p.aadId)
                return (
                  <div className="history-summary-tab">
                    <div className="history-summary-requirements">
                      <div className="history-summary-requirements-header">
                        <label className="history-summary-requirements-label">会议总结提示词（可选）</label>
                        <button className="history-summary-requirements-default-btn"
                          onClick={saveSummaryRequirementsDefault}
                          title="保存为默认提示词">{summaryReqSaved ? '已保存' : '设为默认'}</button>
                      </div>
                      <textarea
                        className="history-summary-requirements-input"
                        value={summaryRequirements}
                        onChange={e => setSummaryRequirements(e.target.value)}
                        placeholder="例如：重点突出决议和待办事项，输出中英双语，按议题分段..."
                        rows={3}
                      />
                    </div>
                    <div className="history-summary-send-settings">
                      <label className="history-summary-chat-toggle">
                        <input
                          type="checkbox"
                          checked={includeMeetingChat}
                          onChange={e => {
                            setIncludeMeetingChat(e.target.checked)
                            localStorage.setItem(STORAGE_KEYS.MEETING_SUMMARY_INCLUDE_CHAT, String(e.target.checked))
                          }}
                        />
                        <span>发送到会议聊天</span>
                      </label>
                      {validP.length === 0 ? (
                        <div className="history-summary-send-hint">
                          暂无可选账号。请先在 Teams Bot 页面刷新参会人员或生成参会情况。
                        </div>
                      ) : (
                        <div className="history-summary-send-panel">
                          <div className="history-summary-send-header">
                            <span className="history-summary-send-label">会议总结发送账号</span>
                            <button className="history-summary-send-all-btn" onClick={toggleAllSummary}>
                              {allSummarySelected ? '取消全选' : '全选'}
                            </button>
                          </div>
                          <div className="history-summary-send-list">
                            {validP.map(p => (
                              <label key={p.aadId} className="history-summary-send-item">
                                <input
                                  type="checkbox"
                                  checked={selectedRecipients.has(p.aadId)}
                                  onChange={() => toggleSummaryRecipient(p.aadId)}
                                />
                                <span>{p.displayName || p.email || p.aadId}</span>
                              </label>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                    {!selectedSessionId && <div className="si-tri-empty">该会议尚未进行同传，暂无会议总结</div>}
                    {selectedSessionId && (summaryLoading || displayed === null) && (
                      <div className="history-summary-loading"><span className="history-summary-spinner" />会议总结生成中...</div>
                    )}
                    {selectedSessionId && !summaryLoading && displayed !== null && displayed === '' && (
                      <div className="history-summary-empty">该会话没有可用的文字记录，无法生成总结</div>
                    )}
                    {selectedSessionId && !summaryLoading && (displayed === null || displayed === '') && (
                      <div className="history-summary-regen-row">
                        <button className="history-summary-regen-btn" onClick={refetchSummary}>生成总结</button>
                      </div>
                    )}
                    {selectedSessionId && !summaryLoading && displayed !== null && displayed !== '' && (
                      <>
                        <div className="history-summary-toolbar">
                          <div className="history-summary-toolbar-actions">
                            <button className="history-summary-export-btn"
                              onClick={() => exportSummaryWord(selectedMeeting.title || '同传记录', displayed)}
                            >导出 Word</button>
                            <button className="history-summary-export-btn"
                              onClick={() => exportSummaryPdf(selectedMeeting.title || '同传记录', displayed)}
                            >导出 PDF</button>
                          </div>
                          {includeMeetingChat && !!localStorage.getItem(TEAMS_BOT_STORAGE_KEYS.MEETING_THREAD_ID) && (
                            <button
                              className="history-summary-export-btn"
                              onClick={() => { void pushSummaryToChat(displayed) }}
                              disabled={chatPushStatus === 'loading'}
                            >
                              {chatPushStatus === 'done' ? '已发送到聊天 ✓' : chatPushStatus === 'error' ? '发送失败' : chatPushStatus === 'loading' ? '发送中...' : '发送到会议聊天'}
                            </button>
                          )}
                          {chosenSummaryRecipients.length > 0 && (
                            <button
                              className="history-summary-export-btn"
                              onClick={() => { void pushSummaryToTeams(displayed, chosenSummaryRecipients) }}
                              disabled={teamsPushStatus === 'loading'}
                            >
                              {teamsPushStatus === 'done' ? '已发送 ✓' : teamsPushStatus === 'error' ? '发送失败' : teamsPushStatus === 'loading' ? '发送中...' : `发送到 Teams（${chosenSummaryRecipients.length} 人）`}
                            </button>
                          )}
                          <button className="history-summary-regen-btn" onClick={refetchSummary}>重新生成</button>
                        </div>
                        <div className="history-summary-body">
                          <div className="history-summary-edit-hint">可手动修改下方内容，再点上方「发送」按钮发送修改后的版本</div>
                          <textarea
                            className="history-summary-edit"
                            value={displayed}
                            onChange={e => setSummaryText(e.target.value)}
                            spellCheck={false}
                          />
                        </div>
                      </>
                    )}

                    {/* ── 行动项 ── */}
                    {selectedSessionId && (
                      <div className="history-action-items">
                        <div className="history-action-items-header">
                          <span className="history-action-items-title">行动项</span>
                          <button
                            className="history-action-items-extract-btn"
                            onClick={() => { void handleExtractActionItems() }}
                            disabled={extractingActionItems}
                          >
                            {extractingActionItems ? '提取中...' : actionItems.length > 0 ? '重新提取' : 'AI 提取行动项'}
                          </button>
                        </div>
                        {actionItemsLoading && (
                          <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>
                        )}
                        {!actionItemsLoading && actionItems.length === 0 && (
                          <div className="history-action-items-empty">暂无行动项，点击"AI 提取行动项"从会议记录中提取</div>
                        )}
                        {!actionItemsLoading && actionItems.length > 0 && (
                          <ul className="history-action-items-list">
                            {actionItems.map(item => (
                              <li key={item.id} className={`history-action-item${item.status === 'done' ? ' done' : ''}`}>
                                <button
                                  className="history-action-item-check"
                                  onClick={() => { void handleToggleActionItem(item) }}
                                  title={item.status === 'done' ? '标记为未完成' : '标记为完成'}
                                >
                                  {item.status === 'done' ? '✓' : '○'}
                                </button>
                                <div className="history-action-item-body">
                                  {item.assignee && <span className="history-action-item-assignee">【{item.assignee}】</span>}
                                  <span className="history-action-item-content">{item.content}</span>
                                  {item.deadline && <span className="history-action-item-deadline">截止：{item.deadline}</span>}
                                </div>
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    )}
                  </div>
                )
              })()}
            </>
          )}
        </section>
      </main>
    </div>
  )
}
