import { useCallback, useEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import {
  getAsrHotwords,
  getInterpretationStatus,
  getMeetings,
  getUserLanguagePreference,
  listUserVoices,
  logout,
  saveUserLanguagePreference,
  saveInterpretationResult,
  startInterpretation,
  stopInterpretation,
  mintChannelShareToken,
} from '../api'
import { AUDIO_DEFAULTS } from '../api/constants'
import { LANGUAGE, ROUTES, STORAGE_KEYS } from '../constants'
import FontSizeControl from '../components/FontSizeControl'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { VoiceMeeterOutput } from '../lib/voiceMeeterOutput'
import { useSmartAutoScroll } from '../lib/useSmartAutoScroll'
import { useTranscriptFontScale } from '../lib/useTranscriptFontScale'
import { AsrWebSocket } from '../lib/websocket'
import { LANGUAGE_OPTIONS } from '../types'
import type { Meeting, UserVoice, WsMessage } from '../types'
import './InterpretationView.css'

interface TranscriptTranslation {
  text: string
  targetLanguage: string
}

interface TranscriptItem {
  id: string
  source: string
  speakerId: string
  speakerName?: string
  translations: TranscriptTranslation[]
  timestamp: number
}


function IconPlay() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path d="M8 5v14l11-7L8 5z" fill="currentColor" />
    </svg>
  )
}

function IconStop() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path d="M7 7h10v10H7V7z" fill="currentColor" />
    </svg>
  )
}

function IconHeadset() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 3a7 7 0 00-7 7v3a2 2 0 002 2h1v-6H6a5 5 0 0110 0v6h-2v2h3a2 2 0 002-2v-3a7 7 0 00-7-7z"
        fill="currentColor"
      />
    </svg>
  )
}

const normalizeVoiceCode = (code?: string | null) => {
  const trimmed = code?.trim()
  if (!trimmed) return ''
  const lower = trimmed.toLowerCase()
  if (lower === 'undefined' || lower === 'null') return ''
  return trimmed
}

const isUnknownSpeakerId = (speakerId?: string | null) =>
  speakerId?.trim().toLowerCase() === 'unknown'
const RUNNING_STATUS = 'running'

const mappedSpeakerName = (speakerId: string | undefined, speakerNameMap: Record<string, string>) =>
  speakerId && !isUnknownSpeakerId(speakerId) ? speakerNameMap[speakerId] : ''

export default function InterpretationView() {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [isRunning, setIsRunning] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const [transcripts, setTranscripts] = useState<TranscriptItem[]>([])
  const [currentSource, setCurrentSource] = useState('')
  const [currentTranslated, setCurrentTranslated] = useState('')
  const [voices, setVoices] = useState<UserVoice[]>([])
  const [selectedVoiceId, setSelectedVoiceId] = useState('')
  const [error, setError] = useState('')
  const [shareHint, setShareHint] = useState('')
  const [detectedLang, setDetectedLang] = useState('')
  const [currentSpeakerId, setCurrentSpeakerId] = useState('')
  const [speakerNameMap, setSpeakerNameMap] = useState<Record<string, string>>({})
  const [selectedHotwordIds, setSelectedHotwordIds] = useState<number[]>([])
  const [enabledLanguages, setEnabledLanguages] = useState<string[]>([LANGUAGE.ZH_CN, LANGUAGE.ID_ID])

  // ── Meeting & speaker summary ─────────────────────────────
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)

  const wsRef = useRef<AsrWebSocket | null>(null)
  const audioRef = useRef<AudioCapture | null>(null)
  const voiceMeeterRef = useRef<VoiceMeeterOutput | null>(null)
  /** 每个 TTS 任务已播放到的 chunkIndex，用于丢弃重复块并告警疑似缺块。 */
  const ttsChunkIndexByTaskRef = useRef<Map<string, number>>(new Map())
  const sessionIdRef = useRef<string | null>(null)
  const detectedLangRef = useRef('')
  const currentSpeakerIdRef = useRef('')
  const selectedVoiceIdRef = useRef('')
  const {
    scrollRef: bodyRef,
    isPaused: isTranscriptAutoScrollPaused,
    scrollToBottom: scrollTranscriptToBottom,
  } = useSmartAutoScroll<HTMLDivElement>([transcripts, currentSource])
  const { scale: transcriptFontScale, setScale: setTranscriptFontScale } = useTranscriptFontScale()

  const speakerNameMapRef = useRef<Record<string, string>>({})

  const refreshVoices = useCallback(() => {
    listUserVoices()
      .then(res => setVoices(res.data || []))
      .catch((err: unknown) => console.warn('[InterpretationView] listUserVoices failed:', err))
  }, [])

  useEffect(() => { detectedLangRef.current = detectedLang }, [detectedLang])
  useEffect(() => { sessionIdRef.current = sessionId }, [sessionId])
  useEffect(() => { currentSpeakerIdRef.current = currentSpeakerId }, [currentSpeakerId])
  useEffect(() => { selectedVoiceIdRef.current = selectedVoiceId }, [selectedVoiceId])
  useEffect(() => { speakerNameMapRef.current = speakerNameMap }, [speakerNameMap])
  useEffect(() => {
    getMeetings()
      .then(res => setMeetings(res.data || []))
      .catch((err: unknown) => console.warn('[InterpretationView] getMeetings failed:', err))

    getAsrHotwords('', true)
      .then(res => {
        const items = res.data || []
        setSelectedHotwordIds(items.map(item => item.id).filter((id): id is number => typeof id === 'number'))
      })
      .catch((err: unknown) => console.warn('[InterpretationView] getAsrHotwords failed:', err))

    getUserLanguagePreference()
      .then(res => {
        // 中文/印尼语必选：无论用户偏好如何，恒含 zh/id。
        setEnabledLanguages(Array.from(new Set([LANGUAGE.ZH_CN, LANGUAGE.ID_ID, ...(res.data?.enabledLanguages ?? [])])))
      })
      .catch((err: unknown) => console.warn('[InterpretationView] getUserLanguagePreference failed:', err))

    refreshVoices()

    return () => {
      wsRef.current?.close()
      audioRef.current?.stop()
      audioRef.current = null
      voiceMeeterRef.current?.stop()
      voiceMeeterRef.current = null
    }
  }, [])

  // Refresh meetings whenever an overlay is closed and user returns here
  useEffect(() => {
    const refreshMeetings = () => {
      getMeetings()
        .then(res => {
          const list = res.data || []
          setMeetings(list)
          // If previously selected meeting was deleted, clear the selection
          setSelectedMeetingId(prev =>
            prev !== null && !list.some(m => m.id === prev) ? null : prev
          )
        })
        .catch((err: unknown) => console.warn('[InterpretationView] getMeetings refresh failed:', err))
      refreshVoices()
    }
    window.addEventListener('hashchange', refreshMeetings)
    return () => window.removeEventListener('hashchange', refreshMeetings)
  }, [])

  const resolveSpeakerName = useCallback((speakerId?: string, speakerName?: string | null) => {
    const displayName = speakerName?.trim()
    if (displayName) return displayName
    return mappedSpeakerName(speakerId, speakerNameMapRef.current)
  }, [])

  const rememberSpeakerName = useCallback((speakerId?: string, speakerName?: string | null) => {
    const displayName = speakerName?.trim()
    if (!speakerId || !displayName || isUnknownSpeakerId(speakerId)) return
    setSpeakerNameMap(prev => prev[speakerId] === displayName ? prev : { ...prev, [speakerId]: displayName })
  }, [refreshVoices])

  const rememberCurrentSpeaker = useCallback((speakerId?: string) => {
    const normalizedSpeakerId = normalizeVoiceCode(speakerId)
    if (!normalizedSpeakerId || isUnknownSpeakerId(normalizedSpeakerId)) return
    const previousSpeakerId = currentSpeakerIdRef.current
    if (previousSpeakerId && previousSpeakerId !== normalizedSpeakerId && selectedVoiceIdRef.current) {
      setSelectedVoiceId('')
      selectedVoiceIdRef.current = ''
      const sid = sessionIdRef.current
      if (sid) {
        wsRef.current?.setVoice(sid, normalizedSpeakerId, '')
      }
    }
    setCurrentSpeakerId(normalizedSpeakerId)
    currentSpeakerIdRef.current = normalizedSpeakerId
  }, [refreshVoices])

  const handleManualVoiceChange = (voiceId: string) => {
    setSelectedVoiceId(voiceId)
    selectedVoiceIdRef.current = voiceId
    const sid = sessionIdRef.current
    const speakerId = currentSpeakerIdRef.current
    if (!sid || !speakerId) {
      if (voiceId) setError('请先等待系统识别到当前汇报人')
      return
    }
    wsRef.current?.setVoice(sid, speakerId, voiceId)
  }

  const handleWsMessage = useCallback((msg: WsMessage) => {
    const messageSpeakerId =
      normalizeVoiceCode(msg.speakerId) ||
      normalizeVoiceCode(msg.voiceId) ||
      normalizeVoiceCode(currentSpeakerIdRef.current)
    const messageSpeakerName = resolveSpeakerName(messageSpeakerId, msg.speakerName)

    switch (msg.type) {
      case 'recognizing':
        setCurrentSource(msg.text || '')
        if (messageSpeakerId) {
          rememberCurrentSpeaker(messageSpeakerId)
        }
        rememberSpeakerName(messageSpeakerId, messageSpeakerName)
        if (msg.language) {
          detectedLangRef.current = msg.language
          setDetectedLang(msg.language)
        }
        break
      case 'recognized':
        if (msg.text) {
          rememberCurrentSpeaker(messageSpeakerId)
          rememberSpeakerName(messageSpeakerId, messageSpeakerName)
          setTranscripts(prev => [
            ...prev,
            {
              id: `${Date.now()}-${Math.random()}`,
              source: msg.text || '',
              speakerId: messageSpeakerId,
              speakerName: messageSpeakerName,
              translations: msg.translatedText
                ? [{ text: msg.translatedText, targetLanguage: msg.targetLanguage || '' }]
                : [],
              timestamp: Date.now(),
            },
          ])
          setCurrentSource('')
          setCurrentTranslated('')
        }
        break
      case 'translated': {
        const translatedText = msg.translatedText || ''
        const originalText = msg.text || ''
        const targetLanguage = msg.targetLanguage || ''
        setCurrentTranslated(translatedText)
        if (sessionIdRef.current && originalText && translatedText) {
          void saveInterpretationResult({
            sessionId: sessionIdRef.current,
            sourceText: originalText,
            translatedText,
            sourceLang: msg.sourceLang || detectedLangRef.current || msg.language || undefined,
            targetLang: targetLanguage,
            speakerId: msg.speakerId || undefined,
            speakerName: messageSpeakerName || undefined,
          }).catch(err => console.warn('[InterpretationView] saveInterpretationResult failed:', err))
        }
        if (!translatedText) break
        setTranscripts(prev => {
          const mergeSpeakerInfo = (item: TranscriptItem): TranscriptItem => ({
            ...item,
            speakerId: item.speakerId || messageSpeakerId,
            speakerName: messageSpeakerName || item.speakerName,
          })
          const upsertTranslation = (item: TranscriptItem): TranscriptItem => {
            const matchedTranslationIndex = item.translations.findIndex(itemTranslation =>
              itemTranslation.targetLanguage === targetLanguage,
            )
            if (matchedTranslationIndex >= 0) {
              return {
                ...item,
                translations: item.translations.map((itemTranslation, index) =>
                  index === matchedTranslationIndex ? { text: translatedText, targetLanguage } : itemTranslation,
                ),
              }
            }
            return { ...item, translations: [...item.translations, { text: translatedText, targetLanguage }] }
          }
          const matchedIndex = originalText
            ? [...prev].reverse().findIndex(item => item.source === originalText)
            : -1
          if (matchedIndex >= 0) {
            const index = prev.length - 1 - matchedIndex
            return prev.map((item, itemIndex) => itemIndex === index ? upsertTranslation(mergeSpeakerInfo(item)) : item)
          }
          if (prev.length > 0 && prev[prev.length - 1].translations.length === 0) {
            return [...prev.slice(0, -1), upsertTranslation(mergeSpeakerInfo(prev[prev.length - 1]))]
          }
          if (!originalText) return prev
          return [
            ...prev,
            {
              id: `${Date.now()}-${Math.random()}`,
              source: originalText,
              speakerId: messageSpeakerId,
              speakerName: messageSpeakerName,
              translations: [{ text: translatedText, targetLanguage }],
              timestamp: Date.now(),
            },
          ]
        })
        break
      }
      case 'tts_audio': {
        if (msg.audioBase64 && msg.targetLanguage) {
          // 按 ttsTaskId+chunkIndex 去重并发现疑似缺块（顺序播放靠 VoiceMeeterOutput 内部排程）。
          if (msg.ttsTaskId && typeof msg.chunkIndex === 'number') {
            const lastIndex = ttsChunkIndexByTaskRef.current.get(msg.ttsTaskId) ?? -1
            if (msg.chunkIndex === 0) {
              console.log('[tts route] taskId=%s targetLanguage=%s', msg.ttsTaskId, msg.targetLanguage)
            }
            if (msg.chunkIndex <= lastIndex) break
            if (msg.chunkIndex > lastIndex + 1) {
              console.warn('[InterpretationView] possible missing chunk, taskId=%s expected=%d got=%d',
                msg.ttsTaskId, lastIndex + 1, msg.chunkIndex)
            }
            ttsChunkIndexByTaskRef.current.set(msg.ttsTaskId, msg.chunkIndex)
          }
          const binary = atob(msg.audioBase64)
          const bytes = new Uint8Array(binary.length)
          for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
          voiceMeeterRef.current?.play(
            new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.byteLength / 2)),
            msg.targetLanguage,
          )
        }
        break
      }
      case 'started':
        setIsRunning(true)
        setError('')
        setCurrentSpeakerId('')
        setSelectedVoiceId('')
        setSpeakerNameMap({})
        setDetectedLang(msg.language || '')
        currentSpeakerIdRef.current = ''
        selectedVoiceIdRef.current = ''
        speakerNameMapRef.current = {}
        detectedLangRef.current = msg.language || ''
        break
      case 'stopped':
        setIsRunning(false)
        break
      case 'error':
        setError(msg.message || '发生错误')
        break
    }
  }, [rememberSpeakerName, resolveSpeakerName])

  const resumeStoredSessionId = async () => {
    const storedSessionId = localStorage.getItem(STORAGE_KEYS.CURRENT_SESSION_ID)
    if (!storedSessionId) return null
    try {
      const status = await getInterpretationStatus(storedSessionId)
      if (status.code === 200 && status.data?.status?.toLowerCase() === RUNNING_STATUS) {
        return storedSessionId
      }
    } catch (err) {
      console.warn('[InterpretationView] resume status check failed:', err)
    }
    localStorage.removeItem(STORAGE_KEYS.CURRENT_SESSION_ID)
    return null
  }

  const connectSession = async (sid: string) => {
    setSessionId(sid)
    sessionIdRef.current = sid
    localStorage.setItem(STORAGE_KEYS.CURRENT_SESSION_ID, sid)

    const ws = new AsrWebSocket()
    wsRef.current = ws
    await ws.connect(sid)
    ws.onMessage(handleWsMessage)
    ws.start({ sessionId: sid, sourceLang: LANGUAGE.AUTO, targetLang: LANGUAGE.AUTO })

    // 译文出口就绪门禁：启动前必须确认 VoiceMeeter 输出设备就绪，否则阻止启动（绝不回退默认扬声器）。
    const voiceMeeter = new VoiceMeeterOutput()
    voiceMeeterRef.current = voiceMeeter
    ttsChunkIndexByTaskRef.current.clear()
    await voiceMeeter.init()
    await voiceMeeter.applySinks()
    if (!voiceMeeter.isReady()) {
      voiceMeeter.stop()
      voiceMeeterRef.current = null
      throw new Error('未检测到就绪的 VoiceMeeter 输出设备（中文需「VoiceMeeter Input」、印尼语需「VoiceMeeter Aux Input」）。请先安装并启动 VoiceMeeter，授予浏览器音频设备权限后再开始。')
    }

    const audio = new AudioCapture({
      sampleRate: AUDIO_DEFAULTS.SAMPLE_RATE,
      onData: pcm => ws.sendAudio(sid, pcmToBase64(pcm)),
    })
    audioRef.current = audio
    await audio.start()
  }

  const startSession = async () => {
    const resumedSessionId = await resumeStoredSessionId()
    if (resumedSessionId) {
      setError('')
      setIsLoading(true)
      try {
        await connectSession(resumedSessionId)
        setShareHint('已接回共享中的同传')
        window.setTimeout(() => setShareHint(''), 2200)
      } catch (err: unknown) {
        setError(err instanceof Error ? err.message : '启动失败')
      } finally {
        setIsLoading(false)
      }
      return
    }
    if (!selectedMeetingId) {
      setError('请先在"会议"页面新建会议，并在此选择关联会议')
      return
    }
    if (enabledLanguages.length < 2) {
      setError('请至少勾选两种翻译语种')
      return
    }
    setError('')
    setIsLoading(true)
    try {
      const selectedMeeting = meetings.find(m => m.id === selectedMeetingId)
      const sessionTitle = selectedMeeting?.title

      const res = await startInterpretation({
        sourceLang: LANGUAGE.AUTO,
        targetLang: LANGUAGE.AUTO,
        title: sessionTitle,
        hotwordIds: selectedHotwordIds,
        enabledLanguages,
        meetingId: selectedMeetingId,
      })
      const sid = res.data
      await connectSession(sid)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '启动失败')
    } finally {
      setIsLoading(false)
    }
  }

  const stopSession = async () => {
    if (!sessionId) return
    const sid = sessionId
    wsRef.current?.stop(sid)
    audioRef.current?.stop()
    audioRef.current = null
    voiceMeeterRef.current?.stop()
    voiceMeeterRef.current = null
    ttsChunkIndexByTaskRef.current.clear()
    await stopInterpretation(sid).then(res => {
      const warning = res?.data?.budgetWarning as string | undefined
      if (warning) {
        setTimeout(() => alert(`⚠️ 预算提醒：${warning}`), 300)
      }
    }).catch((err: unknown) => {
      console.warn('[InterpretationView] stopInterpretation failed:', err)
    })
    wsRef.current?.close()
    wsRef.current = null
    setSessionId(null)
    sessionIdRef.current = null
    localStorage.removeItem(STORAGE_KEYS.CURRENT_SESSION_ID)
    setIsRunning(false)
    setCurrentSource('')
    setCurrentTranslated('')
    setDetectedLang('')
    setCurrentSpeakerId('')
    setSelectedVoiceId('')
    currentSpeakerIdRef.current = ''
    selectedVoiceIdRef.current = ''
  }

  const voiceCodeForDisplay = (speakerId?: string) => {
    const code = normalizeVoiceCode(speakerId)
    if (!code || isUnknownSpeakerId(code)) return ''
    return code.length <= 18 ? code : `${code.slice(0, 8)}...${code.slice(-6)}`
  }

  const renderVoiceBadge = (speakerId?: string, className = 'si-tri-line-lang-badge') => {
    const code = normalizeVoiceCode(speakerId)
    const displayCode = voiceCodeForDisplay(code)
    if (!displayCode) return null
    return (
      <span className={className} title={code}>
        {displayCode}
      </span>
    )
  }

  // P4:复用已签发的频道令牌,避免每次复制都新建令牌。
  const channelShareTokenRef = useRef<string>('')

  const copyShareLink = async () => {
    try {
      if (!channelShareTokenRef.current) {
        const res = await mintChannelShareToken()
        if (res.code !== 200 || !res.data?.token) {
          setShareHint('生成失败')
          return
        }
        channelShareTokenRef.current = res.data.token
      }
      const url = `${window.location.origin}${window.location.pathname}#/share/token/${channelShareTokenRef.current}`
      await navigator.clipboard.writeText(url)
      setShareHint('已复制')
    } catch {
      setShareHint('复制失败')
    } finally {
      window.setTimeout(() => setShareHint(''), 2000)
    }
  }


  const handleLogout = async () => {
    setLogoutBusy(true)
    try {
      await logout()
    } finally {
      setLogoutBusy(false)
      window.location.hash = ROUTES.LOGIN
    }
  }

  return (
    <div className="si-root">
      <aside className="si-hover-sidebar" aria-label="工具侧边栏">
        <div className="si-hover-sidebar-grip" aria-hidden />
        <div className="si-hover-sidebar-panel">
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.MEETINGS }}>
            <span className="si-side-action-icon">M</span>
            <span>会议</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.HISTORY }}>
            <span className="si-side-action-icon">H</span>
            <span>历史记录</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.COST_ANALYSIS }}>
            <span className="si-side-action-icon">$</span>
            <span>成本分析</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.VOICES }}>
            <span className="si-side-action-icon">V</span>
            <span>音色</span>
          </button>
          <button className="si-side-action" onClick={copyShareLink}>
            <span className="si-side-action-icon">S</span>
            <span>{shareHint || '分享链接'}</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.TERMINOLOGY }}>
            <span className="si-side-action-icon">T</span>
            <span>设置</span>
          </button>
        </div>
      </aside>

      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">聚龙同传</h1>
        </div>
        <div className="si-topbar-right">
          <label className="si-manual-voice-select">
            <span>音色</span>
            <select
              value={selectedVoiceId}
              onChange={event => handleManualVoiceChange(event.target.value)}
              disabled={!isRunning || !currentSpeakerId || voices.length === 0}
            >
              <option value="">默认音色</option>
              {voices.map(voice => (
                <option key={voice.voiceId} value={voice.voiceId}>{voice.voiceName}</option>
              ))}
            </select>
          </label>
          <button className="si-logout-btn" type="button" onClick={handleLogout} disabled={logoutBusy}>
            {logoutBusy ? '退出中...' : '退出登录'}
          </button>
        </div>
      </header>

      <main className="si-main">
        <div className="si-workspace si-workspace--running">
          <div
            className="si-trilingual"
            style={{ '--si-tri-font-scale': transcriptFontScale } as CSSProperties}
          >
            <div className="si-tri-host-layout">
              <div className="si-tri-toolbar">
                <span className={`si-live-indicator ${isRunning ? 'is-running' : ''}`} />
                {voiceCodeForDisplay(currentSpeakerId) ? (
                  <span className="si-current-voice-code">{voiceCodeForDisplay(currentSpeakerId)}</span>
                ) : null}
                <div className="si-tri-toolbar-spacer" />
                <FontSizeControl scale={transcriptFontScale} onChange={setTranscriptFontScale} />
              </div>

              {error && (
                <p className="si-tri-err" style={{ whiteSpace: 'pre-wrap' }}>{error}</p>
              )}

              <div className="si-tri-transcript-dock">
                <div className="si-tri-transcript-dock-inner" ref={bodyRef}>
                  {transcripts.length === 0 && !isRunning && !currentSource && (
                    <div className="si-tri-empty">
                      点击“开始同传”，在弹窗中选择要翻译的标签页或窗口并勾选共享音频
                    </div>
                  )}

                  {transcripts.map(item => {
                    const currSpeaker = item.speakerName || mappedSpeakerName(item.speakerId, speakerNameMap) || item.speakerId || ''
                    return (
                      <div key={item.id} className="si-tri-block">
                        <div className="si-tri-block-line">
                          {currSpeaker
                            ? renderVoiceBadge(currSpeaker)
                            : <span className="si-tri-line-lang-badge si-tri-line-lang-badge--unknown">?</span>
                          }
                          <div className="si-tri-block-line-body">
                            <span className="si-tri-line-plain">{item.source}</span>
                          </div>
                        </div>
                        {item.translations.length === 0 && (
                          <div className="si-tri-block-line si-tri-block-line--no-badge">
                            <div className="si-tri-block-line-body">
                              <span className="si-tri-seg-pending">翻译中...</span>
                            </div>
                          </div>
                        )}
                        {item.translations.map(translation => (
                          <div key={translation.targetLanguage} className="si-tri-block-line si-tri-block-line--no-badge">
                            <div className="si-tri-block-line-body">
                              <span className="si-tri-line-plain">{translation.text}</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    )
                  })}

                  {isRunning && currentSource && (
                    <div className="si-tri-block si-tri-block--partial">
                      <div className="si-tri-block-latency si-tri-block-latency--streaming">
                        <IconHeadset />
                        <span>实时识别中</span>
                      </div>
                      <div className="si-tri-block-line">
                        {renderVoiceBadge(mappedSpeakerName(currentSpeakerId, speakerNameMap) || currentSpeakerId, 'si-tri-line-lang-badge si-tri-line-lang-badge--partial')}
                        <div className="si-tri-block-line-body">
                          <span className="si-tri-partial-live-text">{currentSource}</span>
                        </div>
                      </div>
                      <div className="si-tri-block-line">
                        {renderVoiceBadge(mappedSpeakerName(currentSpeakerId, speakerNameMap) || currentSpeakerId, 'si-tri-line-lang-badge si-tri-line-lang-badge--partial')}
                        <div className="si-tri-block-line-body">
                          <span className="si-tri-seg-pending">
                            {currentTranslated ? `实时: ${currentTranslated.slice(-40)}` : '翻译中...'}
                          </span>
                        </div>
                      </div>
                    </div>
                  )}

                  {isRunning && !currentSource && transcripts.length === 0 && (
                    <div className="si-tri-empty si-tri-empty--capture-wait">
                      <div>等待捕获到音频...</div>
                      <p className="si-tri-capture-warn">
                        请确认已选择正确的标签页或窗口，并勾选了共享音频。
                      </p>
                    </div>
                  )}
                </div>
                {isTranscriptAutoScrollPaused && (
                  <button
                    type="button"
                    className="si-auto-scroll-btn"
                    onClick={scrollTranscriptToBottom}
                  >
                    回到底部
                  </button>
                )}
              </div>
            </div>

            <div className="si-tri-footer">
              {!isRunning && (
                <div className="si-meeting-selector">
                  <label className="si-meeting-selector-label">关联会议</label>
                  <select
                    className="si-meeting-selector-select"
                    value={selectedMeetingId ?? ''}
                    onChange={e => setSelectedMeetingId(e.target.value ? Number(e.target.value) : null)}
                  >
                    <option value="" disabled>— 请选择关联会议 —</option>
                    {meetings.map(m => (
                      <option key={m.id} value={m.id}>{m.title}{m.scheduledTime ? ` (${m.scheduledTime})` : ''}</option>
                    ))}
                  </select>
                  <div className="si-language-checkboxes">
                    {LANGUAGE_OPTIONS.map(opt => {
                      // 中文、印尼语是开会必选语种：恒为勾选且不可取消；仅英语可选。
                      const required = opt.value === LANGUAGE.ZH_CN || opt.value === LANGUAGE.ID_ID
                      return (
                        <label key={opt.value} className="si-language-checkbox-item">
                          <input
                            type="checkbox"
                            checked={required || enabledLanguages.includes(opt.value)}
                            onChange={e => {
                              if (required) return
                              const next = e.target.checked
                                ? [...enabledLanguages, opt.value]
                                : enabledLanguages.filter(l => l !== opt.value)
                              const ensured = Array.from(new Set([LANGUAGE.ZH_CN, LANGUAGE.ID_ID, ...next]))
                              setEnabledLanguages(ensured)
                              void saveUserLanguagePreference({ defaultSourceLang: LANGUAGE.AUTO, enabledLanguages: ensured })
                                .catch(err => console.warn('[InterpretationView] saveUserLanguagePreference failed:', err))
                            }}
                          />
                          <span>{opt.label}</span>
                        </label>
                      )
                    })}
                  </div>
                </div>
              )}
              <div className="si-run-control">
                <div className="si-run-status">
                  <span className={`si-run-dot ${isRunning ? 'is-running' : ''}`} />
                  <span>{isRunning ? '运行中' : isLoading ? '启动中' : '待机'}</span>
                </div>
                {!isRunning ? (
                  <button type="button" className="si-tri-btn" onClick={startSession} disabled={isLoading}>
                    <IconPlay />
                    <span>{isLoading ? '启动中' : '开始'}</span>
                  </button>
                ) : (
                  <button type="button" className="si-tri-btn si-tri-btn--stop" onClick={stopSession}>
                    <IconStop />
                    <span>结束</span>
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
