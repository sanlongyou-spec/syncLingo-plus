/**
 * 聚龙同传主页面
 * 音频采集 → ASR 自动语种检测 → 翻译 → TTS，全自动双向同传
 */
import React, { useState, useCallback, useEffect, useRef } from 'react'
import { startInterpretation, stopInterpretation, getUserVoice } from '../api'
import { AsrWebSocket } from '../lib/websocket'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { AUDIO_DEFAULTS } from '../api/constants'
import { STORAGE_KEYS, ROUTES, LANGUAGE } from '../constants'
import type { WsMessage } from '../types'
import './InterpretationView.css'

// 音频设备配置（中文声道 / 印尼语声道，原音和 TTS 共用同一套设备）
interface AudioDeviceConfig {
  zhDeviceId: string | null
  idDeviceId: string | null
}

const STORAGE_KEY_ZH_DEVICE = 'si-tts-zh-device'
const STORAGE_KEY_ID_DEVICE = 'si-tts-id-device'

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

function IconSettings() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M19.14 12.94c.04-.31.06-.63.06-.94 0-.31-.02-.63-.06-.94l2.03-1.58a.49.49 0 00.12-.61l-1.92-3.32a.49.49 0 00-.59-.22l-2.39.96a7.09 7.09 0 00-1.62-.94l-.36-2.54a.484.484 0 00-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 00-.59.22L2.74 8.87a.49.49 0 00.12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58a.49.49 0 00-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.48-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 00-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
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
  const [audioDevices, setAudioDevices] = useState<MediaDeviceInfo[]>([])
  const [audioConfig, setAudioConfig] = useState<AudioDeviceConfig>({
    zhDeviceId: localStorage.getItem(STORAGE_KEY_ZH_DEVICE) || null,
    idDeviceId: localStorage.getItem(STORAGE_KEY_ID_DEVICE) || null,
  })
  const [showDeviceSettings, setShowDeviceSettings] = useState(false)

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
  const detectedLangRef = useRef('')
  const audioConfigRef = useRef(audioConfig)
  const capturePipelineStartedAtRef = useRef(0)
  const lastLoudAudioAtRef = useRef(0)
  const bodyRef = useRef<HTMLDivElement>(null)

  // 保持 ref 与 state 同步，让 onData/onChunk 等闭包能读到最新值
  useEffect(() => { detectedLangRef.current = detectedLang }, [detectedLang])
  useEffect(() => { audioConfigRef.current = audioConfig }, [audioConfig])

  useEffect(() => {
    getUserVoice(userId)
      .then(res => {
        if (res.data?.voiceId) {
          setVoiceId(res.data.voiceId)
        }
      })
      .catch((err: unknown) => {
        console.warn('[InterpretationView] getUserVoice failed:', err)
      })

    // 枚举音频输出设备
    const enumerateDevices = async () => {
      try {
        const devices = await navigator.mediaDevices.enumerateDevices()
        setAudioDevices(devices.filter(d => d.kind === 'audiooutput'))
      } catch (err) {
        console.warn('[InterpretationView] enumerateDevices failed:', err)
      }
    }
    enumerateDevices()

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

  // 初始化流式播放用的 AudioElement（每个语言独立）
  const initStreamAudio = useCallback(async (config: AudioDeviceConfig) => {
    // VoiceMeeter Input (中文)
    if (!streamDestZhRef.current) {
      streamContextRef.current = streamContextRef.current || new AudioContext()
      streamDestZhRef.current = streamContextRef.current.createMediaStreamDestination()
      streamAudioElZhRef.current = new Audio()
      streamAudioElZhRef.current.srcObject = streamDestZhRef.current.stream
      streamAudioElZhRef.current.volume = 1.0
      await streamAudioElZhRef.current.play().catch(console.warn)
    }
    // Voice Aux Input (印尼语)
    if (!streamDestIdRef.current) {
      if (!streamContextRef.current) {
        streamContextRef.current = new AudioContext()
        streamDestIdRef.current = streamContextRef.current.createMediaStreamDestination()
        streamAudioElIdRef.current = new Audio()
        streamAudioElIdRef.current.srcObject = streamDestIdRef.current.stream
        streamAudioElIdRef.current.volume = 1.0
        await streamAudioElIdRef.current.play().catch(console.warn)
      } else {
        streamDestIdRef.current = streamContextRef.current.createMediaStreamDestination()
        streamAudioElIdRef.current = new Audio()
        streamAudioElIdRef.current.srcObject = streamDestIdRef.current.stream
        streamAudioElIdRef.current.volume = 1.0
        await streamAudioElIdRef.current.play().catch(console.warn)
      }
    }
    // 设置 setSinkId
    const setSink = async (el: HTMLAudioElement | null, deviceId: string | null) => {
      if (!el || !('setSinkId' in el)) return
      try {
        await (el as any).setSinkId(deviceId ?? '')
      } catch (err) {
        console.warn('[InterpretationView] setSinkId failed:', err)
      }
    }
    await setSink(streamAudioElZhRef.current, config.zhDeviceId)
    await setSink(streamAudioElIdRef.current, config.idDeviceId)
    console.log('[InterpretationView] 流式音频元素初始化完成, zhDeviceId=', config.zhDeviceId, ', idDeviceId=', config.idDeviceId)
  }, [])

  // 流式播放 PCM（实时播放，使用两个独立的 AudioElement 分别对应中文/印尼语）
  const playStreamPcm = useCallback(async (pcmData: Int16Array, sampleRate: number, lang: string, config: AudioDeviceConfig) => {
    const isZh = !lang.startsWith('id')

    // 确保流式音频元素已初始化
    if (!streamDestZhRef.current || !streamDestIdRef.current) {
      await initStreamAudio(config)
    }

    const dest = isZh ? streamDestZhRef.current : streamDestIdRef.current
    const ctx = streamContextRef.current
    if (!dest || !ctx) return

    const scheduleRef = isZh ? scheduleTimeZhRef : scheduleTimeIdRef
    playStreamPcmDirect(pcmData, sampleRate, ctx, dest, scheduleRef)
  }, [initStreamAudio])

  // 直接播放 PCM 到 destination（串行调度，避免 chunk 并发双音色）
  const playStreamPcmDirect = (
    pcmData: Int16Array,
    sampleRate: number,
    ctx: AudioContext,
    dest: MediaStreamAudioDestinationNode,
    scheduleRef: React.MutableRefObject<number>,
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
    // 在前一个 chunk 结束后才开始，消除 chunk 并发导致的双音色
    const startAt = Math.max(ctx.currentTime, scheduleRef.current)
    source.start(startAt)
    scheduleRef.current = startAt + buffer.duration
  }

  // TTS 播放（24000Hz，语言由后端 targetLanguage 字段确定）
  const playPcm = useCallback(async (pcmData: Int16Array, targetLang: string) => {
    const config = audioConfigRef.current
    console.log('[InterpretationView] playPcm called, targetLang=', targetLang, ', config=', config)
    await playStreamPcm(pcmData, 24000, targetLang, config)
  }, [playStreamPcm])

  // 原音播放（16000Hz，语言从 detectedLangRef 读取，默认中文声道）
  const playSourcePcm = useCallback(async (pcmData: Int16Array) => {
    const lang = detectedLangRef.current || 'zh'
    console.log('[InterpretationView] playSourcePcm called, lang=', lang, ', pcmLen=', pcmData.length)
    await playStreamPcm(pcmData, AUDIO_DEFAULTS.SAMPLE_RATE, lang, audioConfigRef.current)
  }, [playStreamPcm])

  // 处理 WebSocket 消息
  const handleWsMessage = useCallback((msg: WsMessage) => {
    console.log('[InterpretationView] WS message:', msg.type, msg.text, msg.language)
    switch (msg.type) {
      case 'recognizing':
        setCurrentSource(msg.text || '')
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
        // 收到 TTS PCM 数据，播放
        console.log('[InterpretationView] received tts_audio, audioBase64 length=', msg.audioBase64 ? msg.audioBase64.length : 0, ', targetLanguage=', msg.targetLanguage)
        if (msg.audioBase64 && msg.targetLanguage) {
          try {
            const binaryStr = atob(msg.audioBase64)
            const bytes = new Uint8Array(binaryStr.length)
            for (let i = 0; i < binaryStr.length; i++) {
              bytes[i] = binaryStr.charCodeAt(i)
            }
            // 转换为 Int16Array（假设是小端序）
            const pcmData = new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 2)
            console.log('[InterpretationView] decoded pcm, length=', pcmData.length)
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

      // 初始化流式音频播放器（VoiceMeeter Input / Voice Aux Input）
      await initStreamAudio(audioConfigRef.current)

      const audio = new AudioCapture({
        sampleRate: AUDIO_DEFAULTS.SAMPLE_RATE,
        onData: pcm => {
          ws.sendAudio(sid, pcmToBase64(pcm))
          playSourcePcm(pcm)
          // 简单的 RMS 检测
          let sumSq = 0
          for (let i = 0; i < pcm.length; i++) sumSq += pcm[i] * pcm[i]
          const rms = Math.sqrt(sumSq / pcm.length)
          if (rms > 0.0012) lastLoudAudioAtRef.current = Date.now()
        },
      })
      audioRef.current = audio
      await audio.start()

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
    // 清理流式音频
    streamAudioElZhRef.current?.pause()
    streamAudioElIdRef.current?.pause()
    streamAudioElZhRef.current = null
    streamAudioElIdRef.current = null
    streamDestZhRef.current = null
    streamDestIdRef.current = null
    streamContextRef.current?.close()
    streamContextRef.current = null
    scheduleTimeZhRef.current = 0
    scheduleTimeIdRef.current = 0
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
          <button
            className="si-pill-btn"
            onClick={() => setShowDeviceSettings(!showDeviceSettings)}
          >
            <IconSettings />
            音频设备
          </button>
        </div>
      </header>

      {/* 设备设置面板 */}
      {showDeviceSettings && (
        <div className="si-device-settings">
          <div className="si-device-settings-panel">
            <div className="si-device-settings-row">
              <label>中文声道（原音 + TTS）：</label>
              <select
                value={audioConfig.zhDeviceId || ''}
                onChange={e => {
                  const newConfig = { ...audioConfig, zhDeviceId: e.target.value || null }
                  setAudioConfig(newConfig)
                  if (e.target.value) localStorage.setItem(STORAGE_KEY_ZH_DEVICE, e.target.value)
                  else localStorage.removeItem(STORAGE_KEY_ZH_DEVICE)
                }}
              >
                <option value="">默认设备</option>
                {audioDevices.map(d => (
                  <option key={d.deviceId} value={d.deviceId}>{d.label || d.deviceId}</option>
                ))}
              </select>
            </div>
            <div className="si-device-settings-row">
              <label>印尼语声道（原音 + TTS）：</label>
              <select
                value={audioConfig.idDeviceId || ''}
                onChange={e => {
                  const newConfig = { ...audioConfig, idDeviceId: e.target.value || null }
                  setAudioConfig(newConfig)
                  if (e.target.value) localStorage.setItem(STORAGE_KEY_ID_DEVICE, e.target.value)
                  else localStorage.removeItem(STORAGE_KEY_ID_DEVICE)
                }}
              >
                <option value="">默认设备</option>
                {audioDevices.map(d => (
                  <option key={d.deviceId} value={d.deviceId}>{d.label || d.deviceId}</option>
                ))}
              </select>
            </div>
            <p className="si-device-settings-hint">
              中文原音 + 中文 TTS → 中文声道；印尼语原音 + 印尼语 TTS → 印尼语声道。<br />
              语言未识别时默认走中文声道。建议分别选择 VoiceMeeter Input 和 VoiceMeeter Aux Input。
            </p>
          </div>
        </div>
      )}

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
                      <span className="si-tri-line-lang-badge si-tri-line-lang-badge--id">
                        Indonesia
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
                      <span className="si-tri-line-lang-badge si-tri-line-lang-badge--partial si-tri-line-lang-badge--id">
                        Indonesia
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
