/**
 * 同声传译主页面
 * 音频采集 → ASR 自动语种检测 → 翻译 → TTS，全自动双向同传
 */
import { useState, useCallback, useEffect, useRef } from 'react'
import { startInterpretation, stopInterpretation, getUserVoice } from '../api'
import { AsrWebSocket } from '../lib/websocket'
import { AudioCapture, pcmToBase64 } from '../lib/audioCapture'
import { AUDIO_DEFAULTS } from '../api/constants'
import { STORAGE_KEYS, ROUTES, LANGUAGE } from '../constants'
import type { WsMessage } from '../types'
import Header from '../components/Header'
import ErrorBanner from '../components/ErrorBanner'
import './InterpretationView.css'

interface TranscriptItem {
  id: string
  source: string
  translated: string
  language: string
  timestamp: number
}

export default function InterpretationView() {
  // 默认用户 ID（移除登录后使用固定用户）
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')

  const [sessionId, setSessionId] = useState<string | null>(null)
  const [isRunning, setIsRunning] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [transcripts, setTranscripts] = useState<TranscriptItem[]>([])
  const [currentSource, setCurrentSource] = useState('')
  const [currentTranslated, setCurrentTranslated] = useState('')
  const [voiceId, setVoiceId] = useState<string | null>(null)
  const [error, setError] = useState('')

  const wsRef = useRef<AsrWebSocket | null>(null)
  const audioRef = useRef<AudioCapture | null>(null)

  useEffect(() => {
    getUserVoice(userId)
      .then(res => {
        if (res.data?.voiceId) {
          setVoiceId(res.data.voiceId)
        }
      })
      .catch((err: unknown) => {
        // 音色不存在不影响页面加载，静默忽略
        console.warn('[InterpretationView] getUserVoice failed:', err)
      })

    return () => {
      wsRef.current?.close()
      audioRef.current?.stop()
    }
  }, [userId])

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
        break
      case 'started':
        setIsRunning(true)
        setError('')
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
    audioRef.current?.stop()
    wsRef.current?.stop(sessionId)
    // 停止接口失败不影响本地停止流程，静默忽略
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
  }


  return (
    <div className="interpretation-view">
      <Header
        title="同声传译"
        actions={
          <button
            className="btn-link"
            onClick={() => { window.location.hash = ROUTES.VOICE_CLONE }}
          >
            音色克隆
          </button>
        }
      />

      <main className="main-content">
        <div className="control-panel">
          <div className="lang-hint">自动检测语种（中文 ↔ 印尼语）</div>

          <button
            className={`btn-main ${isRunning ? 'btn-stop' : 'btn-start'}`}
            onClick={isRunning ? stopSession : startSession}
            disabled={isLoading}
          >
            {isLoading ? '启动中...' : isRunning ? '停止同传' : '开始同传'}
          </button>

          <ErrorBanner message={error} onDismiss={() => setError('')} />

          {voiceId && (
            <div className="voice-badge">音色已加载</div>
          )}
        </div>

        <div className="subtitle-display">
          <div className="subtitle-source">
            <div className="subtitle-label">源语言</div>
            <div className="subtitle-text source-text">
              {currentSource || (transcripts.length > 0 ? transcripts[transcripts.length - 1].source : '')}
            </div>
          </div>
          <div className="subtitle-divider" />
          <div className="subtitle-target">
            <div className="subtitle-label">目标语言</div>
            <div className="subtitle-text target-text">
              {currentTranslated || (transcripts.length > 0 ? transcripts[transcripts.length - 1].translated : '')}
            </div>
          </div>
        </div>

        <div className="transcript-history">
          <div className="history-label">历史记录</div>
          <div className="history-list">
            {transcripts.map(item => (
              <div key={item.id} className="history-item">
                <div className="history-source">{item.source}</div>
                <div className="history-translated">{item.translated}</div>
                <div className="history-meta">{new Date(item.timestamp).toLocaleTimeString()}</div>
              </div>
            ))}
          </div>
        </div>
      </main>
    </div>
  )
}
