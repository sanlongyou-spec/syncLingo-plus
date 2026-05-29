import React, { useCallback, useEffect, useRef, useState } from 'react'
import {
  generateSpeakerSummary,
  getAsrHotwords,
  getMeetingParticipants,
  getMeetings,
  getUserLanguagePreference,
  saveUserLanguagePreference,
  getUserVoice,
  saveInterpretationResult,
  startInterpretation,
  stopInterpretation,
} from '../api'
import { AUDIO_DEFAULTS, VOICEMEETER } from '../api/constants'
import { LANGUAGE, ROUTES, STORAGE_KEYS } from '../constants'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { AsrWebSocket } from '../lib/websocket'
import { LANGUAGE_OPTIONS } from '../types'
import type { Meeting, WsMessage } from '../types'
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
  if (lower === 'undefined' || lower === 'null') return ''
  return trimmed
}

const isUnknownSpeakerId = (speakerId?: string | null) =>
  speakerId?.trim().toLowerCase() === 'unknown'

const MIN_SPEAKER_CHANGE_CHARS = 30

const mappedSpeakerName = (speakerId: string | undefined, speakerNameMap: Record<string, string>) =>
  speakerId && !isUnknownSpeakerId(speakerId) ? speakerNameMap[speakerId] : ''

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

  // ── Meeting & speaker summary ─────────────────────────────
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)

  const wsRef = useRef<AsrWebSocket | null>(null)
  const audioRef = useRef<AudioCapture | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const detectedLangRef = useRef('')
  const currentSpeakerIdRef = useRef('')
  const bodyRef = useRef<HTMLDivElement>(null)
  const ttsChunkIndexByTaskRef = useRef<Map<string, number>>(new Map())

  // Speaker change detection refs (ID-based for known speakers)
  const speakerBufferRef = useRef<Record<string, string>>({})
  const prevFinalSpeakerRef = useRef('')
  const speakerNameMapRef = useRef<Record<string, string>>({})
  // Candidate new speaker accumulates here until MIN_SPEAKER_CHANGE_CHARS; prevents misidentification
  const pendingSpeakerRef = useRef<{ speakerId: string; buffer: string } | null>(null)
  // Name-based tracking for Unknown speakers resolved via Pyannote (in translated messages)
  const nameBufferRef = useRef<Record<string, string>>({})
  const prevResolvedNameRef = useRef('')

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
  useEffect(() => { speakerNameMapRef.current = speakerNameMap }, [speakerNameMap])
  useEffect(() => {
    getMeetings(userId)
      .then(res => setMeetings(res.data || []))
      .catch((err: unknown) => console.warn('[InterpretationView] getMeetings failed:', err))

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

  // Refresh meetings whenever an overlay is closed and user returns here
  useEffect(() => {
    const refreshMeetings = () => {
      getMeetings(userId)
        .then(res => {
          const list = res.data || []
          setMeetings(list)
          // If previously selected meeting was deleted, clear the selection
          setSelectedMeetingId(prev =>
            prev !== null && !list.some(m => m.id === prev) ? null : prev
          )
        })
        .catch((err: unknown) => console.warn('[InterpretationView] getMeetings refresh failed:', err))
    }
    window.addEventListener('hashchange', refreshMeetings)
    return () => window.removeEventListener('hashchange', refreshMeetings)
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

  const resolveSpeakerName = useCallback((speakerId?: string, speakerName?: string | null) => {
    const displayName = speakerName?.trim()
    if (displayName) return displayName
    return mappedSpeakerName(speakerId, speakerNameMapRef.current)
  }, [])

  const rememberSpeakerName = useCallback((speakerId?: string, speakerName?: string | null) => {
    const displayName = speakerName?.trim()
    if (!speakerId || !displayName || isUnknownSpeakerId(speakerId)) return
    setSpeakerNameMap(prev => prev[speakerId] === displayName ? prev : { ...prev, [speakerId]: displayName })
  }, [])

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
          setCurrentSpeakerId(messageSpeakerId)
          currentSpeakerIdRef.current = messageSpeakerId
        }
        rememberSpeakerName(messageSpeakerId, messageSpeakerName)
        if (msg.language) {
          detectedLangRef.current = msg.language
          setDetectedLang(msg.language)
        }
        break
      case 'recognized':
        if (msg.text) {
          rememberSpeakerName(messageSpeakerId, messageSpeakerName)
          // Speaker change detection for auto-summary
          if (messageSpeakerId) {
            const text = (msg.text || '').trim()
            if (isUnknownSpeakerId(messageSpeakerId)) {
              // Attribute Unknown text to the last known speaker's buffer
              const lastKnown = prevFinalSpeakerRef.current
              if (lastKnown && !isUnknownSpeakerId(lastKnown)) {
                speakerBufferRef.current[lastKnown] =
                  ((speakerBufferRef.current[lastKnown] || '') + ' ' + text).trim()
              }
            } else if (messageSpeakerId === prevFinalSpeakerRef.current) {
              // Same speaker confirmed — flush any pending misrecognition back to them
              const pending = pendingSpeakerRef.current
              if (pending) {
                speakerBufferRef.current[messageSpeakerId] =
                  ((speakerBufferRef.current[messageSpeakerId] || '') + ' ' + pending.buffer).trim()
                pendingSpeakerRef.current = null
              }
              speakerBufferRef.current[messageSpeakerId] =
                ((speakerBufferRef.current[messageSpeakerId] || '') + ' ' + text).trim()
            } else {
              // Different speaker — buffer until MIN_SPEAKER_CHANGE_CHARS to reject misidentifications
              const pending = pendingSpeakerRef.current
              if (pending?.speakerId === messageSpeakerId) {
                pending.buffer = (pending.buffer + ' ' + text).trim()
                if (pending.buffer.length >= MIN_SPEAKER_CHANGE_CHARS) {
                  // Enough text — commit the speaker change
                  const prevSpeaker = prevFinalSpeakerRef.current
                  if (prevSpeaker && !isUnknownSpeakerId(prevSpeaker)) {
                    const prevText = speakerBufferRef.current[prevSpeaker] || ''
                    const prevName = speakerNameMapRef.current[prevSpeaker] || prevSpeaker
                    if (prevText.length >= 30 && sessionIdRef.current) {
                      void triggerSpeakerSummary(prevSpeaker, prevName, prevText, sessionIdRef.current)
                    }
                    delete speakerBufferRef.current[prevSpeaker]
                  }
                  speakerBufferRef.current[messageSpeakerId] =
                    ((speakerBufferRef.current[messageSpeakerId] || '') + ' ' + pending.buffer).trim()
                  prevFinalSpeakerRef.current = messageSpeakerId
                  pendingSpeakerRef.current = null
                }
              } else {
                // New candidate — flush previous pending back to prevFinalSpeaker (was misrecognition)
                if (pending) {
                  const curr = prevFinalSpeakerRef.current
                  speakerBufferRef.current[curr] =
                    ((speakerBufferRef.current[curr] || '') + ' ' + pending.buffer).trim()
                }
                pendingSpeakerRef.current = { speakerId: messageSpeakerId, buffer: text }
              }
            }
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
        // Name-based speaker change detection for Pyannote-resolved speakers.
        // "Unknown" is Azure's placeholder before Pyannote identifies the speaker —
        // only trigger when we have a genuinely resolved name.
        const isResolved = (n: string) => !!n && n !== 'Unknown' && !n.startsWith('Unknown ')
        if (!messageSpeakerId && isResolved(messageSpeakerName) && originalText) {
          nameBufferRef.current[messageSpeakerName] =
            ((nameBufferRef.current[messageSpeakerName] || '') + ' ' + originalText).trim()
          const prevName = prevResolvedNameRef.current
          if (isResolved(prevName) && prevName !== messageSpeakerName) {
            const prevText = nameBufferRef.current[prevName] || ''
            if (prevText.length >= 30 && sessionIdRef.current) {
              void triggerSpeakerSummary('', prevName, prevText, sessionIdRef.current)
            }
            delete nameBufferRef.current[prevName]
          }
          prevResolvedNameRef.current = messageSpeakerName
        }
        break
      }
      case 'speaker_identity':
        if (msg.speakerId) {
          const identitySpeakerId = normalizeVoiceCode(msg.speakerId)
          const displayName = msg.speakerName?.trim() || ''
          if (!identitySpeakerId) break
          setCurrentSpeakerId(identitySpeakerId)
          currentSpeakerIdRef.current = identitySpeakerId
          if (displayName) {
            if (isUnknownSpeakerId(identitySpeakerId)) {
              setTranscripts(prev => {
                const matchedIndex = [...prev].reverse().findIndex(item =>
                  isUnknownSpeakerId(item.speakerId) && !item.speakerName,
                )
                if (matchedIndex < 0) return prev
                const index = prev.length - 1 - matchedIndex
                return prev.map((item, itemIndex) =>
                  itemIndex === index ? { ...item, speakerName: displayName } : item,
                )
              })
            } else {
              setSpeakerNameMap(prev => prev[identitySpeakerId] === displayName ? prev : { ...prev, [identitySpeakerId]: displayName })
              setTranscripts(prev => prev.map(item =>
                item.speakerId === identitySpeakerId ? { ...item, speakerName: displayName } : item,
              ))
            }
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
  }, [playPcm, rememberSpeakerName, resolveSpeakerName])

  const triggerSpeakerSummary = useCallback(async (
    speakerId: string, speakerName: string, text: string, sid: string,
  ) => {
    try {
      await generateSpeakerSummary({ userId, sessionId: sid, speakerId, speakerName, text })
    } catch (e) {
      console.warn('[InterpretationView] speaker summary failed:', e)
    }
  }, [userId])

  const resolveActiveMeetingTitle = async () => {
    try {
      const meetingData = await getMeetingParticipants()
      return meetingData.meetingTitle?.trim() || undefined
    } catch {
      return undefined
    }
  }

  const startSession = async () => {
    if (!selectedMeetingId) {
      setError('请先在"会前管理"页面上传会议安排，并在此选择关联会议')
      return
    }
    if (enabledLanguages.length < 2) {
      setError('请至少勾选两种翻译语种')
      return
    }
    setError('')
    setIsLoading(true)
    try {
      ttsChunkIndexByTaskRef.current.clear()
      const selectedMeeting = meetings.find(m => m.id === selectedMeetingId)
      const sessionTitle = selectedMeeting?.title || await resolveActiveMeetingTitle()

      const res = await startInterpretation({
        userId,
        sourceLang: LANGUAGE.AUTO,
        targetLang: LANGUAGE.AUTO,
        title: sessionTitle,
        voiceId: voiceId || undefined,
        hotwordIds: selectedHotwordIds,
        enabledLanguages,
        meetingId: selectedMeetingId,
      })
      const sid = res.data
      setSessionId(sid)
      sessionIdRef.current = sid
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
    const sid = sessionId
    // Resolve pending speaker before flushing
    const pendingOnStop = pendingSpeakerRef.current
    if (pendingOnStop) {
      if (pendingOnStop.buffer.length >= MIN_SPEAKER_CHANGE_CHARS) {
        // Spoke enough at end of session — commit the change
        const prevSpeaker = prevFinalSpeakerRef.current
        if (prevSpeaker && !isUnknownSpeakerId(prevSpeaker)) {
          const prevText = speakerBufferRef.current[prevSpeaker] || ''
          const prevName = speakerNameMapRef.current[prevSpeaker] || prevSpeaker
          if (prevText.length >= 30) void triggerSpeakerSummary(prevSpeaker, prevName, prevText, sid)
          delete speakerBufferRef.current[prevSpeaker]
        }
        speakerBufferRef.current[pendingOnStop.speakerId] =
          ((speakerBufferRef.current[pendingOnStop.speakerId] || '') + ' ' + pendingOnStop.buffer).trim()
        prevFinalSpeakerRef.current = pendingOnStop.speakerId
      } else {
        // Too short — flush back to previous speaker (misrecognition at end of session)
        const curr = prevFinalSpeakerRef.current
        speakerBufferRef.current[curr] =
          ((speakerBufferRef.current[curr] || '') + ' ' + pendingOnStop.buffer).trim()
      }
      pendingSpeakerRef.current = null
    }
    // Flush last speaker's buffer before stopping
    const lastSpeaker = prevFinalSpeakerRef.current
    const lastText = speakerBufferRef.current[lastSpeaker] || ''
    if (lastSpeaker && !isUnknownSpeakerId(lastSpeaker) && lastText.length >= 30) {
      const lastName = speakerNameMapRef.current[lastSpeaker] || lastSpeaker
      void triggerSpeakerSummary(lastSpeaker, lastName, lastText, sid)
    }
    speakerBufferRef.current = {}
    prevFinalSpeakerRef.current = ''
    pendingSpeakerRef.current = null
    // Flush last Pyannote-resolved speaker's buffer (skip if still "Unknown")
    const lastResolvedName = prevResolvedNameRef.current
    const lastNameText = nameBufferRef.current[lastResolvedName] || ''
    if (lastResolvedName && lastResolvedName !== 'Unknown' && !lastResolvedName.startsWith('Unknown ') && lastNameText.length >= 30) {
      void triggerSpeakerSummary('', lastResolvedName, lastNameText, sessionId)
    }
    nameBufferRef.current = {}
    prevResolvedNameRef.current = ''
    wsRef.current?.stop(sid)
    audioRef.current?.stop()
    audioRef.current = null
    await stopInterpretation(sid).then(res => {
      const warning = res?.data?.budgetWarning as string | undefined
      if (warning) {
        setTimeout(() => alert(`⚠️ 预算提醒：${warning}`), 300)
      }
    }).catch((err: unknown) => {
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

  const permanentShareUrl = `${window.location.origin}${window.location.pathname}#/share/user/${userId}`

  const copyShareLink = async () => {
    await navigator.clipboard.writeText(permanentShareUrl)
  }


  return (
    <div className="si-root">
      <aside className="si-hover-sidebar" aria-label="工具侧边栏">
        <div className="si-hover-sidebar-grip" aria-hidden />
        <div className="si-hover-sidebar-panel">
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.TEAMS_BOT }}>
            <span className="si-side-action-icon">T</span>
            <span>Teams Bot</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.VOICE_CLONE }}>
            <IconLightbulb />
            <span>音色克隆</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.TERMINOLOGY }}>
            <span className="si-side-action-icon">T</span>
            <span>术语表</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.HISTORY }}>
            <span className="si-side-action-icon">H</span>
            <span>历史记录</span>
          </button>
          <button className="si-side-action" onClick={() => { window.location.hash = ROUTES.COST_ANALYSIS }}>
            <span className="si-side-action-icon">$</span>
            <span>成本分析</span>
          </button>
          <button className="si-side-action" onClick={copyShareLink}>
            <span className="si-side-action-icon">S</span>
            <span>复制分享链接</span>
          </button>
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
                    {LANGUAGE_OPTIONS.map(opt => (
                      <label key={opt.value} className="si-language-checkbox-item">
                        <input
                          type="checkbox"
                          checked={enabledLanguages.includes(opt.value)}
                          onChange={e => {
                            const next = e.target.checked
                              ? [...enabledLanguages, opt.value]
                              : enabledLanguages.filter(l => l !== opt.value)
                            setEnabledLanguages(next)
                            void saveUserLanguagePreference(userId, { defaultSourceLang: LANGUAGE.AUTO, enabledLanguages: next })
                              .catch(err => console.warn('[InterpretationView] saveUserLanguagePreference failed:', err))
                          }}
                        />
                        <span>{opt.label}</span>
                      </label>
                    ))}
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
