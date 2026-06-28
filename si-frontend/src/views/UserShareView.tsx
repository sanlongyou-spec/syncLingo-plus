import { useEffect, useRef, useState, useCallback } from 'react'
import type { CSSProperties } from 'react'
import { useParams } from 'react-router-dom'
import { getPublicInterpretationResults, mintShareWsTicket, resolveShareToken } from '../api'
import { WS_DEFAULTS } from '../api/constants'
import FontSizeControl from '../components/FontSizeControl'
import { useSmartAutoScroll } from '../lib/useSmartAutoScroll'
import { useTranscriptFontScale } from '../lib/useTranscriptFontScale'
import type { InterpretationResultItem, WsMessage } from '../types'
import './InterpretationView.css'


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
  const { token = '' } = useParams()
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [items, setItems] = useState<DisplayShareItem[]>([])
  const [currentRecognizing, setCurrentRecognizing] = useState('')
  const [currentLanguage, setCurrentLanguage] = useState('')
  const [isWaiting, setIsWaiting] = useState(true)
  const [tokenInvalid, setTokenInvalid] = useState(false)
  const {
    scrollRef: bodyRef,
    isPaused: isTranscriptAutoScrollPaused,
    scrollToBottom: scrollTranscriptToBottom,
  } = useSmartAutoScroll<HTMLDivElement>([items, currentRecognizing])
  const { scale: transcriptFontScale, setScale: setTranscriptFontScale } = useTranscriptFontScale()
  const wsRef = useRef<WebSocket | null>(null)
  const liveIdRef = useRef(-1)
  const activeSessionIdRef = useRef<string | null>(null)
  const wsStoppedRef = useRef(false)



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

  const connectWs = useCallback(async (sessionId: string) => {
    wsStoppedRef.current = false
    const ticketRes = await mintShareWsTicket(token)
    const ticket = ticketRes.data?.ticket
    if (!ticket || wsStoppedRef.current || activeSessionIdRef.current !== sessionId) return
    const wsUrl = `${WS_DEFAULTS.BASE_URL.replace(/^http/, 'ws')}/ws/share?ticket=${encodeURIComponent(ticket)}`
    const ws = new WebSocket(wsUrl)
    wsRef.current = ws
    ws.onmessage = event => {
      try {
        handleWsMessage(JSON.parse(event.data) as WsMessage)
      } catch (err) {
        console.warn('[UserShareView] parse ws message failed:', err)
      }
    }
    ws.onclose = () => {
      if (!wsStoppedRef.current && activeSessionIdRef.current === sessionId) {
        window.setTimeout(() => { void connectWs(sessionId) }, 2000)
      }
    }
  }, [token]) // eslint-disable-line react-hooks/exhaustive-deps

  const disconnectWs = useCallback(() => {
    wsStoppedRef.current = true
    wsRef.current?.close()
    wsRef.current = null
  }, [])

  // Poll for active session
  useEffect(() => {
    if (!token) return
    let pollStopped = false
    let resultTimer: ReturnType<typeof setInterval> | null = null
    let pollTimer: ReturnType<typeof setInterval> | null = null

    const poll = async () => {
      if (pollStopped) return
      try {
        const res = await resolveShareToken(token)
        if (pollStopped) return
        const newSessionId = res.data || null
        const prevSessionId = activeSessionIdRef.current

        if (newSessionId !== prevSessionId) {
          disconnectWs()
          clearSessionState()
          if (resultTimer) {
            window.clearInterval(resultTimer)
            resultTimer = null
          }
          setActiveSessionId(newSessionId)
          activeSessionIdRef.current = newSessionId
          setIsWaiting(!newSessionId)

          if (newSessionId) {
            void connectWs(newSessionId)
            // Load persisted results for this session
            const load = async () => {
              try {
                const r = await getPublicInterpretationResults(newSessionId)
                if (!pollStopped && activeSessionIdRef.current === newSessionId) {
                  const list = r.data || []
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
      } catch (err) {
        if (pollStopped) return
        const status = (err as { response?: { status?: number } })?.response?.status
        if (status === 401) {
          // 令牌无效/已过期/已撤销：明确提示链接失效并停止轮询，不再显示分享空壳
          pollStopped = true
          if (pollTimer) window.clearInterval(pollTimer)
          if (resultTimer) window.clearInterval(resultTimer)
          disconnectWs()
          setTokenInvalid(true)
        }
        // 其它错误（网络抖动等）忽略，下个周期重试
      }
    }

    void poll()
    pollTimer = window.setInterval(poll, 5000)

    return () => {
      pollStopped = true
      if (pollTimer) window.clearInterval(pollTimer)
      if (resultTimer) window.clearInterval(resultTimer)
      disconnectWs()
    }
  }, [token, connectWs, disconnectWs, clearSessionState])

  if (tokenInvalid) {
    return (
      <div className="si-root">
        <header className="si-topbar">
          <div className="si-topbar-left">
            <h1 className="si-brand">聚龙同传</h1>
            <span className="si-brand-sub">实时文本分享</span>
          </div>
        </header>
        <main className="si-main">
          <div className="si-share-invalid">
            <div className="si-share-invalid-icon">🔗</div>
            <p className="si-share-invalid-title">分享链接已失效</p>
            <p className="si-share-invalid-text">该链接已过期或被停用（链接自生成起 6 小时内有效）。请向会议组织者索取新的分享链接。</p>
          </div>
        </main>
      </div>
    )
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">聚龙同传</h1>
          <span className="si-brand-sub">实时文本分享</span>
        </div>
      </header>

      <main className="si-main">
        <div
          className="si-trilingual"
          style={{ '--si-tri-font-scale': transcriptFontScale } as CSSProperties}
        >
          <div className="si-tri-host-layout">
            <div className="si-tri-toolbar">
              <span className={`si-live-indicator ${isWaiting ? '' : 'is-running'}`} />
              <div className="si-tri-toolbar-spacer" />
              <FontSizeControl scale={transcriptFontScale} onChange={setTranscriptFontScale} />
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
