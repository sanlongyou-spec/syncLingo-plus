import { useEffect, useRef, useState } from 'react'
import {
  createMeeting,
  deleteMeetingFile,
  getMeetings,
  uploadFileToMeeting,
} from '../api'
import { ROUTES, MEETINGS_STORAGE_KEYS } from '../constants'
import type { Meeting, MeetingFile } from '../types'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './MeetingsView.css'

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

export default function MeetingsView() {
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)
  const [meetingName, setMeetingName] = useState('')
  const [meetingFiles, setMeetingFiles] = useState<MeetingFile[]>([])
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [deletingFileId, setDeletingFileId] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  const flash = (message: string) => {
    setSuccess(message)
    window.setTimeout(() => setSuccess(''), 2200)
  }

  const applyMeetingSelection = (id: number | null, source: Meeting[] = meetings) => {
    setSelectedMeetingId(id)
    if (id) {
      localStorage.setItem(MEETINGS_STORAGE_KEYS.LAST_SELECTED_MEETING_ID, String(id))
    } else {
      localStorage.removeItem(MEETINGS_STORAGE_KEYS.LAST_SELECTED_MEETING_ID)
    }
    const meeting = id ? source.find(item => item.id === id) : null
    setMeetingName(meeting?.title || '')
    setMeetingFiles(meeting?.files || [])
  }

  const loadMeetings = async () => {
    setLoading(true)
    setError('')
    try {
      const result = await getMeetings()
      const list = result.data || []
      setMeetings(list)
      const saved = Number(localStorage.getItem(MEETINGS_STORAGE_KEYS.LAST_SELECTED_MEETING_ID))
      const nextId = Number.isSafeInteger(saved) && list.some(item => item.id === saved)
        ? saved
        : list[0]?.id ?? null
      applyMeetingSelection(nextId, list)
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

  const handleMeetingSelect = (value: string) => {
    applyMeetingSelection(value ? Number(value) : null)
  }

  const handleCreateMeeting = async () => {
    const title = meetingName.trim()
    if (!title) {
      setError('请先输入会议名称')
      return
    }
    setCreating(true)
    setError('')
    try {
      const result = await createMeeting({ title })
      const created = result.data
      const nextMeetings = [created, ...meetings.filter(item => item.id !== created.id)]
      setMeetings(nextMeetings)
      applyMeetingSelection(created.id, nextMeetings)
      flash('会议已创建')
    } catch (err) {
      setError(err instanceof Error ? err.message : '新建会议失败')
    } finally {
      setCreating(false)
    }
  }

  const handleUploadFile = async (file: File) => {
    if (!selectedMeetingId) {
      setError('请先选择或新建会议')
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

  const selectedMeeting = selectedMeetingId
    ? meetings.find(meeting => meeting.id === selectedMeetingId)
    : null

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

        <section className="meetings-section">
          <div className="meetings-section-header">
            <h2>会议信息</h2>
            {loading && <span className="meetings-muted">加载中...</span>}
          </div>
          <div className="meetings-form-row">
            <label className="meetings-field">
              <span>所属会议</span>
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
          </div>

          <div className="meetings-create-row">
            <input
              value={meetingName}
              onChange={event => setMeetingName(event.target.value)}
              placeholder="输入会议名称新建会议"
              maxLength={120}
            />
            <button
              className="meetings-primary-btn"
              type="button"
              onClick={handleCreateMeeting}
              disabled={creating || !meetingName.trim()}
            >
              {creating ? '新建中...' : '新建会议'}
            </button>
          </div>
        </section>

        <section className="meetings-section">
          <div className="meetings-section-header">
            <h2>会议文件</h2>
            {selectedMeeting && <span className="meetings-muted">{selectedMeeting.title}</span>}
          </div>

          {!selectedMeetingId ? (
            <div className="meetings-empty">请先选择或新建会议</div>
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
                  <span>支持 PDF、Word（.doc/.docx）</span>
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
