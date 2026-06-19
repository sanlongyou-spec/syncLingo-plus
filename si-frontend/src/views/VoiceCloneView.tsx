import { useEffect, useRef, useState } from 'react'
import { cloneUserVoice, deleteUserVoice, listUserVoices } from '../api'
import { ROUTES, VOICE_CLONE } from '../constants'
import type { UserVoice } from '../types'
import { VOICE_CLONE_LANGUAGE_OPTIONS } from '../types'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './VoiceCloneView.css'

type AudioSource = 'system' | 'microphone'

const RESULT_OK_CODE = 200
const SECOND_MS = 1000

const recorderMimeType = () => {
  if (typeof MediaRecorder === 'undefined') return ''
  const candidates = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
  ]
  return candidates.find(candidate => MediaRecorder.isTypeSupported(candidate)) || ''
}

const formatSeconds = (milliseconds: number) =>
  `${Math.max(0, Math.floor(milliseconds / SECOND_MS))}s`

export default function VoiceCloneView() {
  const [voices, setVoices] = useState<UserVoice[]>([])
  const [source, setSource] = useState<AudioSource>('system')
  const [language, setLanguage] = useState<string>(VOICE_CLONE_LANGUAGE_OPTIONS[0].value)
  const [voiceName, setVoiceName] = useState('')
  const [recording, setRecording] = useState(false)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [recordedBlob, setRecordedBlob] = useState<Blob | null>(null)
  const [recordedUrl, setRecordedUrl] = useState('')
  const [loadingVoices, setLoadingVoices] = useState(true)
  const [cloning, setCloning] = useState(false)
  const [deletingVoiceId, setDeletingVoiceId] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const streamRef = useRef<MediaStream | null>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const timerRef = useRef<number | null>(null)
  const startedAtRef = useRef(0)

  const flash = (message: string) => {
    setSuccess(message)
    window.setTimeout(() => setSuccess(''), 2200)
  }

  const loadVoices = async () => {
    setLoadingVoices(true)
    setError('')
    try {
      const result = await listUserVoices()
      if (result.code !== RESULT_OK_CODE) {
        throw new Error(result.message || '加载音色失败')
      }
      setVoices(result.data || [])
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载音色失败')
    } finally {
      setLoadingVoices(false)
    }
  }

  useEffect(() => {
    void loadVoices()
    return () => {
      stopTracks()
      if (timerRef.current) window.clearInterval(timerRef.current)
      if (recordedUrl) URL.revokeObjectURL(recordedUrl)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const stopTracks = () => {
    streamRef.current?.getTracks().forEach(track => track.stop())
    streamRef.current = null
  }

  const clearRecording = () => {
    setRecordedBlob(null)
    if (recordedUrl) URL.revokeObjectURL(recordedUrl)
    setRecordedUrl('')
    setElapsedMs(0)
  }

  const startTimer = () => {
    startedAtRef.current = Date.now()
    timerRef.current = window.setInterval(() => {
      setElapsedMs(Date.now() - startedAtRef.current)
    }, 250)
  }

  const startRecording = async () => {
    setError('')
    clearRecording()
    chunksRef.current = []
    try {
      const stream = source === 'system'
        ? await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })
        : await navigator.mediaDevices.getUserMedia({ audio: true })
      if (stream.getAudioTracks().length === 0) {
        stream.getTracks().forEach(track => track.stop())
        throw new Error('未获取到音频轨道')
      }
      streamRef.current = stream
      const audioOnlyStream = new MediaStream(stream.getAudioTracks())
      const mimeType = recorderMimeType()
      const recorder = new MediaRecorder(audioOnlyStream, mimeType ? { mimeType } : undefined)
      recorderRef.current = recorder
      recorder.ondataavailable = event => {
        if (event.data.size > 0) chunksRef.current.push(event.data)
      }
      recorder.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: mimeType || 'audio/webm' })
        setRecordedBlob(blob)
        setRecordedUrl(URL.createObjectURL(blob))
        stopTracks()
        if (timerRef.current) {
          window.clearInterval(timerRef.current)
          timerRef.current = null
        }
        setElapsedMs(Date.now() - startedAtRef.current)
      }
      recorder.start(250)
      setRecording(true)
      startTimer()
    } catch (err) {
      stopTracks()
      setRecording(false)
      setError(err instanceof Error ? err.message : '开始录音失败')
    }
  }

  const stopRecording = () => {
    if (recorderRef.current && recorderRef.current.state !== 'inactive') {
      recorderRef.current.stop()
    }
    setRecording(false)
  }

  const submitClone = async () => {
    const name = voiceName.trim()
    if (!name) {
      setError('请输入音色名称')
      return
    }
    if (!recordedBlob) {
      setError('请先录制音频')
      return
    }
    setCloning(true)
    setError('')
    try {
      const durationSeconds = Math.max(1, Math.round(elapsedMs / SECOND_MS))
      const result = await cloneUserVoice(recordedBlob, name, language, durationSeconds)
      if (result.code !== RESULT_OK_CODE) {
        throw new Error(result.message || '克隆音色失败')
      }
      setVoiceName('')
      clearRecording()
      flash('音色已创建')
      await loadVoices()
    } catch (err) {
      setError(err instanceof Error ? err.message : '克隆音色失败')
    } finally {
      setCloning(false)
    }
  }

  const handleDelete = async (voiceId: string) => {
    setDeletingVoiceId(voiceId)
    setError('')
    try {
      const result = await deleteUserVoice(voiceId)
      if (result.code !== RESULT_OK_CODE) {
        throw new Error(result.message || '删除音色失败')
      }
      setVoices(previous => previous.filter(voice => voice.voiceId !== voiceId))
      flash('音色已删除')
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除音色失败')
    } finally {
      setDeletingVoiceId('')
    }
  }

  return (
    <div className="voice-root">
      <header className="voice-topbar">
        <h1>音色</h1>
        <button className="si-pill-btn" type="button" onClick={() => { window.location.hash = ROUTES.HOME }}>
          返回同传
        </button>
      </header>

      <main className="voice-main">
        <ErrorBanner message={error} onDismiss={() => setError('')} />
        <SuccessBanner message={success} />

        <section className="voice-section">
          <div className="voice-section-header">
            <h2>创建音色</h2>
            <span>{recording ? `录制中 ${formatSeconds(elapsedMs)}` : recordedBlob ? `已录制 ${formatSeconds(elapsedMs)}` : '待录制'}</span>
          </div>

          <div className="voice-controls">
            <label>
              <span>音频来源</span>
              <select value={source} onChange={event => setSource(event.target.value as AudioSource)} disabled={recording || cloning}>
                <option value="system">系统音频</option>
                <option value="microphone">麦克风</option>
              </select>
            </label>
            <label>
              <span>语言</span>
              <select value={language} onChange={event => setLanguage(event.target.value)} disabled={recording || cloning}>
                {VOICE_CLONE_LANGUAGE_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <label className="voice-name-field">
              <span>音色名称</span>
              <input
                value={voiceName}
                onChange={event => setVoiceName(event.target.value)}
                maxLength={VOICE_CLONE.MAX_NAME_LENGTH}
                disabled={cloning}
              />
            </label>
          </div>

          <div className="voice-actions">
            {!recording ? (
              <button className="voice-primary-btn" type="button" onClick={() => void startRecording()} disabled={cloning}>
                开始录音
              </button>
            ) : (
              <button className="voice-stop-btn" type="button" onClick={stopRecording}>
                停止录音
              </button>
            )}
            <button
              className="voice-primary-btn"
              type="button"
              onClick={() => void submitClone()}
              disabled={recording || cloning || !recordedBlob || !voiceName.trim()}
            >
              {cloning ? '创建中...' : '创建音色'}
            </button>
          </div>

          {recordedUrl && (
            <audio className="voice-preview" controls src={recordedUrl} />
          )}
        </section>

        <section className="voice-section">
          <div className="voice-section-header">
            <h2>已创建音色</h2>
            <span>{loadingVoices ? '加载中...' : `${voices.length} 个`}</span>
          </div>

          {voices.length === 0 ? (
            <div className="voice-empty">暂无音色</div>
          ) : (
            <div className="voice-list">
              {voices.map(voice => (
                <div className="voice-row" key={voice.voiceId}>
                  <div>
                    <strong>{voice.voiceName}</strong>
                    <span>{voice.createTime || voice.voiceId}</span>
                  </div>
                  <button
                    type="button"
                    className="voice-delete-btn"
                    onClick={() => void handleDelete(voice.voiceId)}
                    disabled={deletingVoiceId === voice.voiceId}
                  >
                    {deletingVoiceId === voice.voiceId ? '删除中...' : '删除'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  )
}
