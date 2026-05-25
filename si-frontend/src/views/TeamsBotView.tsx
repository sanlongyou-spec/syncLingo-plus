import { useCallback, useEffect, useRef, useState } from 'react'
import {
  exportPreMeetingAttendanceDocx,
  exportPreMeetingDocx,
  generatePreMeetingAttendance,
  getMeetingParticipants,
  joinMeeting,
  sendSummaryToMeetingChat,
  sendTeamsSummaryToUsers,
  summarizePreMeetingFile,
  updateInterpretationSessionTitle,
  uploadPreMeetingFile,
} from '../api'
import { ROUTES, STORAGE_KEYS, TEAMS_BOT_STORAGE_KEYS } from '../constants'
import type { MeetingParticipant, PreMeetingAttendanceResult, PreMeetingFile, PreMeetingSummaryResult } from '../types'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './TeamsBotView.css'

type TbTab = 'prepare' | 'bot'

// ── 导出工具 ─────────────────────────────────────────────
const escapeHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

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

const exportPdf = (title: string, extractedText: string, summary: string) => {
  const win = window.open('', '_blank')
  if (!win) return
  win.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"/>
    <title>${escapeHtml(title)}</title>
    <style>
      body{font-family:"Microsoft YaHei",Arial,sans-serif;line-height:1.9;font-size:12pt;margin:40px;color:#111827;}
      h1{font-size:18pt;} h2{font-size:14pt;margin-top:2em;color:#1d4ed8;}
      pre{white-space:pre-wrap;word-break:break-word;font-family:inherit;}
      .print-btn{margin-top:24px;padding:8px 18px;border:1px solid #d0d5dd;border-radius:8px;background:#fff;cursor:pointer;}
      @media print{.print-btn{display:none;}}
    </style></head><body>
    <h1>${escapeHtml(title)}</h1>
    <pre>${escapeHtml(extractedText)}</pre>
    <h2>AI 总结</h2>
    <pre>${escapeHtml(summary)}</pre>
    <button class="print-btn" onclick="window.print()">打印 / 另存为 PDF</button>
  </body></html>`)
  win.document.close()
  win.focus()
  setTimeout(() => win.print(), 400)
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
  const [activeTab, setActiveTab] = useState<TbTab>('prepare')

  // ── 会前准备 state ────────────────────────────────────
  const [prepFiles, setPrepFiles] = useState<PreMeetingFile[]>([])
  const [selectedFileId, setSelectedFileId] = useState('')
  const [defaultReqDraft, setDefaultReqDraft] = useState(() =>
    localStorage.getItem(TEAMS_BOT_STORAGE_KEYS.PRE_MEETING_DEFAULT_REQ) ?? ''
  )
  const [defaultReqSaved, setDefaultReqSaved] = useState(false)
  const [extraReq, setExtraReq] = useState('')
  const [summaryMap, setSummaryMap] = useState<Record<string, PreMeetingSummaryResult>>({})
  const [loadingMap, setLoadingMap] = useState<Record<string, boolean>>({})
  const [uploadLoading, setUploadLoading] = useState(false)
  const [exportLoading, setExportLoading] = useState(false)
  const [prepError, setPrepError] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  const saveDefaultReq = () => {
    localStorage.setItem(TEAMS_BOT_STORAGE_KEYS.PRE_MEETING_DEFAULT_REQ, defaultReqDraft)
    setDefaultReqSaved(true)
    setTimeout(() => setDefaultReqSaved(false), 1500)
  }

  const selectedFile = prepFiles.find(f => f.fileId === selectedFileId)
  const summaryResult = selectedFileId ? (summaryMap[selectedFileId] ?? null) : null

  const doUpload = async (file: File) => {
    setUploadLoading(true)
    setPrepError('')
    try {
      const res = await uploadPreMeetingFile(file)
      const added = res.data || []
      setPrepFiles(prev => [...prev, ...added])
      // 只在当前没有选中文件时才自动选第一个新文件
      setSelectedFileId(prev => prev || (added.length > 0 ? added[0].fileId : ''))
    } catch (err) {
      setPrepError(err instanceof Error ? err.message : '上传失败')
    } finally {
      setUploadLoading(false)
    }
  }

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    e.target.value = ''
    await doUpload(file)
  }

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault()
    const file = e.dataTransfer.files[0]
    if (!file) return
    await doUpload(file)
  }

  const removeFile = (fileId: string) => {
    setPrepFiles(prev => prev.filter(f => f.fileId !== fileId))
    setSummaryMap(prev => { const next = { ...prev }; delete next[fileId]; return next })
    if (selectedFileId === fileId) setSelectedFileId('')
    if (attendanceFileId === fileId) {
      setAttendanceFileId('')
      setAttendanceResult(null)
    }
  }

  const handleSummarize = async () => {
    if (!selectedFileId || loadingMap[selectedFileId]) return
    const fileId = selectedFileId
    setLoadingMap(prev => ({ ...prev, [fileId]: true }))
    setPrepError('')
    const combined = [defaultReqDraft.trim(), extraReq.trim()].filter(Boolean).join('\n')
    try {
      const res = await summarizePreMeetingFile(fileId, combined, userId)
      setSummaryMap(prev => ({ ...prev, [fileId]: res.data }))
    } catch (err) {
      setPrepError(err instanceof Error ? err.message : '生成总结失败')
    } finally {
      setLoadingMap(prev => ({ ...prev, [fileId]: false }))
    }
  }

  // ── Teams Bot state ──────────────────────────────────
  const [meetingUrl, setMeetingUrl] = useState('')
  const [joinStatus, setJoinStatus] = useState<'idle' | 'joining' | 'joined' | 'error'>('idle')
  const [activeCallId, setActiveCallId] = useState<string | null>(null)
  const [meetingTitle, setMeetingTitle] = useState<string | null>(null)
  const [participants, setParticipants] = useState<MeetingParticipant[]>([])
  const [participantsLoading, setParticipantsLoading] = useState(false)
  const [sendStatus, setSendStatus] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle')
  const [chatSendStatus, setChatSendStatus] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle')
  const [attendanceFileId, setAttendanceFileId] = useState('')
  const [attendanceLoading, setAttendanceLoading] = useState(false)
  const [attendanceExportLoading, setAttendanceExportLoading] = useState(false)
  const [attendanceResult, setAttendanceResult] = useState<PreMeetingAttendanceResult | null>(null)
  const [summaryContent, setSummaryContent] = useState('')
  const [botError, setBotError] = useState('')
  const [botSuccess, setBotSuccess] = useState('')
  const attendanceFileInputRef = useRef<HTMLInputElement>(null)
  const attendanceFile = prepFiles.find(f => f.fileId === attendanceFileId)

  async function syncCurrentSessionTitle(title: string) {
    const currentSessionId = localStorage.getItem(STORAGE_KEYS.CURRENT_SESSION_ID)
    if (!currentSessionId || !title.trim()) return
    try {
      await updateInterpretationSessionTitle(currentSessionId, userId, title.trim())
    } catch (e) {
      console.warn('[TeamsBotView] failed to sync meeting title:', e)
    }
  }

  const fetchParticipants = useCallback(async () => {
    setParticipantsLoading(true)
    try {
      const data = await getMeetingParticipants()
      setParticipants(data.participants)
      if (data.callId) setActiveCallId(data.callId)
      if (data.meetingTitle) {
        setMeetingTitle(data.meetingTitle)
        await syncCurrentSessionTitle(data.meetingTitle)
      }
    } catch (e) {
      setBotError(e instanceof Error ? e.message : '获取参会人员失败')
    } finally {
      setParticipantsLoading(false)
    }
  }, [])

  const handleAttendanceFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return

    setAttendanceLoading(true)
    setBotError('')
    setBotSuccess('')
    try {
      const res = await uploadPreMeetingFile(file)
      const added = res.data || []
      if (added.length === 0) {
        throw new Error('未解析到可用的会议安排文件')
      }
      setPrepFiles(prev => [...prev, ...added])
      setAttendanceFileId(added[0].fileId)
      setSelectedFileId(prev => prev || added[0].fileId)
      setAttendanceResult(null)
      setBotSuccess('会议安排已上传，可以刷新生成实际参加情况')
    } catch (e) {
      setBotError(e instanceof Error ? e.message : '上传会议安排失败')
    } finally {
      setAttendanceLoading(false)
    }
  }

  const handleGenerateAttendance = async () => {
    if (!attendanceFileId) {
      setBotError('请先上传会议安排文件')
      return
    }
    setAttendanceLoading(true)
    setParticipantsLoading(true)
    setBotError('')
    setBotSuccess('')
    try {
      const data = await getMeetingParticipants()
      setParticipants(data.participants)
      if (data.callId) setActiveCallId(data.callId)
      if (data.meetingTitle) {
        setMeetingTitle(data.meetingTitle)
        await syncCurrentSessionTitle(data.meetingTitle)
      }

      const res = await generatePreMeetingAttendance(attendanceFileId, data.participants)
      setAttendanceResult(res.data)
      setBotSuccess('已根据会议安排生成实际参加情况')
    } catch (e) {
      setBotError(e instanceof Error ? e.message : '生成实际参加情况失败')
    } finally {
      setParticipantsLoading(false)
      setAttendanceLoading(false)
    }
  }

  const handleExportAttendance = async () => {
    if (!attendanceFileId || !attendanceResult) {
      setBotError('请先生成实际参加情况')
      return
    }
    setAttendanceExportLoading(true)
    setBotError('')
    setBotSuccess('')
    try {
      const blob = await exportPreMeetingAttendanceDocx(attendanceFileId, participants)
      const title = attendanceResult.meetingTitle || attendanceResult.fileName || '实际参会名单'
      downloadBlob(blob, `${sanitizeFilename(title)}_实际参会名单.docx`)
      setBotSuccess('实际参会名单 Word 已导出')
    } catch (e) {
      setBotError(e instanceof Error ? e.message : '导出实际参会名单失败')
    } finally {
      setAttendanceExportLoading(false)
    }
  }

  useEffect(() => { fetchParticipants() }, [fetchParticipants])

  const handleJoinMeeting = async () => {
    const url = meetingUrl.trim()
    if (!url) { setBotError('请粘贴 Teams 会议链接'); return }
    setJoinStatus('joining')
    setBotError('')
    try {
      const data = await joinMeeting(url)
      setActiveCallId(data.callId)
      if (data.meetingTitle) {
        setMeetingTitle(data.meetingTitle)
        await syncCurrentSessionTitle(data.meetingTitle)
      }
      setJoinStatus('joined')
      setBotSuccess('机器人已加入会议，正在获取参会人员…')
      setMeetingUrl('')
      setTimeout(fetchParticipants, 3000)
    } catch (e) {
      setJoinStatus('error')
      setBotError(e instanceof Error ? e.message : '加入会议失败')
    }
  }

  const handleSendToParticipants = async () => {
    const content = summaryContent.trim()
    if (!content) { setBotError('请填写摘要内容'); return }
    if (participants.length === 0) { setBotError('参会人员列表为空，请先让机器人加入会议'); return }
    const recipients = participants.map(p => p.aadId).filter(Boolean)
    if (recipients.length === 0) { setBotError('参会人员信息不完整，请刷新后重试'); return }
    setSendStatus('sending')
    setBotError('')
    setBotSuccess('')
    try {
      const res = await sendTeamsSummaryToUsers(content, recipients)
      if (res.sentCount === 0) throw new Error(res.failures[0]?.error || '没有 Teams 用户收到摘要')
      setSendStatus('sent')
      setBotSuccess(res.failedCount > 0
        ? `已发送给 ${res.sentCount} 人，${res.failedCount} 人失败`
        : `已成功发送给全部 ${res.sentCount} 位参会人员`)
      setSummaryContent('')
    } catch (e) {
      setSendStatus('error')
      setBotError(`发送失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const handleSendToMeetingChat = async () => {
    const content = summaryContent.trim()
    if (!content) { setBotError('请填写摘要内容'); return }
    if (!activeCallId) { setBotError('机器人尚未加入会议，请先加入会议'); return }
    setChatSendStatus('sending')
    setBotError('')
    setBotSuccess('')
    try {
      await sendSummaryToMeetingChat(content)
      setChatSendStatus('sent')
      setBotSuccess('摘要已发送到会议聊天，所有参会人员可见')
      setSummaryContent('')
    } catch (e) {
      setChatSendStatus('error')
      setBotError(`发送失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const botStatus = activeCallId
    ? '机器人已在会议中'
    : joinStatus === 'joined' ? '机器人已加入' : '机器人未在会议中'

  return (
    <div className="si-root tb-root">
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

      <div className="tb-tab-nav">
        <button
          className={`tb-tab-btn${activeTab === 'prepare' ? ' tb-tab-btn--active' : ''}`}
          onClick={() => setActiveTab('prepare')}
        >会前准备</button>
        <button
          className={`tb-tab-btn${activeTab === 'bot' ? ' tb-tab-btn--active' : ''}`}
          onClick={() => setActiveTab('bot')}
        >Teams Bot</button>
      </div>

      {/* ── 会前准备 tab ── */}
      {activeTab === 'prepare' && (
        <main className="tb-prepare-main">

          {/* 左侧：上传 + 文件列表 + 要求输入 */}
          <section className="tb-prepare-left">

            {/* 默认要求 */}
            <div className="tb-default-req">
              <div className="tb-default-req-header">
                <span className="tb-prep-label">默认总结要求</span>
                <button
                  className={`tb-btn tb-btn--sm ${defaultReqSaved ? 'tb-btn--secondary' : 'tb-btn--primary'}`}
                  onClick={saveDefaultReq}
                  type="button"
                >
                  {defaultReqSaved ? '已保存 ✓' : '保存'}
                </button>
              </div>
              <textarea
                className="tb-prep-textarea"
                rows={3}
                placeholder="每次生成 AI 总结时自动带入，例如：输出中文，重点提取数据与结论…"
                value={defaultReqDraft}
                onChange={e => { setDefaultReqDraft(e.target.value); setDefaultReqSaved(false) }}
              />
            </div>

            {/* 上传区 */}
            <div
              className={`tb-upload-zone${uploadLoading ? ' tb-upload-zone--loading' : ''}`}
              onDragOver={e => e.preventDefault()}
              onDrop={handleDrop}
              onClick={() => !uploadLoading && fileInputRef.current?.click()}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".doc,.docx,.pdf,.zip"
                style={{ display: 'none' }}
                onChange={handleFileChange}
              />
              {uploadLoading ? (
                <span className="tb-upload-spinner" />
              ) : (
                <>
                  <span className="tb-upload-icon">📄</span>
                  <span className="tb-upload-hint">点击或拖拽上传报告</span>
                  <span className="tb-upload-sub">支持 Word、PDF、ZIP（含 Word/PDF）</span>
                </>
              )}
            </div>

            {prepError && <div className="tb-prep-error">{prepError}</div>}

            {/* 文件列表 */}
            {prepFiles.length > 0 && (
              <div className="tb-file-list">
                <div className="tb-file-list-label">已上传文件（{prepFiles.length}）</div>
                {prepFiles.map(f => (
                  <div key={f.fileId} className="tb-file-item-wrap">
                    <button
                      className={`tb-file-item${selectedFileId === f.fileId ? ' tb-file-item--active' : ''}`}
                      onClick={() => setSelectedFileId(f.fileId)}
                      type="button"
                    >
                      <span className="tb-file-icon">{f.fileName.endsWith('.pdf') ? '📕' : '📘'}</span>
                      <span className="tb-file-name">{f.fileName}</span>
                      {loadingMap[f.fileId] && <span className="tb-file-spinner" />}
                      {!loadingMap[f.fileId] && summaryMap[f.fileId] && <span className="tb-file-done">✓</span>}
                    </button>
                    <button
                      className="tb-file-remove"
                      onClick={() => removeFile(f.fileId)}
                      type="button"
                      title="移除"
                    >×</button>
                  </div>
                ))}
              </div>
            )}

            {/* 本次额外要求 + 生成按钮 */}
            {selectedFileId && (
              <>
                <div className="tb-prep-field">
                  <label className="tb-prep-label">本次额外要求（可选）</label>
                  <textarea
                    className="tb-prep-textarea"
                    rows={2}
                    placeholder="在默认要求基础上追加，例如：本次重点关注财务数据…"
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
          </section>

          {/* 右侧：总结展示 + 导出 */}
          <section className="tb-prepare-right">
            {!summaryResult && !loadingMap[selectedFileId] && (
              <div className="tb-prep-placeholder">
                {selectedFile
                  ? `已选择「${selectedFile.fileName}」，点击"生成 AI 总结"开始分析`
                  : '上传报告并选择文件后，AI 总结将显示在这里'}
              </div>
            )}
            {loadingMap[selectedFileId] && !summaryResult && (
              <div className="tb-prep-placeholder">
                <span className="tb-upload-spinner" />
                <span style={{ marginLeft: 10 }}>正在分析报告，请稍候…</span>
              </div>
            )}
            {summaryResult && (
              <>
                <div className="tb-prep-result-header">
                  <div>
                    <div className="tb-prep-result-title">{summaryResult.fileName}</div>
                    <div className="tb-prep-result-sub">
                      AI 总结{loadingMap[selectedFileId] ? ' · 重新生成中…' : ''}
                    </div>
                  </div>
                  <div className="tb-prep-export-row">
                    <button
                      className="tb-btn tb-btn--secondary tb-btn--sm"
                      disabled={exportLoading}
                      onClick={async () => {
                        if (!selectedFileId) return
                        setExportLoading(true)
                        setPrepError('')
                        try {
                          const blob = await exportPreMeetingDocx(selectedFileId, summaryResult.summary)
                          downloadBlob(blob, sanitizeFilename(summaryResult.fileName))
                        } catch (err) {
                          setPrepError(err instanceof Error ? err.message : '导出失败')
                        } finally {
                          setExportLoading(false)
                        }
                      }}
                    >{exportLoading ? '导出中…' : '导出 Word'}</button>
                    <button
                      className="tb-btn tb-btn--secondary tb-btn--sm"
                      onClick={() => exportPdf(summaryResult.fileName, summaryResult.extractedText, summaryResult.summary)}
                    >导出 PDF</button>
                  </div>
                </div>
                <pre className="tb-prep-summary-text">{summaryResult.summary}</pre>
              </>
            )}
          </section>
        </main>
      )}

      {/* ── Teams Bot tab ── */}
      {activeTab === 'bot' && (
        <main className="tb-main">
          <ErrorBanner message={botError} onDismiss={() => setBotError('')} />
          {botSuccess && <SuccessBanner message={botSuccess} />}

          <section className="tb-card">
            <h2 className="tb-card-title">加入会议</h2>
            <div className="tb-add-row">
              <input
                className="tb-input"
                placeholder="https://teams.microsoft.com/l/meetup-join/..."
                value={meetingUrl}
                onChange={e => setMeetingUrl(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleJoinMeeting()}
                disabled={!!activeCallId}
              />
              <button
                className={`tb-btn tb-btn--primary ${joinStatus === 'joining' ? 'tb-btn--loading' : ''}`}
                onClick={handleJoinMeeting}
                disabled={joinStatus === 'joining' || !!activeCallId}
              >
                {joinStatus === 'joining' ? '加入中…' : '加入会议'}
              </button>
            </div>
            <p className={`tb-status ${activeCallId ? 'tb-status--ok' : 'tb-status--idle'}`}>
              {botStatus}
            </p>
            {meetingTitle && (
              <p className="tb-meeting-title">
                <span>会议名称</span>
                <strong>{meetingTitle}</strong>
              </p>
            )}
          </section>

          <section className="tb-card">
            <div className="tb-card-header">
              <h2 className="tb-card-title">参会人员</h2>
              <button
                className="tb-btn tb-btn--secondary tb-btn--sm"
                onClick={fetchParticipants}
                disabled={participantsLoading}
              >
                {participantsLoading ? '刷新中…' : '刷新'}
              </button>
            </div>
            {participants.length === 0 ? (
              <p className="tb-empty">
                {participantsLoading ? '正在获取参会人员…' : '暂无参会人员（机器人尚未加入会议，或会议中还没有其他人）'}
              </p>
            ) : (
              <div className="tb-recipient-list">
                {participants.map(p => (
                  <div key={p.aadId} className="tb-recipient-row">
                    <span className="tb-type-pill tb-type-pill--person">参会</span>
                    <span className="tb-recipient-value">
                      {p.displayName || p.aadId}
                      {p.email && <span className="tb-recipient-sub"> · {p.email}</span>}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="tb-card tb-attendance-card">
            <div className="tb-card-header">
              <div>
                <h2 className="tb-card-title">实际参加情况</h2>
              </div>
            </div>
            <input
              ref={attendanceFileInputRef}
              type="file"
              accept=".doc,.docx,.pdf"
              style={{ display: 'none' }}
              onChange={handleAttendanceFileChange}
            />
            <div className="tb-attendance-actions">
              <button
                className="tb-btn tb-btn--secondary"
                type="button"
                disabled={attendanceLoading}
                onClick={() => attendanceFileInputRef.current?.click()}
              >
                {attendanceFile ? '重新上传会议安排' : '上传会议安排'}
              </button>
              <button
                className={`tb-btn tb-btn--primary ${attendanceLoading ? 'tb-btn--loading' : ''}`}
                type="button"
                disabled={attendanceLoading || !attendanceFileId}
                onClick={handleGenerateAttendance}
              >
                {attendanceLoading ? '生成中…' : '刷新并生成实际参加情况'}
              </button>
              <button
                className={`tb-btn tb-btn--secondary ${attendanceExportLoading ? 'tb-btn--loading' : ''}`}
                type="button"
                disabled={attendanceExportLoading || !attendanceResult}
                onClick={handleExportAttendance}
              >
                {attendanceExportLoading ? '导出中…' : '导出 Word'}
              </button>
            </div>
            {attendanceFile && (
              <div className="tb-attendance-file">
                <span>会议安排</span>
                <strong>{attendanceFile.fileName}</strong>
              </div>
            )}
            {attendanceResult && (
              <>
                <div className="tb-attendance-stats">
                  <div className="tb-attendance-stat">
                    <span>应到</span>
                    <strong>{attendanceResult.expectedCount}</strong>
                  </div>
                  <div className="tb-attendance-stat">
                    <span>Teams 实到</span>
                    <strong>{attendanceResult.actualCount}</strong>
                  </div>
                  <div className="tb-attendance-stat">
                    <span>安排内实到</span>
                    <strong>{attendanceResult.presentCount}</strong>
                  </div>
                  <div className="tb-attendance-stat">
                    <span>未到</span>
                    <strong>{attendanceResult.absentCount}</strong>
                  </div>
                  <div className="tb-attendance-stat">
                    <span>未在安排中</span>
                    <strong>{attendanceResult.unexpectedCount}</strong>
                  </div>
                </div>
                <div className="tb-attendance-table-wrap">
                  <table className="tb-attendance-table">
                    <thead>
                      <tr>
                        <th>状态</th>
                        <th>安排姓名</th>
                        <th>分组</th>
                        <th>Teams 实到</th>
                        <th>邮箱</th>
                      </tr>
                    </thead>
                    <tbody>
                      {attendanceResult.rows.map((row, index) => (
                        <tr key={`${row.status}-${row.name || row.actualName || index}-${index}`}>
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
          </section>

          <section className="tb-card">
            <h2 className="tb-card-title">发送会议摘要</h2>
            <textarea
              className="tb-textarea"
              rows={5}
              placeholder="在此粘贴摘要内容…"
              value={summaryContent}
              onChange={e => setSummaryContent(e.target.value)}
            />
            <div className="tb-send-row">
              <span className="tb-send-hint">将发送给 {participants.length} 位参会人员</span>
              <button
                className={`tb-btn tb-btn--secondary ${chatSendStatus === 'sending' ? 'tb-btn--loading' : ''}`}
                onClick={handleSendToMeetingChat}
                disabled={chatSendStatus === 'sending' || !activeCallId}
              >
                {chatSendStatus === 'sending' ? '发送中…' : '发送到会议聊天'}
              </button>
              <button
                className={`tb-btn tb-btn--primary ${sendStatus === 'sending' ? 'tb-btn--loading' : ''}`}
                onClick={handleSendToParticipants}
                disabled={sendStatus === 'sending' || participants.length === 0}
              >
                {sendStatus === 'sending' ? '发送中…' : '发送给参会人员'}
              </button>
            </div>
          </section>
        </main>
      )}
    </div>
  )
}
