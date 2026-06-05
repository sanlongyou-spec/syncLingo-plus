import { useCallback, useEffect, useRef, useState } from 'react'
import {
  addMeetingHotwords,
  createMeeting,
  deleteMeetingFile,
  exportPreMeetingAttendanceDocx,
  generateAttendanceFromMeeting,
  generatePreMeetingAttendance,
  getMeetingParticipants,
  saveExpectedParticipants,
  getMeetings,
  joinMeeting,
  loadMeetingFileForSummary,
  saveMeetingAttendance,
  saveMeetingFileSummary,
  summarizePreMeetingFile,
  updateInterpretationSessionTitle,
  uploadFileToMeeting,
  uploadPreMeetingFile,
} from '../api'
import { ROUTES, STORAGE_KEYS, TEAMS_BOT_STORAGE_KEYS } from '../constants'
import type {
  Meeting,
  MeetingFile,
  MeetingParticipant,
  PreMeetingAttendanceResult,
  PreMeetingFile,
  PreMeetingSummaryResult,
} from '../types'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './TeamsBotView.css'

// ── 导出工具 ─────────────────────────────────────────────
const sanitizeFilename = (s: string) =>
  s.replace(/[\\/:*?"<>|]/g, '_').trim() || 'report-summary'

const downloadBlob = (blob: Blob, filename: string) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

const persistKnownParticipants = (incoming: MeetingParticipant[], userId?: number) => {
  const validParticipants = incoming.filter(p => p.aadId)
  if (validParticipants.length === 0) return
  // Add participant display names to the ASR hotword list (fire-and-forget, deduped server-side).
  if (userId != null) {
    const names = incoming
      .map(p => p.displayName?.trim())
      .filter((name): name is string => !!name)
    if (names.length > 0) {
      addMeetingHotwords(userId, names).catch(error =>
        console.warn('[TeamsBotView] add meeting hotwords failed:', error))
    }
  }
  try {
    const saved = localStorage.getItem(TEAMS_BOT_STORAGE_KEYS.ALL_KNOWN_PARTICIPANTS)
    const existing = saved ? JSON.parse(saved) as MeetingParticipant[] : []
    const byAadId = new Map<string, MeetingParticipant>()
    ;[...existing, ...validParticipants].forEach(participant => {
      if (!participant.aadId) return
      const previous = byAadId.get(participant.aadId)
      byAadId.set(participant.aadId, {
        aadId: participant.aadId,
        displayName: participant.displayName || previous?.displayName || null,
        email: participant.email || previous?.email || null,
      })
    })
    localStorage.setItem(
      TEAMS_BOT_STORAGE_KEYS.ALL_KNOWN_PARTICIPANTS,
      JSON.stringify(Array.from(byAadId.values())),
    )
  } catch (error) {
    console.warn('[TeamsBotView] persist known participants failed:', error)
  }
}

const attendanceStatusText = (status: string) => {
  if (status === 'present') return '已到'
  if (status === 'absent') return '未到'
  if (status === 'unexpected') return '未在安排中'
  return status || '-'
}

const attendanceStatusClass = (status: string) => {
  if (status === 'present') return 'tb-attendance-status--present'
  if (status === 'absent') return 'tb-attendance-status--absent'
  if (status === 'unexpected') return 'tb-attendance-status--unexpected'
  return ''
}

export default function TeamsBotView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')

  // ── 全局状态 ─────────────────────────────────────────
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  // ── Section 1: 会议安排 ──────────────────────────────
  // scheduleFile only exists for the meeting whose 会议安排 was just uploaded this session; it is
  // cleared when switching meetings (a name-only meeting has no 应到 list — refresh shows 实到 instead).
  const [scheduleFile, setScheduleFile] = useState<PreMeetingFile | null>(null)
  const [scheduleLoading, setScheduleLoading] = useState(false)
  const [meetingName, setMeetingName] = useState('')          // auto-populated then editable
  // Whether the currently selected meeting has a saved 应到 list (from a previously uploaded 会议安排).
  const [meetingHasExpected, setMeetingHasExpected] = useState(false)
  const scheduleInputRef = useRef<HTMLInputElement>(null)

  // ── Section 2: 会议文件 + AI 总结 ────────────────────
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)
  const [meetingFiles, setMeetingFiles] = useState<MeetingFile[]>([])
  const [loadingMeetingFileId, setLoadingMeetingFileId] = useState<number | null>(null)
  const [prepFiles, setPrepFiles] = useState<PreMeetingFile[]>([])
  const [selectedFileId, setSelectedFileId] = useState('')
  const [summaryMap, setSummaryMap] = useState<Record<string, PreMeetingSummaryResult>>({})
  const [loadingMap, setLoadingMap] = useState<Record<string, boolean>>({})
  const [fileUploadLoading, setFileUploadLoading] = useState(false)
  const [exportLoading, setExportLoading] = useState(false)
  const [pdfLoading, setPdfLoading] = useState(false)
  // 导出格式：0/'' = 继承原文。正文字体/字号 + 标题字号，标题与正文可不同。
  const [summaryFmt, setSummaryFmt] = useState<{ bodyFont: string; bodySize: number; headingSize: number }>(
    { bodyFont: '', bodySize: 0, headingSize: 0 })
  const [preMeetingToDbFileId, setPreMeetingToDbFileId] = useState<Record<string, number>>({})
  const [defaultReqDraft, setDefaultReqDraft] = useState(
    () => localStorage.getItem(TEAMS_BOT_STORAGE_KEYS.PRE_MEETING_DEFAULT_REQ) ?? ''
  )
  const [defaultReqSaved, setDefaultReqSaved] = useState(false)
  const [extraReq, setExtraReq] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  // ── Section 3: Teams Bot ─────────────────────────────
  const [meetingUrl, setMeetingUrl] = useState('')
  const [joinStatus, setJoinStatus] = useState<'idle' | 'joining' | 'joined' | 'error'>('idle')
  const [activeCallId, setActiveCallId] = useState<string | null>(null)
  const [meetingTitle, setMeetingTitle] = useState<string | null>(null)
  const [participants, setParticipants] = useState<MeetingParticipant[]>([])
  const [participantsLoading, setParticipantsLoading] = useState(false)
  const [attendanceLoading, setAttendanceLoading] = useState(false)
  const [attendanceExportLoading, setAttendanceExportLoading] = useState(false)
  // Attendance is NOT auto-restored on entry — it only appears after the user clicks 「刷新」.
  const [attendanceResult, setAttendanceResult] = useState<PreMeetingAttendanceResult | null>(null)

  // ── 加载会议列表，并恢复上次选中的会议 ──────────────────
  useEffect(() => {
    getMeetings(userId).then(res => {
      const list = res.data || []
      setMeetings(list)
      const saved = localStorage.getItem(TEAMS_BOT_STORAGE_KEYS.LAST_SELECTED_MEETING_ID)
      if (saved) {
        const id = Number(saved)
        if (list.some(m => m.id === id)) {
          const m = list.find(x => x.id === id)!
          setSelectedMeetingId(id)
          setMeetingFiles(m.files || [])
          setMeetingName(m.title || '')
          setMeetingHasExpected(!!m.hasExpectedParticipants)
        }
      }
    }).catch(() => {})
  }, [userId])


  const handleMeetingSelect = (id: number | null) => {
    setSelectedMeetingId(id)
    if (id) localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.LAST_SELECTED_MEETING_ID, String(id))
    else localStorage.removeItem(TEAMS_BOT_STORAGE_KEYS.LAST_SELECTED_MEETING_ID)
    const m = id ? meetings.find(x => x.id === id) : null
    setMeetingFiles(m?.files || [])
    setMeetingName(m?.title || '')
    setMeetingHasExpected(!!m?.hasExpectedParticipants)
    // A previously-selected meeting has no in-memory 会议安排; clear it. The attendance comparison
    // still works if the meeting has a saved 应到 list (meetingHasExpected); otherwise refresh
    // just shows the Teams 实到 list.
    setScheduleFile(null)
    // Reset AI-summary working state + attendance/participants so nothing from the previous
    // (or no) meeting lingers after switching or deselecting.
    setPrepFiles([])
    setSummaryMap({})
    setSelectedFileId('')
    setPreMeetingToDbFileId({})
    setParticipants([])
    setAttendanceResult(null)
  }

  // 仅用会议名新建会议（无会议安排文件）——此时没有应到名单，刷新直接给 Teams 实到。
  const handleCreateMeetingByName = async () => {
    const title = meetingName.trim()
    if (!title) { setError('请先输入会议名称'); return }
    setError('')
    try {
      const res = await createMeeting({ userId, title })
      const m = res.data
      setMeetings(prev => [m, ...prev.filter(x => x.id !== m.id)])
      setSelectedMeetingId(m.id)
      localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.LAST_SELECTED_MEETING_ID, String(m.id))
      setMeetingFiles([])
      setScheduleFile(null)
      setMeetingHasExpected(false)
      setMeetingName(title)
      setPrepFiles([]); setSummaryMap({}); setSelectedFileId(''); setPreMeetingToDbFileId({})
      setParticipants([]); setAttendanceResult(null)
      setSuccess('会议已创建')
    } catch (e) {
      setError(e instanceof Error ? e.message : '新建会议失败')
    }
  }

  // ── Section 1: 上传会议安排 ───────────────────────────
  const handleScheduleUpload = async (file: File) => {
    setScheduleLoading(true)
    setError('')
    try {
      const res = await uploadPreMeetingFile(file, userId)
      const added = res.data || []
      if (added.length === 0) throw new Error('未解析到可用的会议安排文件')
      const first = added[0]
      setScheduleFile(first)
      const title = first.meetingTitle || file.name.replace(/\.[^.]+$/, '')
      setMeetingName(title)
      // auto-create meeting in DB so it appears in InterpretationView
      const mRes = await createMeeting({ userId, title })
      const newMeeting = mRes.data
      setSelectedMeetingId(newMeeting.id)
      localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.LAST_SELECTED_MEETING_ID, String(newMeeting.id))
      setMeetingFiles([])
      // fresh meeting — clear any prior AI/attendance working state
      setPrepFiles([]); setSummaryMap({}); setSelectedFileId(''); setPreMeetingToDbFileId({})
      setParticipants([]); setAttendanceResult(null)
      // Persist the 应到 list onto the meeting so the comparison survives across sessions.
      let savedExpected = false
      try {
        await saveExpectedParticipants(first.fileId, newMeeting.id)
        savedExpected = true
      } catch (err) {
        console.warn('[TeamsBotView] saveExpectedParticipants failed:', err)
      }
      setMeetingHasExpected(savedExpected)
      setMeetings(prev => [{ ...newMeeting, hasExpectedParticipants: savedExpected },
        ...prev.filter(m => m.id !== newMeeting.id)])
      setSuccess(savedExpected ? '会议已创建，会议安排与应到名单已保存' : '会议已创建，会议安排已上传')
    } catch (e) {
      setError(e instanceof Error ? e.message : '上传会议安排失败')
    } finally {
      setScheduleLoading(false)
    }
  }

  // ── Section 2: 上传会议文件 ───────────────────────────
  const handleFileUpload = async (file: File) => {
    setFileUploadLoading(true)
    setError('')
    try {
      // auto-create meeting if name is set and none selected
      let meetingId = selectedMeetingId
      if (!meetingId && meetingName.trim()) {
        const res = await createMeeting({ userId, title: meetingName.trim() })
        const m = res.data
        setMeetings(prev => [m, ...prev])
        setSelectedMeetingId(m.id)
        setMeetingFiles([])
        meetingId = m.id
      }
      let dbFileId: number | null = null
      if (meetingId) {
        const res = await uploadFileToMeeting(meetingId, file)
        setMeetingFiles(prev => [...prev, res.data])
        dbFileId = res.data.id
      }
      // also store in prepFiles for AI summary
      const res2 = await uploadPreMeetingFile(file, userId)
      const added = res2.data || []
      setPrepFiles(prev => [...prev, ...added])
      if (added.length > 0 && !selectedFileId) setSelectedFileId(added[0].fileId)
      if (dbFileId !== null && added.length > 0) {
        setPreMeetingToDbFileId(prev => ({ ...prev, [added[0].fileId]: dbFileId! }))
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '上传失败')
    } finally {
      setFileUploadLoading(false)
    }
  }

  const handleDeleteMeetingFile = async (fileId: number) => {
    if (!selectedMeetingId) return
    try {
      await deleteMeetingFile(selectedMeetingId, fileId)
      setMeetingFiles(prev => prev.filter(f => f.id !== fileId))
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除文件失败')
    }
  }

  // Re-select a previously uploaded file (persisted on the meeting) so it can be re-summarized.
  const handleSelectMeetingFile = async (dbFileId: number) => {
    if (!selectedMeetingId) return
    // Already loaded this session? Just re-select it (don't re-load).
    const existing = Object.entries(preMeetingToDbFileId).find(([, v]) => v === dbFileId)?.[0]
    if (existing) { setSelectedFileId(existing); return }
    setLoadingMeetingFileId(dbFileId)
    setError('')
    try {
      const res = await loadMeetingFileForSummary(selectedMeetingId, dbFileId)
      const data = res.data
      setPrepFiles(prev => prev.some(f => f.fileId === data.fileId)
        ? prev
        : [...prev, { fileId: data.fileId, fileName: data.fileName }])
      setPreMeetingToDbFileId(prev => ({ ...prev, [data.fileId]: dbFileId }))
      if (data.summary) {
        setSummaryMap(prev => ({ ...prev, [data.fileId]: data }))
      }
      setSelectedFileId(data.fileId)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载文件失败')
    } finally {
      setLoadingMeetingFileId(null)
    }
  }

  const removeFile = (fileId: string) => {
    setPrepFiles(prev => prev.filter(f => f.fileId !== fileId))
    setSummaryMap(prev => { const next = { ...prev }; delete next[fileId]; return next })
    if (selectedFileId === fileId) setSelectedFileId('')
  }

  const handleSummarize = async () => {
    if (!selectedFileId || loadingMap[selectedFileId]) return
    const fileId = selectedFileId
    setLoadingMap(prev => ({ ...prev, [fileId]: true }))
    setError('')
    const combined = [defaultReqDraft.trim(), extraReq.trim()].filter(Boolean).join('\n')
    try {
      const res = await summarizePreMeetingFile(fileId, combined, userId, selectedMeetingId)
      setSummaryMap(prev => ({ ...prev, [fileId]: res.data }))
      const dbFileId = preMeetingToDbFileId[fileId]
      const mid = selectedMeetingId
      if (dbFileId && mid) {
        void saveMeetingFileSummary(mid, dbFileId, res.data.summary).catch(() => {})
        setMeetingFiles(prev => prev.map(f => f.id === dbFileId ? { ...f, summary: res.data.summary } : f))
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '生成总结失败')
    } finally {
      setLoadingMap(prev => ({ ...prev, [fileId]: false }))
    }
  }

  const saveDefaultReq = () => {
    localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.PRE_MEETING_DEFAULT_REQ, defaultReqDraft)
    setDefaultReqSaved(true)
    setTimeout(() => setDefaultReqSaved(false), 1500)
  }

  const selectedFile = prepFiles.find(f => f.fileId === selectedFileId)
  const summaryResult = selectedFileId ? (summaryMap[selectedFileId] ?? null) : null
  // Attendance comparison is possible if a 会议安排 was just uploaded, or the meeting has a saved 应到 list.
  const canCompareAttendance = !!scheduleFile || meetingHasExpected

  // ── Section 3: Teams Bot ─────────────────────────────
  async function syncCurrentSessionTitle(title: string) {
    const currentSessionId = localStorage.getItem(STORAGE_KEYS.CURRENT_SESSION_ID)
    if (!currentSessionId || !title.trim()) return
    try {
      await updateInterpretationSessionTitle(currentSessionId, userId, title.trim())
    } catch { /* silent */ }
  }

  const fetchParticipants = useCallback(async () => {
    setParticipantsLoading(true)
    try {
      const data = await getMeetingParticipants()
      setParticipants(data.participants)
      persistKnownParticipants(data.participants, userId)
      if (data.callId) setActiveCallId(data.callId)
      if (data.threadId) {
        localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.MEETING_THREAD_ID, data.threadId)
        localStorage.setItem(STORAGE_KEYS.MEETING_SUMMARY_INCLUDE_CHAT, 'true')
      }
      if (data.meetingTitle) {
        setMeetingTitle(data.meetingTitle)
        await syncCurrentSessionTitle(data.meetingTitle)
      }
    } catch (e) {
      // Background/auto fetch (on mount and after join) — stay silent; the manual
      // 「刷新」(handleRefreshParticipants) surfaces errors instead.
      console.warn('[TeamsBotView] auto fetchParticipants failed:', e)
    } finally {
      setParticipantsLoading(false)
    }
  }, [])

  // No auto-fetch on entry — participants/attendance load only when the user clicks 「刷新」
  // (or shortly after the bot joins a meeting, below).

  const handleJoinMeeting = async () => {
    const url = meetingUrl.trim()
    if (!url) { setError('请粘贴 Teams 会议链接'); return }
    setJoinStatus('joining')
    setError('')
    try {
      const data = await joinMeeting(url)
      setActiveCallId(data.callId)
      if (data.threadId) {
        localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.MEETING_THREAD_ID, data.threadId)
        localStorage.setItem(STORAGE_KEYS.MEETING_SUMMARY_INCLUDE_CHAT, 'true')
      }
      if (data.meetingTitle) {
        setMeetingTitle(data.meetingTitle)
        await syncCurrentSessionTitle(data.meetingTitle)
      }
      setJoinStatus('joined')
      setSuccess('机器人已加入会议，正在获取参会人员…')
      setMeetingUrl('')
      setTimeout(fetchParticipants, 3000)
    } catch (e) {
      setJoinStatus('error')
      setError(e instanceof Error ? e.message : '加入会议失败')
    }
  }

  // Merged action: always refresh the Teams participant list; if a meeting-schedule file is
  // present, also generate (and save, when a meeting is selected) the actual-attendance check.
  const handleRefreshParticipants = async () => {
    // Comparison is possible from the just-uploaded file OR from the meeting's saved 应到 list.
    const canCompare = !!scheduleFile || meetingHasExpected
    setParticipantsLoading(true)
    if (canCompare) setAttendanceLoading(true)
    setError('')
    setSuccess('')
    try {
      const data = await getMeetingParticipants()
      setParticipants(data.participants)
      persistKnownParticipants(data.participants, userId)
      if (data.callId) setActiveCallId(data.callId)
      if (data.threadId) {
        localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.MEETING_THREAD_ID, data.threadId)
        localStorage.setItem(STORAGE_KEYS.MEETING_SUMMARY_INCLUDE_CHAT, 'true')
      }
      if (data.meetingTitle) {
        setMeetingTitle(data.meetingTitle)
        await syncCurrentSessionTitle(data.meetingTitle)
      }

      if (!canCompare) {
        setSuccess('已刷新参会人员')
        return
      }
      // Prefer the freshly-uploaded 会议安排; otherwise use the 应到 list saved on the meeting.
      const res = scheduleFile
        ? await generatePreMeetingAttendance(scheduleFile.fileId, data.participants)
        : await generateAttendanceFromMeeting(selectedMeetingId!, data.participants)
      setAttendanceResult(res.data)
      if (selectedMeetingId) {
        void saveMeetingAttendance(
          selectedMeetingId,
          JSON.stringify({ result: res.data, participants: data.participants }),
        ).then(() => {
          setMeetings(prev => prev.map(m =>
            m.id === selectedMeetingId
              ? { ...m, attendanceJson: JSON.stringify({ result: res.data, participants: data.participants }) }
              : m
          ))
        }).catch(err => {
          console.warn('[TeamsBotView] attendance save failed:', err)
          setError('参会情况保存到历史记录失败，请重启后端服务后重试')
        })
        setSuccess('已刷新参会人员并核对实际参加情况')
      } else {
        setSuccess('已刷新并核对（未选关联会议，结果未保存到历史记录）')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新参会人员失败')
    } finally {
      setParticipantsLoading(false)
      setAttendanceLoading(false)
    }
  }

  const handleExportAttendance = async () => {
    if (!scheduleFile || !attendanceResult) { setError('请先生成实际参加情况'); return }
    setAttendanceExportLoading(true)
    setError('')
    try {
      const blob = await exportPreMeetingAttendanceDocx(scheduleFile.fileId, participants)
      const title = attendanceResult.meetingTitle || attendanceResult.fileName || '实际参会名单'
      downloadBlob(blob, `${sanitizeFilename(title)}_实际参会名单.docx`)
      setSuccess('实际参会名单 Word 已导出')
    } catch (e) {
      setError(e instanceof Error ? e.message : '导出失败')
    } finally {
      setAttendanceExportLoading(false)
    }
  }

  const botStatus = activeCallId
    ? '机器人已在会议中'
    : joinStatus === 'joined' ? '机器人已加入' : '机器人未在会议中'

  return (
    <div className="tb-root tb-root--single">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">Teams Bot</h1>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="tb-single-main">
        <ErrorBanner message={error} onDismiss={() => setError('')} />
        {success && <SuccessBanner message={success} />}

        {/* ── Section 1: 会议（AI 总结 / Teams Bot 共用同一个会议选择） ── */}
        <section className="tb-card">
          <h2 className="tb-card-title">会议</h2>
          <input
            ref={scheduleInputRef}
            type="file"
            accept=".doc,.docx,.pdf"
            style={{ display: 'none' }}
            onChange={e => {
              const f = e.target.files?.[0]
              e.target.value = ''
              if (f) void handleScheduleUpload(f)
            }}
          />

          {/* 唯一的会议选择器 —— AI 总结与 Teams Bot 都用它 */}
          <div className="tb-meeting-selector-row">
            <label className="tb-prep-label">所属会议</label>
            <select
              className="tb-meeting-select"
              value={selectedMeetingId ?? ''}
              onChange={e => handleMeetingSelect(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">— 请选择会议 —</option>
              {meetings.map(m => (
                <option key={m.id} value={m.id}>
                  {m.title}{m.scheduledTime ? ` · ${m.scheduledTime}` : ''}
                </option>
              ))}
            </select>
          </div>

          {/* 新建会议：上传会议安排（带应到名单）或仅输入会议名（刷新直接给 Teams 实到） */}
          <div className="tb-new-meeting">
            <span className="tb-new-meeting-label">新建会议</span>
            <div className="tb-schedule-upload-row">
              <button
                className={`tb-btn tb-btn--secondary ${scheduleLoading ? 'tb-btn--loading' : ''}`}
                disabled={scheduleLoading}
                onClick={() => scheduleInputRef.current?.click()}
              >
                {scheduleLoading ? '解析中…' : scheduleFile ? '重新上传会议安排' : '上传会议安排'}
              </button>
              {scheduleFile && (
                <span className="tb-schedule-file-name">{scheduleFile.fileName}</span>
              )}
            </div>
            <div className="tb-meeting-name-row">
              <input
                className="tb-meeting-input"
                placeholder="或直接输入会议名称新建（无会议安排，刷新只给 Teams 实到）"
                value={meetingName}
                onChange={e => setMeetingName(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && void handleCreateMeetingByName()}
              />
              <button
                className="tb-btn tb-btn--primary"
                disabled={!meetingName.trim()}
                onClick={() => void handleCreateMeetingByName()}
              >
                新建会议
              </button>
            </div>
          </div>
        </section>

        {/* ── Section 2: 会议文件 + AI 总结 ── */}
        <section className="tb-card tb-files-section">
          <h2 className="tb-card-title">AI 总结</h2>

          {!selectedMeetingId && (
            <p className="tb-empty">请先在上方「会议」中选择或新建会议</p>
          )}

          {selectedMeetingId && (<>
          {/* 已关联文件 */}
          {meetingFiles.length > 0 && (
            <div className="tb-meeting-files">
              <span className="tb-meeting-files-label">已上传文件</span>
              {meetingFiles.map(f => {
                const isActive = preMeetingToDbFileId[selectedFileId] === f.id
                const isLoading = loadingMeetingFileId === f.id
                return (
                  <div key={f.id} className={`tb-meeting-file-row tb-meeting-file-row--clickable${isActive ? ' tb-meeting-file-row--active' : ''}`}>
                    <button
                      type="button"
                      className="tb-meeting-file-select"
                      disabled={isLoading}
                      onClick={() => void handleSelectMeetingFile(f.id)}
                      title="点击载入该文件，可重新生成 AI 总结"
                    >
                      <span className="tb-meeting-file-name">{f.fileName}</span>
                      {isLoading && <span className="tb-meeting-file-badge">载入中…</span>}
                      {!isLoading && f.summary && <span className="tb-meeting-file-badge">已总结</span>}
                    </button>
                    <button className="tb-meeting-file-del" onClick={() => handleDeleteMeetingFile(f.id)} title="删除">✕</button>
                  </div>
                )
              })}
            </div>
          )}

          <div className="tb-files-body">
            {/* 左: 上传 + 文件列表 */}
            <div className="tb-files-left">
              {/* 默认要求 */}
              <div className="tb-default-req">
                <div className="tb-default-req-header">
                  <span className="tb-prep-label">默认总结要求</span>
                  <button
                    className={`tb-btn tb-btn--sm ${defaultReqSaved ? 'tb-btn--secondary' : 'tb-btn--primary'}`}
                    onClick={saveDefaultReq}
                  >
                    {defaultReqSaved ? '已保存 ✓' : '保存'}
                  </button>
                </div>
                <textarea
                  className="tb-prep-textarea"
                  rows={4}
                  placeholder="每次生成 AI 总结时自动带入，例如：输出中文，重点提取数据与结论…"
                  value={defaultReqDraft}
                  onChange={e => { setDefaultReqDraft(e.target.value); setDefaultReqSaved(false) }}
                />
              </div>

              {/* 上传区 */}
              <div
                className={`tb-upload-zone${fileUploadLoading ? ' tb-upload-zone--loading' : ''}`}
                onDragOver={e => e.preventDefault()}
                onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) void handleFileUpload(f) }}
                onClick={() => !fileUploadLoading && fileInputRef.current?.click()}
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".doc,.docx,.pdf,.zip"
                  style={{ display: 'none' }}
                  onChange={e => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void handleFileUpload(f) }}
                />
                {fileUploadLoading ? (
                  <span className="tb-upload-spinner" />
                ) : (
                  <>
                    <span className="tb-upload-icon">📄</span>
                    <span className="tb-upload-hint">点击或拖拽上传文件</span>
                    <span className="tb-upload-sub">支持 Word、PDF、ZIP</span>
                  </>
                )}
              </div>

              {/* 文件列表 */}
              {prepFiles.length > 0 && (
                <div className="tb-file-list">
                  <div className="tb-file-list-label">已上传（{prepFiles.length}）</div>
                  {prepFiles.map(f => (
                    <div key={f.fileId} className="tb-file-item-wrap">
                      <button
                        className={`tb-file-item${selectedFileId === f.fileId ? ' tb-file-item--active' : ''}`}
                        onClick={() => setSelectedFileId(f.fileId)}
                      >
                        <span className="tb-file-icon">{f.fileName.endsWith('.pdf') ? '📕' : '📘'}</span>
                        <span className="tb-file-name">{f.fileName}</span>
                        {loadingMap[f.fileId] && <span className="tb-file-spinner" />}
                        {!loadingMap[f.fileId] && summaryMap[f.fileId] && <span className="tb-file-done">✓</span>}
                      </button>
                      <button className="tb-file-remove" onClick={() => removeFile(f.fileId)} title="移除">×</button>
                    </div>
                  ))}
                </div>
              )}

              {/* 额外要求 + 生成按钮 */}
              {selectedFileId && (
                <>
                  <div className="tb-prep-field">
                    <label className="tb-prep-label">本次额外要求（可选）</label>
                    <textarea
                      className="tb-prep-textarea"
                      rows={4}
                      placeholder="在默认要求基础上追加…"
                      value={extraReq}
                      onChange={e => setExtraReq(e.target.value)}
                    />
                  </div>
                  <button
                    className="tb-btn tb-btn--primary tb-prep-submit"
                    onClick={handleSummarize}
                    disabled={!!loadingMap[selectedFileId]}
                  >
                    {loadingMap[selectedFileId] ? '生成中…' : summaryMap[selectedFileId] ? '重新生成' : '生成 AI 总结'}
                  </button>
                </>
              )}
            </div>

            {/* 右: 总结显示 */}
            <div className="tb-files-right">
              {!summaryResult && !loadingMap[selectedFileId] && (
                <div className="tb-prep-placeholder">
                  {selectedFile
                    ? `已选「${selectedFile.fileName}」，点击"生成 AI 总结"开始分析`
                    : '上传文件后点击选择，生成 AI 总结'}
                </div>
              )}
              {loadingMap[selectedFileId] && !summaryResult && (
                <div className="tb-prep-placeholder">
                  <span className="tb-upload-spinner" />
                  <span style={{ marginLeft: 10 }}>正在分析，请稍候…</span>
                </div>
              )}
              {summaryResult && (
                <>
                  <div className="tb-prep-result-header">
                    <div>
                      <div className="tb-prep-result-title">{summaryResult.fileName}</div>
                      <div className="tb-prep-result-sub">AI 总结{loadingMap[selectedFileId] ? ' · 重新生成中…' : ''}</div>
                    </div>
                    <div className="tb-prep-export-row">
                      <button
                        className="tb-btn tb-btn--secondary tb-btn--sm"
                        disabled={exportLoading}
                        onClick={async () => {
                          if (!selectedFileId) return
                          setExportLoading(true)
                          setError('')
                          try {
                            const { exportPreMeetingDocx } = await import('../api')
                            const blob = await exportPreMeetingDocx(selectedFileId, summaryResult.summary, summaryFmt)
                            downloadBlob(blob, sanitizeFilename(summaryResult.fileName))
                          } catch (err) {
                            setError(err instanceof Error ? err.message : '导出失败')
                          } finally {
                            setExportLoading(false)
                          }
                        }}
                      >{exportLoading ? '导出中…' : '导出 Word'}</button>
                      <button
                        className="tb-btn tb-btn--secondary tb-btn--sm"
                        disabled={pdfLoading}
                        onClick={async () => {
                          if (!selectedFileId) return
                          setPdfLoading(true)
                          setError('')
                          try {
                            const { exportPreMeetingPdf } = await import('../api')
                            const blob = await exportPreMeetingPdf(selectedFileId, summaryResult.summary, summaryFmt)
                            const pdfName = sanitizeFilename(summaryResult.fileName).replace(/\.[^.]+$/, '') + '.pdf'
                            downloadBlob(blob, pdfName)
                          } catch (err) {
                            setError(err instanceof Error ? err.message : '导出 PDF 失败')
                          } finally {
                            setPdfLoading(false)
                          }
                        }}
                      >{pdfLoading ? '导出中…' : '导出 PDF'}</button>
                    </div>
                  </div>
                  <div className="tb-prep-summary-edit-hint">可手动修改总结，再点上方「导出 Word / PDF」导出修改后的版本</div>
                  <div className="tb-summary-fmt">
                    <span className="tb-summary-fmt-label">导出格式</span>
                    <label>正文字体
                      <select value={summaryFmt.bodyFont} onChange={e => setSummaryFmt(f => ({ ...f, bodyFont: e.target.value }))}>
                        <option value="">继承原文</option>
                        <option value="宋体">宋体</option>
                        <option value="微软雅黑">微软雅黑</option>
                        <option value="黑体">黑体</option>
                        <option value="楷体">楷体</option>
                        <option value="Times New Roman">Times New Roman</option>
                        <option value="Arial">Arial</option>
                      </select>
                    </label>
                    <label>正文字号
                      <select value={summaryFmt.bodySize} onChange={e => setSummaryFmt(f => ({ ...f, bodySize: Number(e.target.value) }))}>
                        <option value={0}>继承</option>
                        {[9, 10, 11, 12, 14, 16].map(s => <option key={s} value={s}>{s}</option>)}
                      </select>
                    </label>
                    <label>标题字号
                      <select value={summaryFmt.headingSize} onChange={e => setSummaryFmt(f => ({ ...f, headingSize: Number(e.target.value) }))}>
                        <option value={0}>自动(正文+3)</option>
                        {[12, 14, 16, 18, 20].map(s => <option key={s} value={s}>{s}</option>)}
                      </select>
                    </label>
                  </div>
                  <textarea
                    className="tb-prep-summary-edit"
                    value={summaryResult.summary}
                    spellCheck={false}
                    onChange={e => {
                      if (!selectedFileId) return
                      const next = e.target.value
                      setSummaryMap(prev => ({ ...prev, [selectedFileId]: { ...prev[selectedFileId], summary: next } }))
                    }}
                  />
                </>
              )}
            </div>
          </div>
          </>)}
        </section>

        {/* ── Section 3: Teams Bot ── */}
        <section className="tb-card">
          <h2 className="tb-card-title">Teams Bot</h2>

          {!selectedMeetingId && (
            <p className="tb-empty">请先在上方「会议」中选择或新建会议</p>
          )}

          {selectedMeetingId && (<>
          {/* 加入会议 */}
          <div className="tb-bot-join-row">
            <input
              className="tb-input"
              placeholder="https://teams.microsoft.com/l/meetup-join/..."
              value={meetingUrl}
              onChange={e => setMeetingUrl(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && void handleJoinMeeting()}
              disabled={!!activeCallId}
            />
            <button
              className={`tb-btn tb-btn--primary ${joinStatus === 'joining' ? 'tb-btn--loading' : ''}`}
              onClick={() => void handleJoinMeeting()}
              disabled={joinStatus === 'joining' || !!activeCallId}
            >
              {joinStatus === 'joining' ? '加入中…' : '加入会议'}
            </button>
          </div>
          <p className={`tb-status ${activeCallId ? 'tb-status--ok' : 'tb-status--idle'}`}>{botStatus}</p>
          {meetingTitle && (
            <p className="tb-meeting-title"><span>会议名称</span><strong>{meetingTitle}</strong></p>
          )}

          {/* 实际参加情况：一个「刷新」= 拉取 Teams 实到并核对应到/未到 */}
          <div className="tb-card-header" style={{ marginTop: 20 }}>
            <h3 className="tb-card-subtitle">实际参加情况</h3>
            <div className="tb-attendance-actions">
              <button
                className={`tb-btn tb-btn--primary tb-btn--sm ${(participantsLoading || attendanceLoading) ? 'tb-btn--loading' : ''}`}
                disabled={participantsLoading || attendanceLoading}
                onClick={() => void handleRefreshParticipants()}
                title={scheduleFile ? '从 Teams 拉取实到人员并核对应到/未到' : '从 Teams 拉取实到人员（上传会议安排后还会核对应到/未到）'}
              >
                {(participantsLoading || attendanceLoading) ? '刷新中…' : '刷新'}
              </button>
              {attendanceResult && (
                <button
                  className={`tb-btn tb-btn--secondary tb-btn--sm ${attendanceExportLoading ? 'tb-btn--loading' : ''}`}
                  disabled={attendanceExportLoading}
                  onClick={() => void handleExportAttendance()}
                >
                  {attendanceExportLoading ? '导出中…' : '导出 Word'}
                </button>
              )}
            </div>
          </div>
          {canCompareAttendance ? (
            <>
              {scheduleFile ? (
                <div className="tb-attendance-file">
                  <span>会议安排</span>
                  <strong>{scheduleFile.fileName}</strong>
                </div>
              ) : (
                <div className="tb-attendance-file">
                  <span>应到名单</span>
                  <strong>已保存（来自会议安排）</strong>
                </div>
              )}
              {!attendanceResult && !attendanceLoading && (
                <p className="tb-empty">机器人加入会议后，点「刷新」即可直接核对出应到 / 实到 / 未到</p>
              )}
            </>
          ) : (
            <>
              <p className="tb-empty">未保存应到名单：点「刷新」仅拉取 Teams 实到人员；上传会议安排后可核对应到 / 未到</p>
              {participants.length > 0 && (
                <div className="tb-attendance-table-wrap">
                  <div className="tb-attendance-stats">
                    <div className="tb-attendance-stat"><span>Teams 实到</span><strong>{participants.length}</strong></div>
                  </div>
                  <table className="tb-attendance-table">
                    <thead>
                      <tr><th>#</th><th>Teams 实到</th><th>邮箱</th></tr>
                    </thead>
                    <tbody>
                      {participants.map((p, index) => (
                        <tr key={p.aadId || index}>
                          <td>{index + 1}</td>
                          <td>{p.displayName || '-'}</td>
                          <td>{p.email || '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
          {attendanceResult && (
            <>
              <div className="tb-attendance-stats">
                <div className="tb-attendance-stat"><span>应到</span><strong>{attendanceResult.expectedCount}</strong></div>
                <div className="tb-attendance-stat"><span>Teams 实到</span><strong>{attendanceResult.actualCount}</strong></div>
                <div className="tb-attendance-stat"><span>安排内实到</span><strong>{attendanceResult.presentCount}</strong></div>
                <div className="tb-attendance-stat"><span>未到</span><strong>{attendanceResult.absentCount}</strong></div>
                <div className="tb-attendance-stat"><span>未在安排中</span><strong>{attendanceResult.unexpectedCount}</strong></div>
              </div>
              <div className="tb-attendance-table-wrap">
                <table className="tb-attendance-table">
                  <thead>
                    <tr><th>状态</th><th>安排姓名</th><th>分组</th><th>Teams 实到</th><th>邮箱</th></tr>
                  </thead>
                  <tbody>
                    {attendanceResult.rows.map((row, index) => (
                      <tr key={`${row.status}-${row.name || row.actualName || index}`}>
                        <td>
                          <span className={`tb-attendance-status ${attendanceStatusClass(row.status)}`}>
                            {attendanceStatusText(row.status)}
                          </span>
                        </td>
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
          )}
          </>)}
        </section>
      </main>
    </div>
  )
}
