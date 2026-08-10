import { useEffect, useRef, useState } from 'react'
import {
  createMeeting,
  deleteMeetingFile,
  getMeetings,
  previewMeetingNotification,
  saveExpectedParticipants,
  savePreMeetingFileToMeeting,
  sendMeetingNotification,
  uploadFileToMeeting,
  uploadPreMeetingFile,
} from '../api'
import { ROUTES, MEETINGS_STORAGE_KEYS } from '../constants'
import type {
  Meeting,
  MeetingFile,
  MeetingNotificationPreview,
  MeetingNotificationSendResult,
  PreMeetingFile,
} from '../types'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './MeetingsView.css'

const RESULT_OK_CODE = 200
const ACCEPTED_REPORT_EXTENSIONS = ['pdf', 'doc', 'docx'] as const
const ACCEPTED_REPORT_MIME = [
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
]

const reportExtension = (fileName: string) => {
  const dot = fileName.lastIndexOf('.')
  return dot >= 0 ? fileName.slice(dot + 1).toLowerCase() : ''
}

const isAcceptedReportFile = (file: File) => {
  const ext = reportExtension(file.name)
  return ACCEPTED_REPORT_EXTENSIONS.includes(ext as typeof ACCEPTED_REPORT_EXTENSIONS[number])
    || ACCEPTED_REPORT_MIME.includes(file.type)
}

const stripExtension = (fileName: string) =>
  fileName.replace(/\.[^.]+$/, '').trim()

const recipientKey = (recipient: { teamsAccount?: string | null; email?: string | null }) =>
  recipient.teamsAccount || recipient.email || ''

const parseNotificationSendResult = (json?: string | null): MeetingNotificationSendResult | null => {
  if (!json?.trim()) return null
  try {
    const parsed = JSON.parse(json) as MeetingNotificationSendResult
    return typeof parsed.sentCount === 'number' && typeof parsed.failedCount === 'number' ? parsed : null
  } catch {
    return null
  }
}

interface NotificationPreviewOptions {
  showError?: boolean
  showSuccess?: boolean
}

export default function MeetingsView() {
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)
  const [meetingFiles, setMeetingFiles] = useState<MeetingFile[]>([])
  const [meetingHasExpected, setMeetingHasExpected] = useState(false)
  const [meetingUrl, setMeetingUrl] = useState('')
  const [noticeFile, setNoticeFile] = useState<PreMeetingFile | null>(null)
  const [notificationPreview, setNotificationPreview] = useState<MeetingNotificationPreview | null>(null)
  const [notificationContent, setNotificationContent] = useState('')
  const [selectedNotificationRecipients, setSelectedNotificationRecipients] = useState<Set<string>>(new Set())
  const [notificationSendResult, setNotificationSendResult] = useState<MeetingNotificationSendResult | null>(null)
  const [newMeetingTitle, setNewMeetingTitle] = useState('')
  const [newMeetingScheduledTime, setNewMeetingScheduledTime] = useState('')
  const [newMeetingNote, setNewMeetingNote] = useState('')
  const [loading, setLoading] = useState(true)
  const [creatingMeeting, setCreatingMeeting] = useState(false)
  const [noticeUploading, setNoticeUploading] = useState(false)
  const [noticePreviewing, setNoticePreviewing] = useState(false)
  const [notificationSending, setNotificationSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [deletingFileId, setDeletingFileId] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const noticeInputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const flash = (message: string) => {
    setSuccess(message)
    window.setTimeout(() => setSuccess(''), 2200)
  }

  const resetNotificationState = () => {
    setNoticeFile(null)
    setNotificationPreview(null)
    setNotificationContent('')
    setSelectedNotificationRecipients(new Set())
    setNotificationSendResult(null)
  }

  const applyMeetingSelection = (id: number | null, source: Meeting[] = meetings) => {
    setSelectedMeetingId(id)
    if (id) {
      localStorage.setItem(MEETINGS_STORAGE_KEYS.LAST_SELECTED_MEETING_ID, String(id))
    } else {
      localStorage.removeItem(MEETINGS_STORAGE_KEYS.LAST_SELECTED_MEETING_ID)
    }
    const meeting = id ? source.find(item => item.id === id) : null
    setMeetingUrl(meeting?.meetingUrl || '')
    setMeetingFiles(meeting?.files || [])
    setMeetingHasExpected(!!meeting?.hasExpectedParticipants)
    resetNotificationState()
    setNotificationSendResult(parseNotificationSendResult(meeting?.notificationResultJson))
  }

  const loadMeetings = async () => {
    setLoading(true)
    setError('')
    try {
      const result = await getMeetings()
      const list = result.data || []
      setMeetings(list)
      // 进入会议页默认不自动选中任何会议（下拉显示"请选择会议"，各区块为空），
      // 由用户手动选择，避免一进来就带出上次或第一个（可能已删）会议的数据。
      applyMeetingSelection(null, list)
    } catch (err) {
      setError(err instanceof Error ? err.message : '会议加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadMeetings()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selectedMeeting = selectedMeetingId
    ? meetings.find(meeting => meeting.id === selectedMeetingId)
    : null

  const handleMeetingSelect = (value: string) => {
    applyMeetingSelection(value ? Number(value) : null)
  }

  const handleCreateMeeting = async () => {
    const title = newMeetingTitle.trim()
    if (!title) {
      setError('请填写会议名称')
      return
    }
    setCreatingMeeting(true)
    setError('')
    try {
      const result = await createMeeting({
        title,
        scheduledTime: newMeetingScheduledTime ? newMeetingScheduledTime.replace('T', ' ') : undefined,
        note: newMeetingNote.trim() || undefined,
      })
      const created = result.data
      const nextMeetings = [created, ...meetings.filter(item => item.id !== created.id)]
      setMeetings(nextMeetings)
      applyMeetingSelection(created.id, nextMeetings)
      setNewMeetingTitle('')
      setNewMeetingScheduledTime('')
      setNewMeetingNote('')
      flash('会议已创建')
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建会议失败')
    } finally {
      setCreatingMeeting(false)
    }
  }

  const resolveMeetingForNotice = async (file: File, parsedFile: PreMeetingFile) => {
    const parsedTitle = parsedFile.meetingTitle?.trim()
    if (!parsedTitle && selectedMeeting) {
      return selectedMeeting
    }
    const title = parsedTitle || stripExtension(file.name) || '会议'
    const existing = meetings.find(item => item.title === title)
    if (existing) {
      applyMeetingSelection(existing.id, meetings)
      return existing
    }
    const result = await createMeeting({ title })
    const created = result.data
    const nextMeetings = [created, ...meetings.filter(item => item.id !== created.id)]
    setMeetings(nextMeetings)
    applyMeetingSelection(created.id, nextMeetings)
    return created
  }

  const runNotificationPreview = async (
    targetMeetingId = selectedMeetingId,
    targetFile = noticeFile,
    options: NotificationPreviewOptions = {},
  ) => {
    const showError = options.showError ?? true
    const showSuccess = options.showSuccess ?? true
    if (!targetMeetingId) {
      if (showError) setError('请先选择或创建会议')
      return null
    }
    const normalizedMeetingUrl = meetingUrl.trim()
    if (!normalizedMeetingUrl) {
      if (showError) setError('生成通知前请先填写会议链接')
      return null
    }
    setNoticePreviewing(true)
    if (showError) setError('')
    try {
      const preview = await previewMeetingNotification(
        targetMeetingId,
        targetFile?.fileId,
        normalizedMeetingUrl,
      )
      setNotificationPreview(preview)
      setNotificationContent(preview.notificationContent || '')
      setSelectedNotificationRecipients(new Set(
        (preview.teamsRecipients || [])
          .map(recipientKey)
          .filter(Boolean),
      ))
      setMeetings(previous => previous.map(item =>
        item.id === targetMeetingId
          ? {
              ...item,
              meetingUrl: preview.meetingUrl || normalizedMeetingUrl,
              hasExpectedParticipants: preview.participantNames.length > 0,
            }
          : item,
      ))
      setMeetingUrl(preview.meetingUrl || normalizedMeetingUrl)
      setMeetingHasExpected(preview.participantNames.length > 0)
      if (showSuccess) flash('会议通知已解析')
      return preview
    } catch (err) {
      if (showError) {
        setError(err instanceof Error ? err.message : '解析会议通知失败')
      }
      return null
    } finally {
      setNoticePreviewing(false)
    }
  }

  useEffect(() => {
    if (!selectedMeetingId || notificationPreview || noticePreviewing || noticeUploading) return
    if (!meetingUrl.trim() || meetingFiles.length === 0) return
    void runNotificationPreview(selectedMeetingId, null, { showError: false, showSuccess: false })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedMeetingId, meetingUrl, meetingFiles.length])

  const handleNoticeUpload = async (file: File) => {
    if (!isAcceptedReportFile(file)) {
      setError('会议通知仅支持 PDF 或 Word（.doc/.docx）')
      return
    }
    const normalizedMeetingUrl = meetingUrl.trim()
    setNoticeUploading(true)
    setError('')
    setNotificationSendResult(null)
    try {
      const uploadedNotice = await uploadPreMeetingFile(file)
      if (uploadedNotice.code !== RESULT_OK_CODE) {
        throw new Error(uploadedNotice.message || '上传会议通知失败')
      }
      const parsedFiles = uploadedNotice.data || []
      if (parsedFiles.length === 0) {
        throw new Error('未解析到可用的会议通知文件')
      }
      const parsedFile = parsedFiles[0]
      const targetMeeting = await resolveMeetingForNotice(file, parsedFile)
      const targetMeetingId = targetMeeting.id
      const savedFileResult = await savePreMeetingFileToMeeting(targetMeetingId, parsedFile.fileId)
      if (savedFileResult.code !== RESULT_OK_CODE) {
        throw new Error(savedFileResult.message || '保存会议通知失败')
      }
      const savedFile = savedFileResult.data
      const expectedResult = await saveExpectedParticipants(parsedFile.fileId, targetMeetingId)
      if (expectedResult.code !== RESULT_OK_CODE) {
        throw new Error(expectedResult.message || '保存应到名单失败')
      }
      setNoticeFile(parsedFile)
      setMeetingFiles([...(targetMeeting.files || []), savedFile])
      setMeetingHasExpected((expectedResult.data || 0) > 0)
      setMeetings(previous => previous.map(item =>
        item.id === targetMeetingId
          ? {
              ...item,
              hasExpectedParticipants: (expectedResult.data || 0) > 0,
              files: [...(item.files || []), savedFile],
            }
          : item,
      ))
      if (normalizedMeetingUrl) {
        flash('会议通知已上传')
        await runNotificationPreview(targetMeetingId, parsedFile)
      } else {
        setNotificationPreview(null)
        setNotificationContent('')
        flash('会议通知已上传')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传会议通知失败')
    } finally {
      setNoticeUploading(false)
    }
  }

  const handleUploadFile = async (file: File) => {
    if (!selectedMeetingId) {
      setError('请先选择或创建会议')
      return
    }
    if (!isAcceptedReportFile(file)) {
      setError('只支持 PDF、Word（.doc/.docx）汇报文件')
      return
    }
    setUploading(true)
    setError('')
    try {
      const result = await uploadFileToMeeting(selectedMeetingId, file)
      if (result.code !== RESULT_OK_CODE) {
        throw new Error(result.message || '上传会议文件失败')
      }
      const uploaded = result.data
      setMeetingFiles(previous => [...previous, uploaded])
      setMeetings(previous => previous.map(item =>
        item.id === selectedMeetingId
          ? { ...item, files: [...(item.files || []), uploaded] }
          : item,
      ))
      flash('会议文件已上传')
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传会议文件失败')
    } finally {
      setUploading(false)
    }
  }

  const handleDeleteFile = async (fileId: number) => {
    if (!selectedMeetingId) return
    setDeletingFileId(fileId)
    setError('')
    try {
      await deleteMeetingFile(selectedMeetingId, fileId)
      setMeetingFiles(previous => previous.filter(file => file.id !== fileId))
      setMeetings(previous => previous.map(item =>
        item.id === selectedMeetingId
          ? { ...item, files: (item.files || []).filter(file => file.id !== fileId) }
          : item,
      ))
      flash('会议文件已删除')
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除会议文件失败')
    } finally {
      setDeletingFileId(null)
    }
  }

  const toggleNotificationRecipient = (recipient: string) => {
    setSelectedNotificationRecipients(previous => {
      const next = new Set(previous)
      if (next.has(recipient)) next.delete(recipient)
      else next.add(recipient)
      return next
    })
  }

  const setAllNotificationRecipients = (checked: boolean) => {
    setSelectedNotificationRecipients(new Set(
      checked && notificationPreview
        ? notificationPreview.teamsRecipients.map(recipientKey).filter(Boolean)
        : [],
    ))
  }

  const handleSendNotification = async () => {
    if (!selectedMeetingId) {
      setError('请先选择或创建会议')
      return
    }
    const content = notificationContent.trim()
    if (!content) {
      setError('通知内容不能为空')
      return
    }
    const recipients = Array.from(selectedNotificationRecipients)
    if (recipients.length === 0) {
      setError('请至少选择一个通知账号')
      return
    }
    setNotificationSending(true)
    setError('')
    try {
      const result = await sendMeetingNotification(selectedMeetingId, content, recipients)
      setNotificationSendResult(result)
      setMeetings(previous => previous.map(item =>
        item.id === selectedMeetingId ? { ...item, notificationResultJson: JSON.stringify(result) } : item,
      ))
      flash(`通知发送完成：成功 ${result.sentCount} 个，失败 ${result.failedCount} 个`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '发送会议通知失败')
    } finally {
      setNotificationSending(false)
    }
  }

  const allRecipientsSelected = !!notificationPreview?.teamsRecipients.length
    && notificationPreview.teamsRecipients
      .map(recipientKey)
      .filter(Boolean)
      .every(recipient => selectedNotificationRecipients.has(recipient))

  return (
    <div className="meetings-root">
      <header className="meetings-topbar">
        <div>
          <h1 className="meetings-title">会议</h1>
        </div>
        <button className="si-pill-btn" type="button" onClick={() => { window.location.hash = ROUTES.HOME }}>
          返回同传
        </button>
      </header>

      <main className="meetings-main">
        <ErrorBanner message={error} onDismiss={() => setError('')} />
        <SuccessBanner message={success} />

        <section className="meetings-section meetings-workspace-section">
          <div className="meetings-section-header">
            <h2>会议工作台</h2>
            {loading && <span className="meetings-muted">加载中...</span>}
          </div>

          <div className="meetings-workspace-grid">
            <form
              className="meetings-create-panel"
              onSubmit={event => {
                event.preventDefault()
                void handleCreateMeeting()
              }}
            >
              <div className="meetings-panel-title">
                <strong>新建会议</strong>
                <span>名称必填，其余选填</span>
              </div>
              <label className="meetings-input-field">
                <span>会议名称</span>
                <input
                  value={newMeetingTitle}
                  onChange={event => setNewMeetingTitle(event.target.value)}
                  maxLength={120}
                  placeholder="例如：金融专项会议"
                />
              </label>
              <div className="meetings-form-split">
                <label className="meetings-input-field">
                  <span>会议时间（选填）</span>
                  <input
                    type="datetime-local"
                    value={newMeetingScheduledTime}
                    onChange={event => setNewMeetingScheduledTime(event.target.value)}
                  />
                </label>
                <label className="meetings-input-field">
                  <span>备注（选填）</span>
                  <input
                    value={newMeetingNote}
                    onChange={event => setNewMeetingNote(event.target.value)}
                    maxLength={240}
                    placeholder="议题、场次或负责人"
                  />
                </label>
              </div>
              <button
                className="meetings-primary-btn meetings-create-btn"
                type="submit"
                disabled={creatingMeeting || !newMeetingTitle.trim()}
              >
                {creatingMeeting ? '创建中...' : '创建会议'}
              </button>
            </form>

            <div className="meetings-select-panel">
              <div className="meetings-panel-title">
                <strong>选择会议</strong>
                <span>{meetings.length} 个会议</span>
              </div>
              <label className="meetings-input-field">
                <span>当前会议</span>
                <select
                  value={selectedMeetingId ?? ''}
                  onChange={event => handleMeetingSelect(event.target.value)}
                  disabled={loading}
                >
                  <option value="">请选择会议</option>
                  {meetings.map(meeting => (
                    <option key={meeting.id} value={meeting.id}>
                      {meeting.title}
                    </option>
                  ))}
                </select>
              </label>

              {selectedMeeting ? (
                <div className="meetings-selected-card">
                  <div>
                    <strong>{selectedMeeting.title}</strong>
                    <span>{selectedMeeting.scheduledTime || '未设置会议时间'}</span>
                  </div>
                  <div className="meetings-status-row">
                    <span>{meetingFiles.length} 个文件</span>
                    <span>{meetingHasExpected ? '已有应到名单' : '未导入名单'}</span>
                    <span>{meetingUrl.trim() ? '已填链接' : '链接选填'}</span>
                  </div>
                </div>
              ) : (
                <div className="meetings-selection-placeholder">
                  请选择已有会议，或在左侧创建一个新会议。
                </div>
              )}
            </div>
          </div>

          <div className="meetings-notice-grid meetings-optional-grid">
            <label className="meetings-link-field">
              <span>会议链接（选填）</span>
              <input
                value={meetingUrl}
                onChange={event => {
                  setMeetingUrl(event.target.value)
                  setNotificationPreview(null)
                  setNotificationContent('')
                  setNotificationSendResult(null)
                }}
                placeholder="https://teams.microsoft.com/l/meetup-join/..."
              />
            </label>

            <div className="meetings-notice-field">
              <span>会议通知（选填）</span>
              <div
                className={`meetings-upload meetings-notice-upload${noticeUploading ? ' is-uploading' : ''}`}
                onClick={() => !noticeUploading && noticeInputRef.current?.click()}
                onDragOver={event => event.preventDefault()}
                onDrop={event => {
                  event.preventDefault()
                  const file = event.dataTransfer.files[0]
                  if (file) void handleNoticeUpload(file)
                }}
              >
                <input
                  ref={noticeInputRef}
                  type="file"
                  accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                  hidden
                  onChange={event => {
                    const file = event.target.files?.[0]
                    event.target.value = ''
                    if (file) void handleNoticeUpload(file)
                  }}
                />
                <div className="meetings-upload-icon">DOC</div>
                <div className="meetings-upload-text">
                  <strong>
                    {noticeUploading
                      ? '上传中...'
                      : noticePreviewing
                        ? '解析中...'
                        : '上传会议通知'}
                  </strong>
                  <span>PDF、Word（.doc/.docx），上传后自动生成通知</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className="meetings-section">
          <div className="meetings-section-header">
            <h2>会议通知</h2>
            {selectedMeeting && (
              <span className="meetings-muted">
                {meetingHasExpected ? '已保存应到名单' : selectedMeeting.title}
              </span>
            )}
          </div>

          {!notificationPreview && (
            <div className="meetings-empty">
              需要发送通知时，上传会议通知并填写会议链接即可生成通知名单与内容
            </div>
          )}

          {notificationPreview && (
            <div className="meetings-notification-preview">
              <div className="meetings-preview-stats">
                <span>应到 {notificationPreview.participantNames.length}</span>
                <span>Teams {notificationPreview.teamsRecipients.length}</span>
                <span>未匹配 {notificationPreview.unmatched.length}</span>
                <span>非 Teams {notificationPreview.nonTeamsSkipped.length}</span>
              </div>

              <div className="meetings-recipient-toolbar">
                <strong>通知账号</strong>
                <button
                  type="button"
                  className="meetings-secondary-btn"
                  onClick={() => setAllNotificationRecipients(!allRecipientsSelected)}
                  disabled={notificationPreview.teamsRecipients.length === 0}
                >
                  {allRecipientsSelected ? '清空' : '全选'}
                </button>
              </div>

              {notificationPreview.teamsRecipients.length === 0 ? (
                <div className="meetings-empty">暂无可通知账号</div>
              ) : (
                <div className="meetings-recipient-list">
                  {notificationPreview.teamsRecipients.map(recipient => {
                    const key = recipientKey(recipient)
                    return (
                      <label key={`${recipient.scheduleName}-${key}`} className="meetings-recipient-row">
                        <input
                          type="checkbox"
                          checked={selectedNotificationRecipients.has(key)}
                          onChange={() => toggleNotificationRecipient(key)}
                        />
                        <span>{recipient.scheduleName}</span>
                        <span>{recipient.accountName}</span>
                        <span>{recipient.email}</span>
                      </label>
                    )
                  })}
                </div>
              )}

              {(notificationPreview.unmatched.length > 0 || notificationPreview.nonTeamsSkipped.length > 0) && (
                <div className="meetings-notice-warnings">
                  {notificationPreview.unmatched.map(item => (
                    <span key={`unmatched-${item}`}>未匹配：{item}</span>
                  ))}
                  {notificationPreview.nonTeamsSkipped.map(item => (
                    <span key={`nonteams-${item}`}>非 Teams：{item}</span>
                  ))}
                </div>
              )}

              <label className="meetings-notification-content">
                <span>通知内容</span>
                <textarea
                  value={notificationContent}
                  onChange={event => setNotificationContent(event.target.value)}
                  rows={9}
                />
              </label>

              <div className="meetings-send-row">
                <button
                  className="meetings-primary-btn"
                  type="button"
                  onClick={() => void handleSendNotification()}
                  disabled={notificationSending || selectedNotificationRecipients.size === 0 || !notificationContent.trim()}
                >
                  {notificationSending ? '发送中...' : '发送通知'}
                </button>
              </div>

              {notificationSendResult && (
                <div className="meetings-delivery-result">
                  <div>
                    <strong>通知结果</strong>
                    <span>
                      成功 {notificationSendResult.sentCount} 个，失败 {notificationSendResult.failedCount} 个
                    </span>
                  </div>
                  {notificationSendResult.successfulRecipients?.length > 0 && (
                    <p>成功：{notificationSendResult.successfulRecipients.join('、')}</p>
                  )}
                  {notificationSendResult.failedRecipients?.length > 0 && (
                    <p>失败：{notificationSendResult.failedRecipients.join('、')}</p>
                  )}
                  {notificationSendResult.error && <p>错误：{notificationSendResult.error}</p>}
                </div>
              )}
            </div>
          )}
        </section>

        <section className="meetings-section">
          <div className="meetings-section-header">
            <h2>会议文件</h2>
            {selectedMeeting && <span className="meetings-muted">{selectedMeeting.title}</span>}
          </div>

          {!selectedMeetingId ? (
            <div className="meetings-empty">请先选择或创建会议</div>
          ) : (
            <>
              <div
                className={`meetings-upload${uploading ? ' is-uploading' : ''}`}
                onClick={() => !uploading && fileInputRef.current?.click()}
                onDragOver={event => event.preventDefault()}
                onDrop={event => {
                  event.preventDefault()
                  const file = event.dataTransfer.files[0]
                  if (file) void handleUploadFile(file)
                }}
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                  hidden
                  onChange={event => {
                    const file = event.target.files?.[0]
                    event.target.value = ''
                    if (file) void handleUploadFile(file)
                  }}
                />
                <div className="meetings-upload-icon">PDF</div>
                <div>
                  <strong>{uploading ? '上传中...' : '上传汇报文件'}</strong>
                  <span>PDF、Word（.doc/.docx）</span>
                </div>
              </div>

              {meetingFiles.length === 0 ? (
                <div className="meetings-empty">暂无会议文件</div>
              ) : (
                <div className="meetings-file-list">
                  {meetingFiles.map(file => (
                    <div key={file.id} className="meetings-file-row">
                      <div className="meetings-file-meta">
                        <span className="meetings-file-type">{(file.fileType || reportExtension(file.fileName)).toUpperCase()}</span>
                        <div>
                          <strong>{file.fileName}</strong>
                          <span>{file.createTime || '刚刚上传'}</span>
                        </div>
                      </div>
                      <button
                        type="button"
                        className="meetings-delete-btn"
                        onClick={() => void handleDeleteFile(file.id)}
                        disabled={deletingFileId === file.id}
                      >
                        {deletingFileId === file.id ? '删除中...' : '删除'}
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </section>
      </main>
    </div>
  )
}
