import React, { useCallback, useEffect, useRef, useState } from 'react'
import {
  getAsrHotwords,
  getMeetingParticipants,
  getMeetingSummary,
  getUserLanguagePreference,
  getUserVoice,
  saveInterpretationResult,
  sendTeamsSummaryToUsers,
  startInterpretation,
  stopInterpretation,
} from '../api'
import { AUDIO_DEFAULTS, VOICEMEETER } from '../api/constants'
import { LANGUAGE, ROUTES, STORAGE_KEYS } from '../constants'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { AsrWebSocket } from '../lib/websocket'
import type { WsMessage } from '../types'
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


function IconLightbulb() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M9 21h6v-1H9v1zm3-19a6 6 0 00-3 11.17V17h6v-3.83A6 6 0 0012 2zm0 10a4 4 0 110-8 4 4 0 010 8z"
        fill="currentColor"
      />
    </svg>
  )
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
  if (lower === 'unknown' || lower === 'undefined' || lower === 'null') return ''
  return trimmed
}

export default function InterpretationView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')

  const [sessionId, setSessionId] = useState<string | null>(null)
  const [isRunning, setIsRunning] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [transcripts, setTranscripts] = useState<TranscriptItem[]>([])
  const [currentSource, setCurrentSource] = useState('')
  const [currentTranslated, setCurrentTranslated] = useState('')
  const [voiceId, setVoiceId] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [detectedLang, setDetectedLang] = useState('')
  const [currentSpeakerId, setCurrentSpeakerId] = useState('')
  const [speakerNameMap, setSpeakerNameMap] = useState<Record<string, string>>({})
  const [selectedHotwordIds, setSelectedHotwordIds] = useState<number[]>([])
  const [enabledLanguages, setEnabledLanguages] = useState<string[]>([LANGUAGE.ZH_CN, LANGUAGE.ID_ID])
  const [lastSessionId, setLastSessionId] = useState<string | null>(null)
  const [teamsPushStatus, setTeamsPushStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')

  const wsRef = useRef<AsrWebSocket | null>(null)
  const audioRef = useRef<AudioCapture | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const detectedLangRef = useRef('')
  const currentSpeakerIdRef = useRef('')
  const bodyRef = useRef<HTMLDivElement>(null)
  const ttsChunkIndexByTaskRef = useRef<Map<string, number>>(new Map())

  const streamContextRef = useRef<AudioContext | null>(null)
  const streamDestZhRef = useRef<MediaStreamAudioDestinationNode | null>(null)
  const streamDestIdRef = useRef<MediaStreamAudioDestinationNode | null>(null)
  const streamDestEnRef = useRef<MediaStreamAudioDestinationNode | null>(null)
  const streamAudioElZhRef = useRef<HTMLAudioElement | null>(null)
  const streamAudioElIdRef = useRef<HTMLAudioElement | null>(null)
  const streamAudioElEnRef = useRef<HTMLAudioElement | null>(null)
  const streamSinkReadyZhRef = useRef(false)
  const streamSinkReadyIdRef = useRef(false)
  const streamSinkReadyEnRef = useRef(false)
  const scheduleTimeZhRef = useRef(0)
  const scheduleTimeIdRef = useRef(0)
  const scheduleTimeEnRef = useRef(0)
  const pendingSourcesZhRef = useRef<Set<AudioBufferSourceNode>>(new Set())
  const pendingSourcesIdRef = useRef<Set<AudioBufferSourceNode>>(new Set())
  const pendingSourcesEnRef = useRef<Set<AudioBufferSourceNode>>(new Set())

  useEffect(() => { detectedLangRef.current = detectedLang }, [detectedLang])
  useEffect(() => { sessionIdRef.current = sessionId }, [sessionId])
  useEffect(() => { currentSpeakerIdRef.current = currentSpeakerId }, [currentSpeakerId])

  useEffect(() => {
    getUserVoice(userId)
      .then(res => {
        if (res.data?.voiceId) setVoiceId(res.data.voiceId)
      })
      .catch((err: unknown) => console.warn('[InterpretationView] getUserVoice failed:', err))

    getAsrHotwords(userId, '', true)
      .then(res => {
        const items = res.data || []
        setSelectedHotwordIds(items.map(item => item.id).filter((id): id is number => typeof id === 'number'))
      })
      .catch((err: unknown) => console.warn('[InterpretationView] getAsrHotwords failed:', err))

    getUserLanguagePreference(userId)
      .then(res => {
        setEnabledLanguages(res.data?.enabledLanguages?.length ? res.data.enabledLanguages : [LANGUAGE.ZH_CN, LANGUAGE.ID_ID])
      })
      .catch((err: unknown) => console.warn('[InterpretationView] getUserLanguagePreference failed:', err))

    return () => {
      wsRef.current?.close()
      audioRef.current?.stop()
      audioRef.current = null
    }
  }, [userId])

  useEffect(() => {
    const el = bodyRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [transcripts, currentSource])

  const initStreamAudio = useCallback(async () => {
    if (!streamContextRef.current) {
      streamContextRef.current = new AudioContext()
    }
    const ctx = streamContextRef.current
    const setupElement = (destRef: React.MutableRefObject<MediaStreamAudioDestinationNode | null>, elRef: React.MutableRefObject<HTMLAudioElement | null>) => {
      if (destRef.current) return
      destRef.current = ctx.createMediaStreamDestination()
      elRef.current = new Audio()
      elRef.current.srcObject = destRef.current.stream
      elRef.current.autoplay = false
      elRef.current.volume = 0
      elRef.current.pause()
    }
    setupElement(streamDestZhRef, streamAudioElZhRef)
    setupElement(streamDestIdRef, streamAudioElIdRef)
    setupElement(streamDestEnRef, streamAudioElEnRef)
  }, [])

  const applyVoiceMeeterSinks = useCallback(async () => {
    const muteAndPause = (el: HTMLAudioElement | null) => {
      if (!el) return
      el.volume = 0
      el.autoplay = false
      el.pause()
    }
    const setSink = async (el: HTMLAudioElement | null, deviceId: string) => {
      if (!el || !('setSinkId' in el)) return false
      try {
        await (el as HTMLAudioElement & { setSinkId: (id: string) => Promise<void> }).setSinkId(deviceId)
        el.volume = 1
        await el.play()
        return true
      } catch (err) {
        console.warn('[InterpretationView] setSinkId/play failed:', err)
        muteAndPause(el)
        return false
      }
    }

    streamSinkReadyZhRef.current = false
    streamSinkReadyIdRef.current = false
    streamSinkReadyEnRef.current = false
    muteAndPause(streamAudioElZhRef.current)
    muteAndPause(streamAudioElIdRef.current)
    muteAndPause(streamAudioElEnRef.current)

    const outputs = (await navigator.mediaDevices.enumerateDevices()).filter(device => device.kind === 'audiooutput')
    const voiceMeeterOutputs = outputs.filter(device => {
      const label = device.label.toLowerCase()
      return label.includes('voicemeeter') || label.includes('voice meeter')
    })
    const zhDevice = voiceMeeterOutputs.find(device =>
      device.label.toLowerCase().includes(VOICEMEETER.ZH_DEVICE_LABEL.toLowerCase()) &&
      !device.label.toLowerCase().includes('aux') &&
      !device.label.toLowerCase().includes('vaio3'),
    ) || voiceMeeterOutputs.find(device =>
      !device.label.toLowerCase().includes('aux') && !device.label.toLowerCase().includes('vaio3'),
    )
    const idDevice = voiceMeeterOutputs.find(device =>
      device.label.toLowerCase().includes(VOICEMEETER.ID_DEVICE_LABEL.toLowerCase()),
    ) || voiceMeeterOutputs.find(device => device.label.toLowerCase().includes('aux'))
    const enDevice = voiceMeeterOutputs.find(device =>
      device.label.toLowerCase().includes(VOICEMEETER.EN_DEVICE_LABEL.toLowerCase()),
    ) || voiceMeeterOutputs.find(device => device.label.toLowerCase().includes('vaio3'))

    if (zhDevice) streamSinkReadyZhRef.current = await setSink(streamAudioElZhRef.current, zhDevice.deviceId)
    if (idDevice) streamSinkReadyIdRef.current = await setSink(streamAudioElIdRef.current, idDevice.deviceId)
    if (enDevice) streamSinkReadyEnRef.current = await setSink(streamAudioElEnRef.current, enDevice.deviceId)
  }, [])

  const areVoiceMeeterSinksReady = useCallback(() => (
    streamSinkReadyZhRef.current && streamSinkReadyIdRef.current
  ), [])

  const playStreamPcmDirect = (
    pcmData: Int16Array,
    sampleRate: number,
    ctx: AudioContext,
    dest: MediaStreamAudioDestinationNode,
    scheduleRef: React.MutableRefObject<number>,
    pendingRef: React.MutableRefObject<Set<AudioBufferSourceNode>>,
  ) => {
    const floatData = new Float32Array(pcmData.length)
    for (let i = 0; i < pcmData.length; i += 1) {
      floatData[i] = pcmData[i] / 32768
    }
    const buffer = ctx.createBuffer(1, floatData.length, sampleRate)
    buffer.copyToChannel(floatData, 0)
    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(dest)
    const startAt = Math.max(ctx.currentTime + 0.02, scheduleRef.current)
    pendingRef.current.add(source)
    source.onended = () => pendingRef.current.delete(source)
    source.start(startAt)
    scheduleRef.current = startAt + buffer.duration
  }

  const playStreamPcm = useCallback((pcmData: Int16Array, sampleRate: number, targetLang: string) => {
    const isEn = targetLang.startsWith('en')
    const isId = targetLang.startsWith('id')
    const ctx = streamContextRef.current
    const dest = isEn ? streamDestEnRef.current : (isId ? streamDestIdRef.current : streamDestZhRef.current)
    const sinkReady = isEn ? streamSinkReadyEnRef.current : (isId ? streamSinkReadyIdRef.current : streamSinkReadyZhRef.current)
    if (!ctx || !dest || !sinkReady) return
    const scheduleRef = isEn ? scheduleTimeEnRef : (isId ? scheduleTimeIdRef : scheduleTimeZhRef)
    const pendingRef = isEn ? pendingSourcesEnRef : (isId ? pendingSourcesIdRef : pendingSourcesZhRef)
    playStreamPcmDirect(pcmData, sampleRate, ctx, dest, scheduleRef, pendingRef)
  }, [])

  const playPcm = useCallback((pcmData: Int16Array, targetLang: string) => {
    playStreamPcm(pcmData, 24000, targetLang)
  }, [playStreamPcm])

  const handleWsMessage = useCallback((msg: WsMessage) => {
    const messageSpeakerId =
      normalizeVoiceCode(msg.speakerId) ||
      normalizeVoiceCode(msg.voiceId) ||
      normalizeVoiceCode(currentSpeakerIdRef.current)
    const messageSpeakerName = msg.speakerName?.trim() || ''

    switch (msg.type) {
      case 'recognizing':
        setCurrentSource(msg.text || '')
        if (messageSpeakerId) {
          setCurrentSpeakerId(messageSpeakerId)
          currentSpeakerIdRef.current = messageSpeakerId
        }
        if (messageSpeakerName && messageSpeakerId) {
          setSpeakerNameMap(prev => prev[messageSpeakerId] === messageSpeakerName ? prev : { ...prev, [messageSpeakerId]: messageSpeakerName })
        }
        if (msg.language) {
          detectedLangRef.current = msg.language
          setDetectedLang(msg.language)
        }
        break
      case 'recognized':
        if (msg.text) {
          if (messageSpeakerName && messageSpeakerId) {
            setSpeakerNameMap(prev => prev[messageSpeakerId] === messageSpeakerName ? prev : { ...prev, [messageSpeakerId]: messageSpeakerName })
          }
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
          }).catch(err => console.warn('[InterpretationView] saveInterpretationResult failed:', err))
        }
        if (!translatedText) break
        setTranscripts(prev => {
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
            return prev.map((item, itemIndex) => itemIndex === index ? upsertTranslation(item) : item)
          }
          if (prev.length > 0 && prev[prev.length - 1].translations.length === 0) {
            return [...prev.slice(0, -1), upsertTranslation(prev[prev.length - 1])]
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
      case 'speaker_identity':
        if (msg.speakerId) {
          const displayName = msg.speakerName?.trim() || ''
          setCurrentSpeakerId(msg.speakerId)
          currentSpeakerIdRef.current = msg.speakerId
          if (displayName) {
            setSpeakerNameMap(prev => prev[msg.speakerId!] === displayName ? prev : { ...prev, [msg.speakerId!]: displayName })
            setTranscripts(prev => prev.map(item =>
              item.speakerId === msg.speakerId ? { ...item, speakerName: displayName } : item,
            ))
          }
        }
        break
      case 'tts_audio':
        if (msg.audioBase64 && msg.targetLanguage) {
          if (msg.ttsTaskId && typeof msg.chunkIndex === 'number') {
            const lastIndex = ttsChunkIndexByTaskRef.current.get(msg.ttsTaskId) ?? -1
            if (msg.chunkIndex <= lastIndex) break
            ttsChunkIndexByTaskRef.current.set(msg.ttsTaskId, msg.chunkIndex)
          }
          try {
            const binaryStr = atob(msg.audioBase64)
            const bytes = new Uint8Array(binaryStr.length)
            for (let i = 0; i < binaryStr.length; i += 1) {
              bytes[i] = binaryStr.charCodeAt(i)
            }
            playPcm(new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 2), msg.targetLanguage)
          } catch (err) {
            console.error('[InterpretationView] failed to play TTS audio:', err)
          }
        }
        break
      case 'started':
        setIsRunning(true)
        setError('')
        setCurrentSpeakerId('')
        setSpeakerNameMap({})
        setDetectedLang(msg.language || '')
        currentSpeakerIdRef.current = ''
        detectedLangRef.current = msg.language || ''
        break
      case 'stopped':
        setIsRunning(false)
        break
      case 'error':
        setError(msg.message || '发生错误')
        break
    }
  }, [playPcm])

  const startSession = async () => {
    setError('')
    setIsLoading(true)
    try {
      ttsChunkIndexByTaskRef.current.clear()

      const res = await startInterpretation({
        userId,
        sourceLang: LANGUAGE.AUTO,
        targetLang: LANGUAGE.AUTO,
        voiceId: voiceId || undefined,
        hotwordIds: selectedHotwordIds,
        enabledLanguages,
      })
      const sid = res.data
      setSessionId(sid)
      sessionIdRef.current = sid
      setLastSessionId(sid)
      setTeamsPushStatus('idle')
      localStorage.setItem(STORAGE_KEYS.CURRENT_SESSION_ID, sid)

      const ws = new AsrWebSocket()
      wsRef.current = ws
      await ws.connect(sid)
      ws.onMessage(handleWsMessage)
      ws.start({ sessionId: sid, sourceLang: LANGUAGE.AUTO, targetLang: LANGUAGE.AUTO, voiceId: voiceId || undefined })

      await initStreamAudio()
      await applyVoiceMeeterSinks()
      if (!areVoiceMeeterSinksReady()) {
        throw new Error('VoiceMeeter output is not ready. Please select VoiceMeeter output devices before starting.')
      }

      const audio = new AudioCapture({
        sampleRate: AUDIO_DEFAULTS.SAMPLE_RATE,
        onData: pcm => ws.sendAudio(sid, pcmToBase64(pcm)),
      })
      audioRef.current = audio
      await audio.start()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '启动失败')
    } finally {
      setIsLoading(false)
    }
  }

  const stopSession = async () => {
    if (!sessionId) return
    wsRef.current?.stop(sessionId)
    audioRef.current?.stop()
    audioRef.current = null
    await stopInterpretation(sessionId).catch((err: unknown) => {
      console.warn('[InterpretationView] stopInterpretation failed:', err)
    })
    wsRef.current?.close()
    streamAudioElZhRef.current?.pause()
    streamAudioElIdRef.current?.pause()
    streamAudioElEnRef.current?.pause()
    pendingSourcesZhRef.current.forEach(source => { try { source.stop() } catch { /* already ended */ } })
    pendingSourcesIdRef.current.forEach(source => { try { source.stop() } catch { /* already ended */ } })
    pendingSourcesEnRef.current.forEach(source => { try { source.stop() } catch { /* already ended */ } })
    pendingSourcesZhRef.current.clear()
    pendingSourcesIdRef.current.clear()
    pendingSourcesEnRef.current.clear()
    streamSinkReadyZhRef.current = false
    streamSinkReadyIdRef.current = false
    streamSinkReadyEnRef.current = false
    streamDestZhRef.current = null
    streamDestIdRef.current = null
    streamDestEnRef.current = null
    streamAudioElZhRef.current = null
    streamAudioElIdRef.current = null
    streamAudioElEnRef.current = null
    await streamContextRef.current?.close()
    streamContextRef.current = null
    wsRef.current = null
    setSessionId(null)
    sessionIdRef.current = null
    localStorage.removeItem(STORAGE_KEYS.CURRENT_SESSION_ID)
    setIsRunning(false)
    setCurrentSource('')
    setCurrentTranslated('')
    setDetectedLang('')
    setCurrentSpeakerId('')
    currentSpeakerIdRef.current = ''
  }

  const voiceCodeForDisplay = (speakerId?: string) => {
    const code = normalizeVoiceCode(speakerId)
    if (!code) return ''
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

  const shareUrl = sessionId
    ? `${window.location.origin}${window.location.pathname}#/share/${sessionId}`
    : ''

  const copyShareLink = async () => {
    if (!shareUrl) return
    await navigator.clipboard.writeText(shareUrl)
  }

  const pushSummaryToTeams = async () => {
    const sid = lastSessionId
    if (!sid) return
    setTeamsPushStatus('loading')
    try {
      // Fetch participants from the active meeting the bot joined
      const participantsData = await getMeetingParticipants()
      if (!participantsData.callId || participantsData.participants.length === 0) {
        throw new Error('请先在 Teams Bot 页面粘贴会议链接，让机器人加入会议')
      }
      const recipients = participantsData.participants.map(p => p.aadId).filter(Boolean)

      const summaryData = await getMeetingSummary(sid)
      const summaryText = summaryData.data?.summary
      if (!summaryText?.trim()) throw new Error('摘要内容为空，请稍后重试')

      const teamsRes = await sendTeamsSummaryToUsers(summaryText, recipients)
      if (!teamsRes.sent) throw new Error(teamsRes.failures[0]?.error || '没有 Teams 用户收到摘要')
      setTeamsPushStatus('done')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
    } catch (e) {
      setTeamsPushStatus('error')
      setError(e instanceof Error ? e.message : '推送到 Teams 失败')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
    }
  }

  return (
    <div className="si-root">
      <aside className="si-hover-sidebar" aria-label="工具侧边栏">
        <div className="si-hover-sidebar-grip" aria-hidden />
        <div className="si-hover-sidebar-panel">
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.VOICE_CLONE }}>
            <IconLightbulb />
            <span>音色克隆</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.HISTORY }}>
            <span className="si-side-action-icon">H</span>
            <span>历史记录</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.TERMINOLOGY }}>
            <span className="si-side-action-icon">T</span>
            <span>术语表</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.MEETING_MATERIALS }}>
            <span className="si-side-action-icon">M</span>
            <span>会议资料总结</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.TEAMS_BOT }}>
            <span className="si-side-action-icon">T</span>
            <span>Teams Bot</span>
          </button>
          {lastSessionId && (
            <button
              className="si-side-action"
              onClick={pushSummaryToTeams}
              disabled={teamsPushStatus === 'loading'}
            >
              <span className="si-side-action-icon">
                {teamsPushStatus === 'done' ? '✓' : teamsPushStatus === 'error' ? '✗' : '↑'}
              </span>
              <span>
                {teamsPushStatus === 'loading' ? '推送中…' : teamsPushStatus === 'done' ? '已发送' : '推送摘要到Teams'}
              </span>
            </button>
          )}
          {sessionId && (
            <button className="si-side-action" onClick={copyShareLink}>
              <span className="si-side-action-icon">S</span>
              <span>复制分享链接</span>
            </button>
          )}
        </div>
      </aside>

      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">聚龙同传</h1>
        </div>
      </header>

      <main className="si-main">
        <div className="si-workspace si-workspace--running">
          <div className="si-trilingual">
            <div className="si-tri-host-layout">
              <div className="si-tri-toolbar">
                <span className={`si-live-indicator ${isRunning ? 'is-running' : ''}`} />
                {voiceCodeForDisplay(currentSpeakerId) ? (
                  <span className="si-current-voice-code">{voiceCodeForDisplay(currentSpeakerId)}</span>
                ) : null}
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

                  {transcripts.map((item, index) => {
                    const prev = index > 0 ? transcripts[index - 1] : null
                    const prevSpeaker = prev ? (prev.speakerName || prev.speakerId) : null
                    const currSpeaker = item.speakerName || item.speakerId
                    const showSpeakerHeader = currSpeaker !== prevSpeaker
                    return (
                      <div key={item.id} className="si-tri-block">
                        {showSpeakerHeader && (
                          <div className="si-tri-speaker-header">
                            {renderVoiceBadge(currSpeaker)}
                          </div>
                        )}
                        <div className="si-tri-block-line si-tri-block-line--no-badge">
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
                        {renderVoiceBadge(speakerNameMap[currentSpeakerId] || currentSpeakerId, 'si-tri-line-lang-badge si-tri-line-lang-badge--partial')}
                        <div className="si-tri-block-line-body">
                          <span className="si-tri-partial-live-text">{currentSource}</span>
                        </div>
                      </div>
                      <div className="si-tri-block-line">
                        {renderVoiceBadge(speakerNameMap[currentSpeakerId] || currentSpeakerId, 'si-tri-line-lang-badge si-tri-line-lang-badge--partial')}
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
              </div>
            </div>

            <div className="si-tri-footer">
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
