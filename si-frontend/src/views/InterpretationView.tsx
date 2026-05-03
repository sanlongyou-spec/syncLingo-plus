/**
 * 聚龙同传主页面
 * 音频采集 → ASR 自动语种检测 → 翻译 → TTS，全自动双向同传
 */
import { useState, useCallback, useEffect, useRef } from 'react'
import { startInterpretation, stopInterpretation, getUserVoice } from '../api'
import { AsrWebSocket } from '../lib/websocket'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { AUDIO_DEFAULTS } from '../api/constants'
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
  const capturePipelineStartedAtRef = useRef(0)
  const lastLoudAudioAtRef = useRef(0)
  const bodyRef = useRef<HTMLDivElement>(null)

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
  }, [])

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

      const audio = new AudioCapture({
        sampleRate: AUDIO_DEFAULTS.SAMPLE_RATE,
        onData: pcm => {
          ws.sendAudio(sid, pcmToBase64(pcm))
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
