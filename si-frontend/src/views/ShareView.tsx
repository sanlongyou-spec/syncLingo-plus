import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { getPublicInterpretationResults, getPublicSessionInfo } from '../api'
import { WS_DEFAULTS } from '../api/constants'
import { useSmartAutoScroll } from '../lib/useSmartAutoScroll'
import type { InterpretationResultItem, WsMessage } from '../types'
import './InterpretationView.css'

const LANG_LABELS: Record<string, string> = { zh: '中文', id: 'Bahasa Indonesia', en: 'English' }
const MUTE_NOTICE: Record<string, { text: string; button: string }> = {
  zh: { text: '请先静音或降低 Teams 原声，避免同时听到原声和传译声音。谢谢。', button: '我知道了' },
  en: { text: 'Please mute or lower the original Teams audio to avoid hearing both original and interpretation audio. Thank you.', button: 'Got it' },
  id: { text: 'Harap matikan atau kecilkan suara asli Teams agar tidak mendengar suara asli dan terjemahan bersamaan. Terima kasih.', button: 'Saya mengerti' },
}
const AUDIO_SAMPLE_RATE = 48000
// 播放积压时加速追赶(变速变调, 不丢音频): 队列空→1.0x, 积压越多越快, 封顶 1.35x
const CATCHUP_START_SEC = 1.0
const CATCHUP_FULL_SEC = 4.0
const CATCHUP_MAX_RATE = 1.15   // 变调更轻微; 彻底去变调需上变速不变调算法
const catchupRate = (backlogSec: number): number => {
  if (backlogSec <= CATCHUP_START_SEC) return 1.0
  if (backlogSec >= CATCHUP_FULL_SEC) return CATCHUP_MAX_RATE
  return 1.0 + (backlogSec - CATCHUP_START_SEC) / (CATCHUP_FULL_SEC - CATCHUP_START_SEC) * (CATCHUP_MAX_RATE - 1.0)
}

const toCanonicalLang = (lang: string): string => {
  const lower = lang.trim().toLowerCase()
  if (lower.startsWith('zh')) return 'zh'
  if (lower.startsWith('id')) return 'id'
  if (lower.startsWith('en')) return 'en'
  return lower
}

interface DisplayShareItem {
  id: number
  sourceText: string
  sourceLang?: string
  displaySourceText: string
  translations: DisplayShareTranslation[]
  isStreaming?: boolean
  speakerId?: string
  speakerName?: string
}

interface DisplayShareTranslation {
  id: number
  translatedText: string
  targetLang?: string
  displayTranslatedText: string
}

const toDisplayItem = (item: InterpretationResultItem): DisplayShareItem => ({
  id: item.id,
  sourceText: item.sourceText,
  sourceLang: item.sourceLang,
  displaySourceText: item.sourceText,
  translations: [{
    id: item.id,
    translatedText: item.translatedText,
    targetLang: item.targetLang,
    displayTranslatedText: item.translatedText,
  }],
  isStreaming: false,
  speakerId: item.speakerId,
  speakerName: item.speakerName,
})

const upsertTranslation = (
  item: DisplayShareItem,
  translation: DisplayShareTranslation,
): DisplayShareItem => {
  const byId = item.translations.findIndex(existing => existing.id === translation.id)
  if (byId >= 0) {
    return {
      ...item,
      translations: item.translations.map((existing, index) =>
        index === byId ? translation : existing,
      ),
      isStreaming: false,
    }
  }

  const byText = item.translations.findIndex(existing =>
    existing.translatedText === translation.translatedText &&
    existing.targetLang === translation.targetLang,
  )
  if (byText >= 0) {
    return { ...item, isStreaming: false }
  }

  return {
    ...item,
    translations: [...item.translations, translation],
    isStreaming: false,
  }
}

const normalizeSpeakerId = (speakerId?: string | null) => {
  const trimmed = speakerId?.trim()
  if (!trimmed) return ''
  const lower = trimmed.toLowerCase()
  if (lower === 'undefined' || lower === 'null') return ''
  return trimmed
}

const isUnknownSpeakerId = (speakerId?: string | null) =>
  speakerId?.trim().toLowerCase() === 'unknown'

const mappedSpeakerName = (speakerId: string | undefined, speakerNameMap: Record<string, string>) =>
  speakerId && !isUnknownSpeakerId(speakerId) ? speakerNameMap[speakerId] : ''

const mergePersistedSpeaker = (
  item: DisplayShareItem,
  persisted: InterpretationResultItem,
): DisplayShareItem => ({
  ...item,
  speakerId: item.speakerId || normalizeSpeakerId(persisted.speakerId) || undefined,
  speakerName: item.speakerName || persisted.speakerName?.trim() || undefined,
})

export default function ShareView() {
  const { sessionId = '' } = useParams()
  const [items, setItems] = useState<DisplayShareItem[]>([])
  const [currentRecognizing, setCurrentRecognizing] = useState('')
  const [currentLanguage, setCurrentLanguage] = useState('')
  const [currentSpeakerId, setCurrentSpeakerId] = useState('')
  const [currentSpeakerName, setCurrentSpeakerName] = useState('')
  const [speakerNameMap, setSpeakerNameMap] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const {
    scrollRef: bodyRef,
    isPaused: isTranscriptAutoScrollPaused,
    scrollToBottom: scrollTranscriptToBottom,
  } = useSmartAutoScroll<HTMLDivElement>([items, currentRecognizing])
  const wsRef = useRef<WebSocket | null>(null)
  const liveIdRef = useRef(-1)
  const speakerNameMapRef = useRef<Record<string, string>>({})

  // ── 音频：选语言 + Opus(WebCodecs) 播放 ──────────────────────
  const [audioLangs, setAudioLangs] = useState<string[]>([])
  const [selectedLang, setSelectedLang] = useState<string | null>(null)
  const [noticeLang, setNoticeLang] = useState<string | null>(null)
  const audioWsRef = useRef<WebSocket | null>(null)
  const selectedLangRef = useRef<string | null>(null)
  const audioCtxRef = useRef<AudioContext | null>(null)
  const decoderRef = useRef<{ decode: (chunk: unknown) => void; close: () => void } | null>(null)
  const scheduleRef = useRef(0)
  const tsRef = useRef(0)
  const pendingSourcesRef = useRef<Set<AudioBufferSourceNode>>(new Set())

  // 兜底：从实时文本消息里推断可选语言，确保即使 /info 接口异常按钮也能出现
  const addAudioLang = (raw?: string) => {
    if (!raw) return
    const c = toCanonicalLang(raw)
    if (c !== 'zh' && c !== 'id' && c !== 'en') return
    const order = ['zh', 'id', 'en']
    setAudioLangs(prev => (prev.includes(c) ? prev : [...prev, c].sort((a, b) => order.indexOf(a) - order.indexOf(b))))
  }

  const stopAudio = () => {
    selectedLangRef.current = null
    audioWsRef.current?.close()
    audioWsRef.current = null
    try { decoderRef.current?.close() } catch { /* already closed */ }
    decoderRef.current = null
    pendingSourcesRef.current.forEach(source => { try { source.stop() } catch { /* ended */ } })
    pendingSourcesRef.current.clear()
    void audioCtxRef.current?.close()
    audioCtxRef.current = null
    scheduleRef.current = 0
    tsRef.current = 0
  }

  const startAudio = (lang: string) => {
    stopAudio()
    const canonical = toCanonicalLang(lang)
    selectedLangRef.current = canonical
    setSelectedLang(canonical)
    setNoticeLang(MUTE_NOTICE[canonical] ? canonical : null)

    const AudioDecoderCtor = (window as unknown as { AudioDecoder?: unknown }).AudioDecoder as
      | (new (init: { output: (data: unknown) => void; error: (e: unknown) => void }) => {
          configure: (cfg: unknown) => void
          decode: (chunk: unknown) => void
          close: () => void
        })
      | undefined
    const EncodedAudioChunkCtor = (window as unknown as { EncodedAudioChunk?: unknown }).EncodedAudioChunk as
      | (new (init: { type: string; timestamp: number; data: ArrayBuffer | ArrayBufferView }) => unknown)
      | undefined
    if (!AudioDecoderCtor || !EncodedAudioChunkCtor) {
      setError('当前浏览器不支持音频解码（需要 Chrome/Edge 等支持 WebCodecs 的浏览器）')
      return
    }

    const ctx = new AudioContext({ sampleRate: AUDIO_SAMPLE_RATE })
    audioCtxRef.current = ctx
    void ctx.resume()

    const handleAudioData = (data: unknown) => {
      const audioData = data as {
        numberOfFrames: number
        sampleRate: number
        allocationSize: (opt: { planeIndex: number; format: string }) => number
        copyTo: (dest: Float32Array, opt: { planeIndex: number; format: string }) => void
        close: () => void
      }
      try {
        const size = audioData.allocationSize({ planeIndex: 0, format: 'f32-planar' })
        const samples = new Float32Array(size / 4)
        audioData.copyTo(samples, { planeIndex: 0, format: 'f32-planar' })
        const buffer = ctx.createBuffer(1, samples.length, audioData.sampleRate)
        buffer.copyToChannel(samples, 0)
        const source = ctx.createBufferSource()
        source.buffer = buffer
        source.connect(ctx.destination)
        const backlogSec = Math.max(0, scheduleRef.current - ctx.currentTime)
        const rate = catchupRate(backlogSec)
        source.playbackRate.value = rate
        const startAt = Math.max(ctx.currentTime + 0.08, scheduleRef.current)
        pendingSourcesRef.current.add(source)
        source.onended = () => pendingSourcesRef.current.delete(source)
        source.start(startAt)
        scheduleRef.current = startAt + buffer.duration / rate
      } catch (err) {
        console.warn('[ShareView] audio render failed:', err)
      } finally {
        audioData.close()
      }
    }

    const decoder = new AudioDecoderCtor({
      output: handleAudioData,
      error: (e: unknown) => console.warn('[ShareView] audio decode error:', e),
    })
    decoder.configure({ codec: 'opus', sampleRate: AUDIO_SAMPLE_RATE, numberOfChannels: 1 })
    decoderRef.current = decoder

    const wsUrl = `${WS_DEFAULTS.BASE_URL.replace(/^http/, 'ws')}/ws/share-audio?sessionId=${encodeURIComponent(sessionId)}&lang=${canonical}`
    const ws = new WebSocket(wsUrl)
    ws.binaryType = 'arraybuffer'
    audioWsRef.current = ws
    ws.onmessage = event => {
      if (!(event.data instanceof ArrayBuffer) || decoderRef.current !== decoder) return
      const view = new Uint8Array(event.data)
      if (view.length === 0) return
      // 帧首字节：0x01=音频 0x02=标记 0x03=心跳；本页只播音频，其余忽略
      if (view[0] !== 0x01) return
      try {
        decoder.decode(new EncodedAudioChunkCtor({ type: 'key', timestamp: tsRef.current, data: event.data.slice(1) }))
        tsRef.current += 20000
      } catch (err) {
        console.warn('[ShareView] decode chunk failed:', err)
      }
    }
    ws.onclose = () => {
      // 仍选着该语言则自动重连
      if (audioWsRef.current === ws && selectedLangRef.current === canonical) {
        window.setTimeout(() => {
          if (audioWsRef.current === ws && selectedLangRef.current === canonical) startAudio(canonical)
        }, 2000)
      }
    }
  }

  useEffect(() => {
    if (!sessionId) return
    getPublicSessionInfo(sessionId)
      .then(res => {
        const langs = Array.from(new Set((res.data?.enabledLanguages ?? []).map(toCanonicalLang)))
        setAudioLangs(langs)
      })
      .catch((err: unknown) => console.warn('[ShareView] getPublicSessionInfo failed:', err))
  }, [sessionId])

  useEffect(() => stopAudio, [])

  const upsertPersistedItems = (list: InterpretationResultItem[]) => {
    list.forEach(item => {
      addAudioLang(item.sourceLang)
      addAudioLang(item.targetLang)
    })
    setItems(prev => {
      const next = [...prev]
      list.forEach(item => {
        const translation: DisplayShareTranslation = {
          id: item.id,
          translatedText: item.translatedText,
          targetLang: item.targetLang,
          displayTranslatedText: item.translatedText,
        }
        const byTranslationId = next.findIndex(existing =>
          existing.translations.some(existingTranslation => existingTranslation.id === item.id),
        )
        if (byTranslationId >= 0) {
          next[byTranslationId] = mergePersistedSpeaker(upsertTranslation(next[byTranslationId], translation), item)
          return
        }

        const bySource = next.findIndex(existing => existing.sourceText === item.sourceText)
        if (bySource >= 0) {
          next[bySource] = mergePersistedSpeaker(upsertTranslation(next[bySource], translation), item)
          return
        }

        const byPendingSource = next.findIndex(existing =>
          existing.id < 0 &&
          existing.sourceText === item.sourceText &&
          existing.translations.length === 0,
        )
        if (byPendingSource >= 0) {
          next[byPendingSource] = mergePersistedSpeaker(upsertTranslation(next[byPendingSource], translation), item)
          return
        }

        next.push(toDisplayItem(item))
      })
      return next
    })
  }

  useEffect(() => {
    speakerNameMapRef.current = speakerNameMap
  }, [speakerNameMap])

  const resolveSpeakerName = (speakerId?: string, speakerName?: string | null) => {
    const displayName = speakerName?.trim()
    if (displayName) return displayName
    return mappedSpeakerName(speakerId, speakerNameMapRef.current)
  }

  const rememberSpeakerName = (speakerId?: string, speakerName?: string | null) => {
    const displayName = speakerName?.trim()
    if (!speakerId || !displayName || isUnknownSpeakerId(speakerId)) return
    setSpeakerNameMap(prev => prev[speakerId] === displayName ? prev : { ...prev, [speakerId]: displayName })
  }

  const handleShareMessage = (msg: WsMessage) => {
    const messageSpeakerId = normalizeSpeakerId(msg.speakerId)
    const messageSpeakerName = resolveSpeakerName(messageSpeakerId, msg.speakerName)
    addAudioLang(msg.language)
    addAudioLang(msg.targetLanguage)
    switch (msg.type) {
      case 'recognizing':
        setCurrentRecognizing(msg.text || '')
        setCurrentLanguage(msg.language || '')
        if (messageSpeakerId) {
          setCurrentSpeakerId(messageSpeakerId)
        }
        setCurrentSpeakerName(messageSpeakerName || '')
        if (messageSpeakerName) {
          rememberSpeakerName(messageSpeakerId, messageSpeakerName)
        }
        break
      case 'recognized': {
        if (!msg.text) return
        setCurrentRecognizing('')
        setCurrentLanguage(msg.language || '')
        if (messageSpeakerId) {
          setCurrentSpeakerId(messageSpeakerId)
        }
        setCurrentSpeakerName(messageSpeakerName || '')
        if (messageSpeakerName) {
          rememberSpeakerName(messageSpeakerId, messageSpeakerName)
        }
        const id = liveIdRef.current--
        setItems(prev => [
          ...prev,
          {
            id,
            sourceText: msg.text || '',
            sourceLang: msg.language,
            displaySourceText: msg.text || '',
            translations: [],
            isStreaming: true,
            speakerId: messageSpeakerId || undefined,
            speakerName: messageSpeakerName || undefined,
          },
        ])
        break
      }
      case 'translated': {
        const sourceText = msg.text || ''
        const translatedText = msg.translatedText || ''
        if (!sourceText && !translatedText) return
        if (messageSpeakerId) {
          setCurrentSpeakerId(messageSpeakerId)
        }
        setCurrentSpeakerName(messageSpeakerName || '')
        if (messageSpeakerName) {
          rememberSpeakerName(messageSpeakerId, messageSpeakerName)
        }
        setItems(prev => {
          const translation: DisplayShareTranslation = {
            id: liveIdRef.current--,
            translatedText,
            targetLang: msg.targetLanguage,
            displayTranslatedText: translatedText,
          }
          const mergeMessageSpeaker = (item: DisplayShareItem): DisplayShareItem => ({
            ...item,
            speakerId: item.speakerId || messageSpeakerId || undefined,
            speakerName: messageSpeakerName || item.speakerName,
          })
          const matchedIndex = sourceText
            ? [...prev].reverse().findIndex(item => item.sourceText === sourceText)
            : -1
          if (matchedIndex >= 0) {
            const index = prev.length - 1 - matchedIndex
            return prev.map((item, itemIndex) =>
              itemIndex === index
                ? upsertTranslation(mergeMessageSpeaker(item), translation)
                : item,
            )
          }
          return [
            ...prev,
            {
              id: liveIdRef.current--,
              sourceText,
              displaySourceText: sourceText,
              translations: [translation],
              isStreaming: false,
              speakerId: messageSpeakerId || undefined,
              speakerName: messageSpeakerName || undefined,
            },
          ]
        })
        break
      }
      case 'speaker_identity': {
        const identitySpeakerId = normalizeSpeakerId(msg.speakerId)
        const displayName = msg.speakerName?.trim() || ''
        if (!identitySpeakerId) break
        setCurrentSpeakerId(identitySpeakerId)
        setCurrentSpeakerName(displayName || '')
        if (displayName) {
          if (isUnknownSpeakerId(identitySpeakerId)) {
            setItems(prev => {
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
            rememberSpeakerName(identitySpeakerId, displayName)
            setItems(prev => prev.map(item =>
              item.speakerId === identitySpeakerId ? { ...item, speakerName: displayName } : item,
            ))
          }
        }
        break
      }
      case 'started':
        setCurrentRecognizing('')
        setCurrentSpeakerId('')
        setCurrentSpeakerName('')
        setSpeakerNameMap({})
        speakerNameMapRef.current = {}
        break
      case 'stopped':
        setCurrentRecognizing('')
        setCurrentSpeakerId('')
        setCurrentSpeakerName('')
        break
      case 'error':
        setError(msg.message || '实时分享连接异常')
        break
    }
  }

  useEffect(() => {
    if (!sessionId) return
    let stopped = false

    const load = async () => {
      try {
        const res = await getPublicInterpretationResults(sessionId)
        if (!stopped) {
          upsertPersistedItems(res.data || [])
          setError('')
        }
      } catch (err: unknown) {
        if (!stopped) setError(err instanceof Error ? err.message : '加载失败')
      }
    }

    const connect = () => {
      const wsUrl = `${WS_DEFAULTS.BASE_URL.replace(/^http/, 'ws')}/ws/share`
      const ws = new WebSocket(wsUrl)
      wsRef.current = ws
      ws.onopen = () => {
        ws.send(JSON.stringify({ type: 'start', sessionId }))
      }
      ws.onmessage = event => {
        try {
          handleShareMessage(JSON.parse(event.data) as WsMessage)
        } catch (err) {
          console.warn('[ShareView] parse ws message failed:', err)
        }
      }
      ws.onerror = () => {
        setError('实时分享连接异常，正在使用刷新模式')
      }
      ws.onclose = () => {
        if (!stopped) {
          window.setTimeout(connect, 2000)
        }
      }
    }

    void load()
    connect()
    const timer = window.setInterval(load, 2000)
    return () => {
      stopped = true
      window.clearInterval(timer)
      wsRef.current?.close()
      wsRef.current = null
    }
  }, [sessionId])

  const notice = noticeLang ? MUTE_NOTICE[noticeLang] : null

  return (
    <div className="si-root">
      {notice && (
        <div className="si-notice-overlay" onClick={() => setNoticeLang(null)}>
          <div className="si-notice-card" onClick={e => e.stopPropagation()}>
            <div className="si-notice-icon">🔇</div>
            <p className="si-notice-text">{notice.text}</p>
            <button type="button" className="si-notice-btn" onClick={() => setNoticeLang(null)}>
              {notice.button}
            </button>
          </div>
        </div>
      )}
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">聚龙同传</h1>
          <span className="si-brand-sub">实时文本分享</span>
        </div>
      </header>

      <main className="si-main">
        <div className="si-trilingual">
          <div className="si-tri-host-layout">
            <div className="si-tri-toolbar">
              <span className="si-live-indicator is-running" />
              {audioLangs.length > 0 && (
                <div className="si-share-audio-langs">
                  <span className="si-share-audio-label">🔊 收听语言</span>
                  {audioLangs.map(lang => (
                    <button
                      key={lang}
                      type="button"
                      className={`si-share-audio-btn ${selectedLang === lang ? 'is-active' : ''}`}
                      onClick={() => startAudio(lang)}
                    >
                      {LANG_LABELS[lang] || lang}
                    </button>
                  ))}
                  {selectedLang && (
                    <button
                      type="button"
                      className="si-share-audio-btn si-share-audio-btn--mute"
                      onClick={() => { stopAudio(); setSelectedLang(null) }}
                    >
                      关闭声音
                    </button>
                  )}
                </div>
              )}
            </div>

            {error && <p className="si-tri-err">{error}</p>}

            <div className="si-tri-transcript-dock">
              <div className="si-tri-transcript-dock-inner" ref={bodyRef}>
                {items.length === 0 && !currentRecognizing && (
                  <div className="si-tri-empty">等待同传文本...</div>
                )}

                {items.map(item => {
                  const rawId = isUnknownSpeakerId(item.speakerId) ? null : (item.speakerId || null)
                  const currSpeaker = item.speakerName || mappedSpeakerName(item.speakerId, speakerNameMap) || rawId
                  return (
                    <div key={item.id} className={`si-tri-block ${item.isStreaming ? 'si-tri-block--partial' : ''}`}>
                      <div className="si-tri-share-line">
                        {currSpeaker
                          ? <span className="si-tri-line-lang-badge">{currSpeaker}</span>
                          : <span className="si-tri-line-lang-badge si-tri-line-lang-badge--unknown">?</span>
                        }
                        {item.displaySourceText}
                      </div>
                      {item.translations.length === 0 && (
                        <div className="si-tri-share-line si-tri-share-line--translated">
                          翻译中...
                        </div>
                      )}
                      {item.translations.map(translation => (
                        <div key={translation.id} className="si-tri-share-line si-tri-share-line--translated">
                          {translation.displayTranslatedText}
                        </div>
                      ))}
                    </div>
                  )
                })}

                {currentRecognizing && (
                  <div className="si-tri-block si-tri-block--partial">
                    <div className="si-tri-block-latency si-tri-block-latency--streaming">
                      <span>实时识别中</span>
                    </div>
                    <div className="si-tri-share-line">
                      {(() => {
                        const rawCurrId = isUnknownSpeakerId(currentSpeakerId) ? '' : currentSpeakerId
                        const label = currentSpeakerName || mappedSpeakerName(currentSpeakerId, speakerNameMap) || rawCurrId
                        return label
                          ? <span className="si-tri-line-lang-badge">{label}</span>
                          : <span className="si-tri-line-lang-badge si-tri-line-lang-badge--unknown">?</span>
                      })()}
                      {currentRecognizing}
                    </div>
                    {currentLanguage && (
                      <div className="si-tri-share-line si-tri-share-line--translated">
                        翻译中...
                      </div>
                    )}
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
        </div>
      </main>
    </div>
  )
}
