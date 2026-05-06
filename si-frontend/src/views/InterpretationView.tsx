/**
 * 聚龙同传主页面
 * 音频采集 → ASR 自动语种检测 → 翻译 → TTS，全自动双向同传
 * 音频路由：中文原音 + 中文TTS → VoiceMeeter Input；印尼语TTS → VoiceMeeter Aux Input
 */
import React, { useState, useCallback, useEffect, useRef } from 'react'
import { startInterpretation, stopInterpretation, getUserVoice } from '../api'
import { AsrWebSocket } from '../lib/websocket'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { AUDIO_DEFAULTS, VOICEMEETER } from '../api/constants'
import { STORAGE_KEYS, ROUTES, LANGUAGE } from '../constants'
import type { WsMessage } from '../types'
import './InterpretationView.css'

interface TranscriptItem {
  id: string
  source: string
  translated: string
  language: string
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
  const [captureSilentWarn, setCaptureSilentWarn] = useState(false)

  const wsRef = useRef<AsrWebSocket | null>(null)
  const audioRef = useRef<AudioCapture | null>(null)

  // 流式播放用：每次播放前都切换 sinkId
  const streamContextRef = useRef<AudioContext | null>(null)
  const streamDestZhRef = useRef<MediaStreamAudioDestinationNode | null>(null)
  const streamDestIdRef = useRef<MediaStreamAudioDestinationNode | null>(null)
  const streamAudioElZhRef = useRef<HTMLAudioElement | null>(null)
  const streamAudioElIdRef = useRef<HTMLAudioElement | null>(null)
  // 每个声道的音频排队结束时间，防止 chunk 并发播放产生双音色
  const scheduleTimeZhRef = useRef(0)
  const scheduleTimeIdRef = useRef(0)
  const pendingSourcesZhRef = useRef<Set<AudioBufferSourceNode>>(new Set())
  const pendingSourcesIdRef = useRef<Set<AudioBufferSourceNode>>(new Set())
  const detectedLangRef = useRef('')
  const prevDetectedLangRef = useRef('')
  const capturePipelineStartedAtRef = useRef(0)
  const lastLoudAudioAtRef = useRef(0)
  const bodyRef = useRef<HTMLDivElement>(null)

  useEffect(() => { detectedLangRef.current = detectedLang }, [detectedLang])

  // 语言切换时清空新语言声道，立刻跟上原音
  useEffect(() => {
    if (!isRunning || !detectedLang) return
    const prev = prevDetectedLangRef.current
    prevDetectedLangRef.current = detectedLang
    if (!prev || prev === detectedLang) return
    const newIsZh = !detectedLang.startsWith('id')
    const pending = newIsZh ? pendingSourcesZhRef.current : pendingSourcesIdRef.current
    pending.forEach(s => { try { s.stop() } catch { /* already ended */ } })
    pending.clear()
    if (newIsZh) {
      scheduleTimeZhRef.current = streamContextRef.current?.currentTime ?? 0
    } else {
      scheduleTimeIdRef.current = streamContextRef.current?.currentTime ?? 0
    }
    console.log('[InterpretationView] 语言切换 %s→%s，已清空%s声道', prev, detectedLang, newIsZh ? '中文' : '印尼语')
  }, [detectedLang, isRunning])

  useEffect(() => {
    getUserVoice(userId)
      .then(res => {
        if (res.data?.voiceId) setVoiceId(res.data.voiceId)
      })
      .catch((err: unknown) => {
        console.warn('[InterpretationView] getUserVoice failed:', err)
      })

    return () => {
      wsRef.current?.close()
      audioRef.current?.stop()
    }
  }, [userId])

  // 静音检测
  useEffect(() => {
    if (!isRunning) {
      setCaptureSilentWarn(false)
      return
    }
    const iv = window.setInterval(() => {
      const start = capturePipelineStartedAtRef.current
      const last = lastLoudAudioAtRef.current
      const now = Date.now()
      if (start === 0 || now - start < 5000) return
      if (last === 0 || now - last > 3500) setCaptureSilentWarn(true)
    }, 2000)
    return () => clearInterval(iv)
  }, [isRunning])

  // 自动滚动到底部
  useEffect(() => {
    const el = bodyRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [transcripts, currentSource])

  // 按设备标签匹配 VoiceMeeter 设备并设置 sinkId
  const applyVoiceMeeterSinks = useCallback(async () => {
    const setSink = async (el: HTMLAudioElement | null, deviceId: string) => {
      if (!el || !('setSinkId' in el)) return
      try { await (el as any).setSinkId(deviceId) } catch (e) {
        console.warn('[InterpretationView] setSinkId failed:', e)
      }
    }
    try {
      // Chrome 在没有麦克风授权时 audiooutput 设备的 label 为空字符串，
      // 无法按名称匹配 VoiceMeeter。临时申请麦克风权限解锁标签，然后立即释放。
      let tempStream: MediaStream | null = null
      try {
        tempStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false })
      } catch {
        console.warn('[InterpretationView] 麦克风授权失败，设备标签可能为空')
      } finally {
        tempStream?.getTracks().forEach(t => t.stop())
      }

      const devices = await navigator.mediaDevices.enumerateDevices()
      const outputs = devices.filter(d => d.kind === 'audiooutput')
      console.log('[InterpretationView] 可用输出设备:\n' + outputs.map(d => `  ${d.label || '(无标签)'} [${d.deviceId}]`).join('\n'))

      const zhDevice = outputs.find(d =>
        d.label.toLowerCase().includes(VOICEMEETER.ZH_DEVICE_LABEL.toLowerCase()) &&
        !d.label.toLowerCase().includes('aux')
      )
      const idDevice = outputs.find(d =>
        d.label.toLowerCase().includes(VOICEMEETER.ID_DEVICE_LABEL.toLowerCase())
      )

      if (zhDevice) {
        await setSink(streamAudioElZhRef.current, zhDevice.deviceId)
        console.log('[InterpretationView] 中文声道 →', zhDevice.label)
      } else {
        console.warn('[InterpretationView] 未找到', VOICEMEETER.ZH_DEVICE_LABEL, '，中文原音将输出到默认设备')
      }
      if (idDevice) {
        await setSink(streamAudioElIdRef.current, idDevice.deviceId)
        console.log('[InterpretationView] 印尼语声道 →', idDevice.label)
      } else {
        console.warn('[InterpretationView] 未找到', VOICEMEETER.ID_DEVICE_LABEL, '，印尼语TTS将输出到默认设备')
      }
    } catch (err) {
      console.warn('[InterpretationView] enumerateDevices failed:', err)
    }
  }, [])

  // 初始化流式播放用的 AudioElement（中文 / 印尼语独立声道）
  const initStreamAudio = useCallback(async () => {
    if (!streamContextRef.current) {
      streamContextRef.current = new AudioContext()
    }
    const ctx = streamContextRef.current
    if (ctx.state === 'suspended') {
      await ctx.resume()
    }

    if (!streamDestZhRef.current) {
      streamDestZhRef.current = ctx.createMediaStreamDestination()
      streamAudioElZhRef.current = new Audio()
      streamAudioElZhRef.current.srcObject = streamDestZhRef.current.stream
      streamAudioElZhRef.current.volume = 1.0
      await streamAudioElZhRef.current.play().catch(console.warn)
    }
    if (!streamDestIdRef.current) {
      streamDestIdRef.current = ctx.createMediaStreamDestination()
      streamAudioElIdRef.current = new Audio()
      streamAudioElIdRef.current.srcObject = streamDestIdRef.current.stream
      streamAudioElIdRef.current.volume = 1.0
      await streamAudioElIdRef.current.play().catch(console.warn)
    }
    console.log('[InterpretationView] 流式音频元素初始化完成')
  }, [])

  // 流式播放 PCM（实时播放，使用两个独立的 AudioElement 分别对应中文/印尼语）
  // 同步函数：initStreamAudio 已在 startSession 中 await 完成，此处直接使用 ref
  const playStreamPcm = useCallback((pcmData: Int16Array, sampleRate: number, lang: string) => {
    const isZh = !lang.startsWith('id')
    const dest = isZh ? streamDestZhRef.current : streamDestIdRef.current
    const ctx = streamContextRef.current
    if (!dest || !ctx) {
      console.warn('[InterpretationView] 音频未初始化，丢弃 PCM 块')
      return
    }
    const scheduleRef = isZh ? scheduleTimeZhRef : scheduleTimeIdRef
    const pendingRef = isZh ? pendingSourcesZhRef : pendingSourcesIdRef
    playStreamPcmDirect(pcmData, sampleRate, ctx, dest, scheduleRef, pendingRef)
  }, [])

  // 直接播放 PCM 到 destination（串行调度，避免 chunk 并发双音色）
  const playStreamPcmDirect = (
    pcmData: Int16Array,
    sampleRate: number,
    ctx: AudioContext,
    dest: MediaStreamAudioDestinationNode,
    scheduleRef: React.MutableRefObject<number>,
    pendingRef: React.MutableRefObject<Set<AudioBufferSourceNode>>,
  ) => {
    const float32Data = new Float32Array(pcmData.length)
    for (let i = 0; i < pcmData.length; i++) {
      float32Data[i] = pcmData[i] / 32768.0
    }
    const buffer = ctx.createBuffer(1, pcmData.length, sampleRate)
    buffer.copyToChannel(float32Data, 0)
    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(dest)
    const startAt = Math.max(ctx.currentTime, scheduleRef.current)
    const queueWaitMs = Math.round((startAt - ctx.currentTime) * 1000)
    console.log('[TTS] schedule chunk: queueWait=', queueWaitMs, 'ms, duration=', Math.round(buffer.duration * 1000), 'ms')
    pendingRef.current.add(source)
    source.addEventListener('ended', () => {
      pendingRef.current.delete(source)
    })
    source.start(startAt)
    scheduleRef.current = startAt + buffer.duration
  }

  // TTS 播放（24000Hz，语言由后端 targetLanguage 字段确定）
  const playPcm = useCallback((pcmData: Int16Array, targetLang: string) => {
    console.log('[InterpretationView] playPcm, targetLang=', targetLang)
    playStreamPcm(pcmData, 24000, targetLang)
  }, [playStreamPcm])

  // 原音播放（16000Hz，语言从 detectedLangRef 读取，默认中文声道）
  const playSourcePcm = useCallback((pcmData: Int16Array) => {
    const lang = detectedLangRef.current || 'zh'
    console.log('[SRC] playSourcePcm, lang=', lang, 'samples=', pcmData.length, 'zhEl.paused=', streamAudioElZhRef.current?.paused)
    playStreamPcm(pcmData, AUDIO_DEFAULTS.SAMPLE_RATE, lang)
  }, [playStreamPcm])

  // 处理 WebSocket 消息
  const handleWsMessage = useCallback((msg: WsMessage) => {
    console.log('[InterpretationView] WS message:', msg.type, msg.text, msg.language)
    switch (msg.type) {
      case 'recognizing':
        setCurrentSource(msg.text || '')
        if (msg.language) {
          detectedLangRef.current = msg.language
          setDetectedLang(msg.language)
        }
        break
      case 'recognized':
        if (msg.text) {
          setTranscripts(prev => [
            ...prev,
            {
              id: `${Date.now()}-${Math.random()}`,
              source: msg.text || '',
              translated: msg.translatedText || '',
              language: msg.language || '',
              timestamp: Date.now(),
            },
          ])
          setCurrentSource('')
          setCurrentTranslated('')
        }
        break
      case 'translated':
        setCurrentTranslated(msg.translatedText || '')
        setTranscripts(prev => {
          if (prev.length === 0) return prev
          const last = prev[prev.length - 1]
          if (last.translated) return prev
          return [...prev.slice(0, -1), { ...last, translated: msg.translatedText || '' }]
        })
        break
      case 'tts_audio': {
        const ttsReceiveMs = performance.now()
        console.log('[TTS] chunk received, audioBase64 length=', msg.audioBase64 ? msg.audioBase64.length : 0, ', lang=', msg.targetLanguage)
        if (msg.audioBase64 && msg.targetLanguage) {
          // 丢弃方向与当前说话人不匹配的 TTS 块（语言切换后旧 TTS 仍在传输时）
          const curLang = detectedLangRef.current
          if (curLang) {
            const expectedTarget = curLang.startsWith('id') ? 'zh' : 'id'
            if (!msg.targetLanguage.toLowerCase().startsWith(expectedTarget)) {
              console.log('[TTS] discarded stale chunk, target=%s expected=%s', msg.targetLanguage, expectedTarget)
              break
            }
          }
          try {
            const binaryStr = atob(msg.audioBase64)
            const bytes = new Uint8Array(binaryStr.length)
            for (let i = 0; i < binaryStr.length; i++) {
              bytes[i] = binaryStr.charCodeAt(i)
            }
            const pcmData = new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 2)
            console.log('[TTS] decoded pcm, samples=', pcmData.length, ', decodeMs=', Math.round(performance.now() - ttsReceiveMs), 'ms')
            playPcm(pcmData, msg.targetLanguage)
          } catch (err) {
            console.error('[InterpretationView] Failed to play TTS audio:', err)
          }
        }
        break
      }
      case 'started':
        setIsRunning(true)
        setError('')
        detectedLangRef.current = msg.language || ''
        setDetectedLang(msg.language || '')
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
      const res = await startInterpretation({
        userId,
        sourceLang: LANGUAGE.AUTO,
        targetLang: LANGUAGE.ID_ID,
        voiceId: voiceId || undefined,
      })
      const sid = res.data
      setSessionId(sid)

      const ws = new AsrWebSocket()
      wsRef.current = ws
      await ws.connect(sid)
      ws.onMessage(handleWsMessage)
      ws.start({ sessionId: sid, sourceLang: LANGUAGE.AUTO, targetLang: LANGUAGE.ID_ID, voiceId: voiceId || undefined })

      // 初始化流式音频播放器，然后路由到 VoiceMeeter
      await initStreamAudio()
      await applyVoiceMeeterSinks()

      const audio = new AudioCapture({
        sampleRate: AUDIO_DEFAULTS.SAMPLE_RATE,
        onData: pcm => {
          ws.sendAudio(sid, pcmToBase64(pcm))
          playSourcePcm(pcm)
          let sumSq = 0
          for (let i = 0; i < pcm.length; i++) sumSq += pcm[i] * pcm[i]
          const rms = Math.sqrt(sumSq / pcm.length)
          if (rms > 0.001) lastLoudAudioAtRef.current = Date.now()
        },
      })
      audioRef.current = audio
      await audio.start()

      // getDisplayMedia 授权后重新枚举，标签此时通常可见，再次应用路由
      await applyVoiceMeeterSinks()

      capturePipelineStartedAtRef.current = Date.now()
      lastLoudAudioAtRef.current = 0
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '启动失败')
    } finally {
      setIsLoading(false)
    }
  }

  const stopSession = async () => {
    if (!sessionId) return
    audioRef.current?.stop()
    wsRef.current?.stop(sessionId)
    await stopInterpretation(sessionId).catch((err: unknown) => {
      console.warn('[InterpretationView] stopInterpretation failed:', err)
    })
    wsRef.current?.close()
    streamAudioElZhRef.current?.pause()
    streamAudioElIdRef.current?.pause()
    streamAudioElZhRef.current = null
    streamAudioElIdRef.current = null
    streamDestZhRef.current = null
    streamDestIdRef.current = null
    streamContextRef.current?.close()
    streamContextRef.current = null
    pendingSourcesZhRef.current.forEach(s => { try { s.stop() } catch { /* already ended */ } })
    pendingSourcesIdRef.current.forEach(s => { try { s.stop() } catch { /* already ended */ } })
    pendingSourcesZhRef.current.clear()
    pendingSourcesIdRef.current.clear()
    scheduleTimeZhRef.current = 0
    scheduleTimeIdRef.current = 0
    prevDetectedLangRef.current = ''
    wsRef.current = null
    audioRef.current = null
    setSessionId(null)
    setIsRunning(false)
    setCurrentSource('')
    setCurrentTranslated('')
    setDetectedLang('')
    setCaptureSilentWarn(false)
    capturePipelineStartedAtRef.current = 0
    lastLoudAudioAtRef.current = 0
  }

  const langLabel = (code: string) => {
    const labels: Record<string, string> = {
      'zh': '中文',
      'zh-CN': '中文',
      'en': 'English',
      'en-US': 'English',
      'id': 'Indonesia',
      'id-ID': 'Indonesia',
    }
    return labels[code] || code
  }

  const langBadgeClass = (code: string) => {
    if (code.startsWith('zh')) return 'si-tri-line-lang-badge--zh'
    if (code.startsWith('en')) return 'si-tri-line-lang-badge--en'
    if (code.startsWith('id')) return 'si-tri-line-lang-badge--id'
    return ''
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">聚龙同传</h1>
          <span className="si-brand-sub">中文 / Bahasa Indonesia</span>
        </div>
        <div className="si-topbar-right">
          {voiceId && (
            <span className="si-voice-badge">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm-1-9c0-.55.45-1 1-1s1 .45 1 1v6c0 .55-.45 1-1 1s-1-.45-1-1V5zm6 6c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
              </svg>
              音色已加载
            </span>
          )}
          <button
            className="si-pill-btn"
            onClick={() => { window.location.hash = ROUTES.VOICE_CLONE }}
          >
            <IconLightbulb />
            音色克隆
          </button>
        </div>
      </header>

      <main className="si-main">
        <div className="si-trilingual">
          <div className="si-tri-host-layout">
            <div className="si-tri-toolbar">
              <span className="si-tri-title">实时同传</span>
              {detectedLang ? (
                <span className="si-tri-detected-lang" title="检测到的语种">
                  {langLabel(detectedLang)}
                </span>
              ) : null}
            </div>

            {error && (
              <p className="si-tri-err" style={{ whiteSpace: 'pre-wrap' }}>{error}</p>
            )}

            <div className="si-tri-transcript-dock">
              <div className="si-tri-transcript-dock-inner" ref={bodyRef}>
                {transcripts.length === 0 && !isRunning && !currentSource && (
                  <div className="si-tri-empty">
                    点击「开始同传」，在弹窗中允许麦克风权限即可开始实时翻译
                  </div>
                )}

                {/* 历史记录块 */}
                {transcripts.map(item => (
                  <div key={item.id} className="si-tri-block">
                    <div className="si-tri-block-line">
                      <span className={`si-tri-line-lang-badge ${langBadgeClass(item.language)}`}>
                        {langLabel(item.language)}
                      </span>
                      <div className="si-tri-block-line-body">
                        <span className="si-tri-line-plain">{item.source}</span>
                      </div>
                    </div>
                    <div className="si-tri-block-line">
                      <span className={`si-tri-line-lang-badge ${item.language.startsWith('id') ? 'si-tri-line-lang-badge--zh' : 'si-tri-line-lang-badge--id'}`}>
                        {item.language.startsWith('id') ? '中文' : 'Indonesia'}
                      </span>
                      <div className="si-tri-block-line-body">
                        <span className="si-tri-line-plain">{item.translated}</span>
                      </div>
                    </div>
                  </div>
                ))}

                {/* 实时识别块 */}
                {isRunning && currentSource && (
                  <div className="si-tri-block si-tri-block--partial">
                    <div className="si-tri-block-latency si-tri-block-latency--streaming">
                      <IconHeadset />
                      <span>实时识别中</span>
                    </div>
                    <div className="si-tri-block-line">
                      <span className="si-tri-line-lang-badge si-tri-line-lang-badge--partial">
                        {detectedLang ? langLabel(detectedLang) : '检测中'}
                      </span>
                      <div className="si-tri-block-line-body">
                        <span className="si-tri-partial-live-text">{currentSource}</span>
                      </div>
                    </div>
                    <div className="si-tri-block-line">
                      <span className={`si-tri-line-lang-badge si-tri-line-lang-badge--partial ${detectedLang.startsWith('id') ? 'si-tri-line-lang-badge--zh' : 'si-tri-line-lang-badge--id'}`}>
                        {detectedLang.startsWith('id') ? '中文' : 'Indonesia'}
                      </span>
                      <div className="si-tri-block-line-body">
                        <span className="si-tri-seg-pending">
                          {currentTranslated ? `实时: ${currentTranslated.slice(-40)}` : '翻译中...'}
                        </span>
                      </div>
                    </div>
                  </div>
                )}

                {/* 等待音频 */}
                {isRunning && !currentSource && transcripts.length === 0 && (
                  <div className="si-tri-empty si-tri-empty--capture-wait">
                    <div>等待语音...</div>
                    {captureSilentWarn && (
                      <p className="si-tri-capture-warn">
                        已连接但几乎无音频。请检查：① 麦克风没有被静音；② 浏览器已授权麦克风权限；
                        ③ 说话声音足够大。
                      </p>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>

          <div className="si-tri-footer">
            {!isRunning
              ? <button type="button" className="si-tri-btn" onClick={startSession} disabled={isLoading}>
                  {isLoading ? '启动中...' : '开始同传'}
                </button>
              : <button type="button" className="si-tri-btn si-tri-btn--stop" onClick={stopSession}>
                  停止同传
                </button>
            }
          </div>
        </div>

      </main>
    </div>
  )
}
