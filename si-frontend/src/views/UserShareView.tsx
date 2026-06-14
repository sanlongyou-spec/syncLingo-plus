import { useEffect, useRef, useState, useCallback } from 'react'
import { useParams } from 'react-router-dom'
import { getActiveSessionForUser, getPublicInterpretationResults, getPublicSessionInfo, reportPublicLatency } from '../api'
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
// 播放积压时加速追赶(变速变调 playbackRate, 不丢音频): 队列空→1.0x, 积压越多越快, 封顶 1.3x
// 合成阶段不再加速(后端一律 1.0x 自然语速), 所有加速都在这里按积压驱动
const CATCHUP_START_SEC = 1.0   // 积压超过此值开始加速
const CATCHUP_FULL_SEC = 4.0    // 积压达到此值用最高速
const CATCHUP_MAX_RATE = 1.35   // 最高播放速率(变调; 1.35x 排空更快, 压客户端积压)
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
  const byId = item.translations.findIndex(t => t.id === translation.id)
  if (byId >= 0) {
    return {
      ...item,
      translations: item.translations.map((t, i) => i === byId ? translation : t),
      isStreaming: false,
    }
  }
  const byText = item.translations.findIndex(t =>
    t.translatedText === translation.translatedText && t.targetLang === translation.targetLang,
  )
  if (byText >= 0) return { ...item, isStreaming: false }
  return { ...item, translations: [...item.translations, translation], isStreaming: false }
}

export default function UserShareView() {
  const { userId = '' } = useParams()
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [items, setItems] = useState<DisplayShareItem[]>([])
  const [currentRecognizing, setCurrentRecognizing] = useState('')
  const [currentLanguage, setCurrentLanguage] = useState('')
  const [isWaiting, setIsWaiting] = useState(true)
  const {
    scrollRef: bodyRef,
    isPaused: isTranscriptAutoScrollPaused,
    scrollToBottom: scrollTranscriptToBottom,
  } = useSmartAutoScroll<HTMLDivElement>([items, currentRecognizing])
  const wsRef = useRef<WebSocket | null>(null)
  const liveIdRef = useRef(-1)
  const activeSessionIdRef = useRef<string | null>(null)
  const wsStoppedRef = useRef(false)

  // ── 音频：选语言 + Opus(WebCodecs) 播放 ──────────────────────
  const [audioLangs, setAudioLangs] = useState<string[]>([])
  const [selectedLang, setSelectedLang] = useState<string | null>(null)
  const [noticeLang, setNoticeLang] = useState<string | null>(null)
  const audioWsRef = useRef<WebSocket | null>(null)
  const selectedLangRef = useRef<string | null>(null)
  const audioCtxRef = useRef<AudioContext | null>(null)
  const audioDestRef = useRef<MediaStreamAudioDestinationNode | null>(null)
  const audioElRef = useRef<HTMLAudioElement | null>(null)
  const audioGenRef = useRef(0)  // increments on every stopAudio; guards stale decoder callbacks
  const decoderRef = useRef<{ decode: (chunk: unknown) => void; close: () => void } | null>(null)
  const scheduleRef = useRef(0)
  const tsRef = useRef(0)
  const pendingSourcesRef = useRef<Set<AudioBufferSourceNode>>(new Set())
  // 端到端延迟测量：句首音标记 + RTT
  const pendingMarkerRef = useRef<{ captureMs: number; arrivalMs: number } | null>(null)
  const rttRef = useRef(0)
  const lastPingSentRef = useRef(0)
  const pingTimerRef = useRef<number | null>(null)
  // 记录两次上报之间的"峰值倍速"(加速在句中才涨, 句首采样会漏掉, 故记峰值)
  const maxRateRef = useRef(1.0)

  const reportLatency = (
    sessionId: string,
    lang: string,
    e2eMs: number,
    captureMs: number,
    rttMs: number,
    tailMs: number,
    outputLatencyMs: number,
    backlogMs: number,
    playbackRateMilli: number,
  ) => {
    // 防御：缺少有效服务端段(captureMs)的样本不上报，避免污染统计(历史上出现过 e2eMs≈2 的坏样本)
    if (!(captureMs > 0) || !(e2eMs >= 200)) {
      return
    }
    const report = { sessionId, lang, e2eMs, captureMs, rttMs, tailMs, outputLatencyMs, backlogMs, playbackRateMilli }
    console.info('[UserShareView] e2e(client)', report)
    try {
      void reportPublicLatency(report).catch(() => { /* ignore */ })
    } catch { /* ignore */ }
  }

  const addAudioLang = (raw?: string) => {
    if (!raw) return
    const c = toCanonicalLang(raw)
    if (c !== 'zh' && c !== 'id' && c !== 'en') return
    const order = ['zh', 'id', 'en']
    setAudioLangs(prev => (prev.includes(c) ? prev : [...prev, c].sort((a, b) => order.indexOf(a) - order.indexOf(b))))
  }

  const stopAudio = useCallback(() => {
    audioGenRef.current++  // invalidate all in-flight decoder output callbacks
    selectedLangRef.current = null
    audioWsRef.current?.close()
    audioWsRef.current = null
    try { decoderRef.current?.close() } catch { /* already closed */ }
    decoderRef.current = null
    pendingSourcesRef.current.forEach(source => { try { source.stop() } catch { /* ended */ } })
    pendingSourcesRef.current.clear()
    audioElRef.current?.pause()
    audioElRef.current = null
    audioDestRef.current = null
    void audioCtxRef.current?.close()
    audioCtxRef.current = null
    scheduleRef.current = 0
    tsRef.current = 0
    if (pingTimerRef.current) { window.clearInterval(pingTimerRef.current); pingTimerRef.current = null }
    pendingMarkerRef.current = null
    rttRef.current = 0
    lastPingSentRef.current = 0
  }, [])

  const startAudio = (lang: string) => {
    const sessionId = activeSessionIdRef.current
    if (!sessionId) return
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
      alert('当前浏览器不支持音频解码（需要 Chrome/Edge 等支持 WebCodecs 的浏览器）')
      return
    }

    const ctx = new AudioContext({ sampleRate: AUDIO_SAMPLE_RATE, latencyHint: 'playback' })
    audioCtxRef.current = ctx
    void ctx.resume()

    // Route through HTMLAudioElement so Bluetooth speakers are used on iOS/Android.
    // Web Audio API's ctx.destination routes to low-latency (voice) output which
    // bypasses Bluetooth; MediaStreamDestination → <audio> uses the media pipeline.
    const dest = ctx.createMediaStreamDestination()
    audioDestRef.current = dest
    const audioEl = new Audio()
    audioEl.srcObject = dest.stream
    audioEl.play().catch(() => { /* requires user gesture — already inside click handler */ })
    audioElRef.current = audioEl

    const myGen = audioGenRef.current  // capture generation for this startAudio call

    const handleAudioData = (data: unknown) => {
      const audioData = data as {
        sampleRate: number
        allocationSize: (opt: { planeIndex: number; format: string }) => number
        copyTo: (dest: Float32Array, opt: { planeIndex: number; format: string }) => void
        close: () => void
      }
      // Guard: if stopAudio() was called after this startAudio, discard stale callback
      if (audioGenRef.current !== myGen) {
        try { audioData.close() } catch { /* already closed */ }
        return
      }
      try {
        const size = audioData.allocationSize({ planeIndex: 0, format: 'f32-planar' })
        const samples = new Float32Array(size / 4)
        audioData.copyTo(samples, { planeIndex: 0, format: 'f32-planar' })
        const buffer = ctx.createBuffer(1, samples.length, audioData.sampleRate)
        buffer.copyToChannel(samples, 0)
        const source = ctx.createBufferSource()
        source.buffer = buffer
        source.connect(dest)  // → MediaStreamDestination → <audio> → Bluetooth
        // Cap schedule horizon: if backlog exceeds 6s, stop all pending sources and reset to prevent
        // AudioBufferSourceNode accumulation (memory leak) and unrecoverable lag after tab throttling.
        if (scheduleRef.current - ctx.currentTime > 6.0) {
          pendingSourcesRef.current.forEach(s => { try { s.stop() } catch { /* already ended */ } })
          pendingSourcesRef.current.clear()
          scheduleRef.current = ctx.currentTime + 0.05
        }
        const backlogSec = Math.max(0, scheduleRef.current - ctx.currentTime)
        const rate = catchupRate(backlogSec)
        source.playbackRate.value = rate
        maxRateRef.current = Math.max(maxRateRef.current, rate)   // 句中峰值倍速
        const startAt = Math.max(ctx.currentTime + 0.08, scheduleRef.current)
        pendingSourcesRef.current.add(source)
        source.onended = () => pendingSourcesRef.current.delete(source)
        source.start(startAt)
        scheduleRef.current = startAt + buffer.duration / rate
        // 该句首音真正开始播放：合成端到端延迟 = 服务端耗时 + RTT/2 + 本地缓冲
        const marker = pendingMarkerRef.current
        if (marker) {
          pendingMarkerRef.current = null
          const scheduledStartWall = performance.now() + (startAt - ctx.currentTime) * 1000
          const outputTimestamp = ctx.getOutputTimestamp()
          const outputPerformanceTime = outputTimestamp.performanceTime ?? 0
          const outputContextTime = outputTimestamp.contextTime ?? 0
          const estimatedOutputStartWall = outputPerformanceTime > 0
            ? outputPerformanceTime + (startAt - outputContextTime) * 1000
            : scheduledStartWall
          const playStartWall = Math.max(scheduledStartWall, estimatedOutputStartWall)
          const outputLatencyMs = Math.max(0, Math.round(playStartWall - scheduledStartWall))
          const tailMs = Math.max(0, Math.round(playStartWall - marker.arrivalMs))
          const e2eMs = Math.round(marker.captureMs + rttRef.current / 2 + tailMs)
          reportLatency(
            sessionId,
            selectedLangRef.current || '',
            e2eMs,
            marker.captureMs,
            Math.round(rttRef.current),
            tailMs,
            outputLatencyMs,
            Math.round(backlogSec * 1000),
            Math.round(maxRateRef.current * 1000),   // 上报"上一段的峰值倍速", 反映真实加速(非句首瞬时)
          )
          maxRateRef.current = rate   // 重置, 开始累计下一段的峰值
        }
      } catch (err) {
        console.warn('[UserShareView] audio render failed:', err)
      } finally {
        audioData.close()
      }
    }

    const decoder = new AudioDecoderCtor({
      output: handleAudioData,
      error: (e: unknown) => console.warn('[UserShareView] audio decode error:', e),
    })
    decoder.configure({ codec: 'opus', sampleRate: AUDIO_SAMPLE_RATE, numberOfChannels: 1 })
    decoderRef.current = decoder

    const wsUrl = `${WS_DEFAULTS.BASE_URL.replace(/^http/, 'ws')}/ws/share-audio?sessionId=${encodeURIComponent(sessionId)}&lang=${canonical}`
    const ws = new WebSocket(wsUrl)
    ws.binaryType = 'arraybuffer'
    audioWsRef.current = ws
    ws.onopen = () => {
      lastPingSentRef.current = 0
      pingTimerRef.current = window.setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          lastPingSentRef.current = performance.now()
          ws.send(new Uint8Array([0x03]))
        }
      }, 3000)
    }
    ws.onmessage = event => {
      if (!(event.data instanceof ArrayBuffer) || decoderRef.current !== decoder) return
      const view = new Uint8Array(event.data)
      if (view.length === 0) return
      const type = view[0]
      if (type === 0x03) {           // pong：算 RTT
        if (lastPingSentRef.current > 0) rttRef.current = performance.now() - lastPingSentRef.current
        return
      }
      if (type === 0x02) {           // 句首音标记：服务端已耗时
        if (event.data.byteLength >= 5) {
          const captureMs = new DataView(event.data).getInt32(1)
          pendingMarkerRef.current = { captureMs, arrivalMs: performance.now() }
        }
        return
      }
      // type === 0x01 音频：去掉首字节后解码
      try {
        decoder.decode(new EncodedAudioChunkCtor({ type: 'key', timestamp: tsRef.current, data: event.data.slice(1) }))
        tsRef.current += 20000
      } catch (err) {
        console.warn('[UserShareView] decode chunk failed:', err)
      }
    }
    ws.onclose = () => {
      if (audioWsRef.current === ws
        && selectedLangRef.current === canonical
        && activeSessionIdRef.current === sessionId) {
        window.setTimeout(() => {
          if (audioWsRef.current === ws
            && selectedLangRef.current === canonical
            && activeSessionIdRef.current === sessionId) {
            startAudio(canonical)
          }
        }, 2000)
      }
    }
  }

  useEffect(() => {
    activeSessionIdRef.current = activeSessionId
  }, [activeSessionId])

  const clearSessionState = useCallback(() => {
    setItems([])
    setCurrentRecognizing('')
    setCurrentLanguage('')
    liveIdRef.current = -1
  }, [])

  const handleWsMessage = (msg: WsMessage) => {
    addAudioLang(msg.language)
    addAudioLang(msg.targetLanguage)
    switch (msg.type) {
      case 'recognizing':
        setCurrentRecognizing(msg.text || '')
        setCurrentLanguage(msg.language || '')
        break
      case 'recognized': {
        if (!msg.text) return
        setCurrentRecognizing('')
        setCurrentLanguage(msg.language || '')
        const id = liveIdRef.current--
        setItems(prev => [...prev, {
          id,
          sourceText: msg.text || '',
          sourceLang: msg.language,
          displaySourceText: msg.text || '',
          translations: [],
          isStreaming: true,
          speakerId: msg.speakerId || undefined,
          speakerName: msg.speakerName || undefined,
        }])
        break
      }
      case 'translated': {
        const sourceText = msg.text || ''
        const translatedText = msg.translatedText || ''
        if (!sourceText && !translatedText) return
        setItems(prev => {
          const translation: DisplayShareTranslation = {
            id: liveIdRef.current--,
            translatedText,
            targetLang: msg.targetLanguage,
            displayTranslatedText: translatedText,
          }
          const matchedIdx = sourceText
            ? [...prev].reverse().findIndex(item => item.sourceText === sourceText)
            : -1
          if (matchedIdx >= 0) {
            const index = prev.length - 1 - matchedIdx
            return prev.map((item, i) => i === index ? upsertTranslation(item, translation) : item)
          }
          return [...prev, {
            id: liveIdRef.current--,
            sourceText,
            displaySourceText: sourceText,
            translations: [translation],
            isStreaming: false,
            speakerId: msg.speakerId || undefined,
            speakerName: msg.speakerName || undefined,
          }]
        })
        break
      }
      case 'started':
        clearSessionState()
        break
      case 'stopped':
        setCurrentRecognizing('')
        break
    }
  }

  const connectWs = useCallback((sessionId: string) => {
    wsStoppedRef.current = false
    const wsUrl = `${WS_DEFAULTS.BASE_URL.replace(/^http/, 'ws')}/ws/share`
    const ws = new WebSocket(wsUrl)
    wsRef.current = ws
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'start', sessionId }))
    }
    ws.onmessage = event => {
      try {
        handleWsMessage(JSON.parse(event.data) as WsMessage)
      } catch (err) {
        console.warn('[UserShareView] parse ws message failed:', err)
      }
    }
    ws.onclose = () => {
      if (!wsStoppedRef.current && activeSessionIdRef.current === sessionId) {
        window.setTimeout(() => connectWs(sessionId), 2000)
      }
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const disconnectWs = useCallback(() => {
    wsStoppedRef.current = true
    wsRef.current?.close()
    wsRef.current = null
  }, [])

  // Poll for active session
  useEffect(() => {
    if (!userId) return
    let pollStopped = false
    let resultTimer: ReturnType<typeof setInterval> | null = null

    const poll = async () => {
      try {
        const res = await getActiveSessionForUser(Number(userId))
        if (pollStopped) return
        const newSessionId = res.data || null
        const prevSessionId = activeSessionIdRef.current

        if (newSessionId !== prevSessionId) {
          disconnectWs()
          clearSessionState()
          stopAudio()
          setAudioLangs([])
          setSelectedLang(null)
          if (resultTimer) {
            window.clearInterval(resultTimer)
            resultTimer = null
          }
          setActiveSessionId(newSessionId)
          activeSessionIdRef.current = newSessionId
          setIsWaiting(!newSessionId)

          if (newSessionId) {
            connectWs(newSessionId)
            getPublicSessionInfo(newSessionId)
              .then(infoRes => {
                if (!pollStopped && activeSessionIdRef.current === newSessionId) {
                  (infoRes.data?.enabledLanguages ?? []).forEach(addAudioLang)
                }
              })
              .catch(() => { /* 兜底靠文本消息推断语言 */ })
            // Load persisted results for this session
            const load = async () => {
              try {
                const r = await getPublicInterpretationResults(newSessionId)
                if (!pollStopped && activeSessionIdRef.current === newSessionId) {
                  const list = r.data || []
                  list.forEach(item => { addAudioLang(item.sourceLang); addAudioLang(item.targetLang) })
                  setItems(prev => {
                    const next = [...prev]
                    list.forEach(item => {
                      const translation: DisplayShareTranslation = {
                        id: item.id,
                        translatedText: item.translatedText,
                        targetLang: item.targetLang,
                        displayTranslatedText: item.translatedText,
                      }
                      const byTransId = next.findIndex(e => e.translations.some(t => t.id === item.id))
                      if (byTransId >= 0) return
                      const bySource = next.findIndex(e => e.sourceText === item.sourceText)
                      if (bySource >= 0) {
                        next[bySource] = upsertTranslation(next[bySource], translation)
                        return
                      }
                      next.push(toDisplayItem(item))
                    })
                    return next
                  })
                }
              } catch { /* ignore load error */ }
            }
            void load()
            resultTimer = window.setInterval(load, 3000)
          }
        }
      } catch { /* ignore poll error */ }
    }

    void poll()
    const pollTimer = window.setInterval(poll, 5000)

    return () => {
      pollStopped = true
      window.clearInterval(pollTimer)
      if (resultTimer) window.clearInterval(resultTimer)
      disconnectWs()
      stopAudio()
    }
  }, [userId, connectWs, disconnectWs, clearSessionState, stopAudio])

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
              <span className={`si-live-indicator ${isWaiting ? '' : 'is-running'}`} />
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

            <div className="si-tri-transcript-dock">
              <div className="si-tri-transcript-dock-inner" ref={bodyRef}>
                {isWaiting && (
                  <div className="si-tri-empty">等待同传开始...</div>
                )}

                {!isWaiting && items.length === 0 && !currentRecognizing && (
                  <div className="si-tri-empty">等待同传文本...</div>
                )}

                {items.map(item => (
                  <div key={item.id} className={`si-tri-block ${item.isStreaming ? 'si-tri-block--partial' : ''}`}>
                    <div className="si-tri-share-line">
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
                ))}

                {currentRecognizing && (
                  <div className="si-tri-block si-tri-block--partial">
                    <div className="si-tri-block-latency si-tri-block-latency--streaming">
                      <span>实时识别中</span>
                    </div>
                    <div className="si-tri-share-line">
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
