import { useEffect, useRef, useState, useCallback } from 'react'
import { useParams } from 'react-router-dom'
import { getActiveSessionForUser, getPublicInterpretationResults } from '../api'
import { WS_DEFAULTS } from '../api/constants'
import { useSmartAutoScroll } from '../lib/useSmartAutoScroll'
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

const normalizeSpeakerId = (speakerId?: string | null) => {
  const trimmed = speakerId?.trim()
  if (!trimmed) return ''
  const lower = trimmed.toLowerCase()
  if (lower === 'undefined' || lower === 'null') return ''
  return trimmed
}

const isUnknownSpeakerId = (speakerId?: string | null) =>
  speakerId?.trim().toLowerCase() === 'unknown'

const mappedSpeakerName = (speakerId: string | undefined, nameMap: Record<string, string>) =>
  speakerId && !isUnknownSpeakerId(speakerId) ? nameMap[speakerId] : ''

export default function UserShareView() {
  const { userId = '' } = useParams()
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [items, setItems] = useState<DisplayShareItem[]>([])
  const [currentRecognizing, setCurrentRecognizing] = useState('')
  const [currentLanguage, setCurrentLanguage] = useState('')
  const [currentSpeakerId, setCurrentSpeakerId] = useState('')
  const [currentSpeakerName, setCurrentSpeakerName] = useState('')
  const [speakerNameMap, setSpeakerNameMap] = useState<Record<string, string>>({})
  const [isWaiting, setIsWaiting] = useState(true)
  const {
    scrollRef: bodyRef,
    isPaused: isTranscriptAutoScrollPaused,
    scrollToBottom: scrollTranscriptToBottom,
  } = useSmartAutoScroll<HTMLDivElement>([items, currentRecognizing])
  const wsRef = useRef<WebSocket | null>(null)
  const liveIdRef = useRef(-1)
  const speakerNameMapRef = useRef<Record<string, string>>({})
  const activeSessionIdRef = useRef<string | null>(null)
  const wsStoppedRef = useRef(false)

  useEffect(() => {
    speakerNameMapRef.current = speakerNameMap
  }, [speakerNameMap])

  useEffect(() => {
    activeSessionIdRef.current = activeSessionId
  }, [activeSessionId])

  const clearSessionState = useCallback(() => {
    setItems([])
    setCurrentRecognizing('')
    setCurrentLanguage('')
    setCurrentSpeakerId('')
    setCurrentSpeakerName('')
    setSpeakerNameMap({})
    speakerNameMapRef.current = {}
    liveIdRef.current = -1
  }, [])

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

  const handleWsMessage = (msg: WsMessage) => {
    const sid = normalizeSpeakerId(msg.speakerId)
    const sname = resolveSpeakerName(sid, msg.speakerName)
    switch (msg.type) {
      case 'recognizing':
        setCurrentRecognizing(msg.text || '')
        setCurrentLanguage(msg.language || '')
        if (sid) setCurrentSpeakerId(sid)
        setCurrentSpeakerName(sname || '')
        if (sname) rememberSpeakerName(sid, sname)
        break
      case 'recognized': {
        if (!msg.text) return
        setCurrentRecognizing('')
        setCurrentLanguage(msg.language || '')
        if (sid) setCurrentSpeakerId(sid)
        setCurrentSpeakerName(sname || '')
        if (sname) rememberSpeakerName(sid, sname)
        const id = liveIdRef.current--
        setItems(prev => [...prev, {
          id,
          sourceText: msg.text || '',
          sourceLang: msg.language,
          displaySourceText: msg.text || '',
          translations: [],
          isStreaming: true,
          speakerId: sid || undefined,
          speakerName: sname || undefined,
        }])
        break
      }
      case 'translated': {
        const sourceText = msg.text || ''
        const translatedText = msg.translatedText || ''
        if (!sourceText && !translatedText) return
        if (sid) setCurrentSpeakerId(sid)
        setCurrentSpeakerName(sname || '')
        if (sname) rememberSpeakerName(sid, sname)
        setItems(prev => {
          const translation: DisplayShareTranslation = {
            id: liveIdRef.current--,
            translatedText,
            targetLang: msg.targetLanguage,
            displayTranslatedText: translatedText,
          }
          const mergeSpk = (item: DisplayShareItem): DisplayShareItem => ({
            ...item,
            speakerId: item.speakerId || sid || undefined,
            speakerName: sname || item.speakerName,
          })
          const matchedIdx = sourceText
            ? [...prev].reverse().findIndex(item => item.sourceText === sourceText)
            : -1
          if (matchedIdx >= 0) {
            const index = prev.length - 1 - matchedIdx
            return prev.map((item, i) => i === index ? upsertTranslation(mergeSpk(item), translation) : item)
          }
          return [...prev, {
            id: liveIdRef.current--,
            sourceText,
            displaySourceText: sourceText,
            translations: [translation],
            isStreaming: false,
            speakerId: sid || undefined,
            speakerName: sname || undefined,
          }]
        })
        break
      }
      case 'speaker_identity': {
        const identitySid = normalizeSpeakerId(msg.speakerId)
        const displayName = msg.speakerName?.trim() || ''
        if (!identitySid) break
        setCurrentSpeakerId(identitySid)
        setCurrentSpeakerName(displayName || '')
        if (displayName) {
          if (isUnknownSpeakerId(identitySid)) {
            setItems(prev => {
              const idx = [...prev].reverse().findIndex(item =>
                isUnknownSpeakerId(item.speakerId) && !item.speakerName,
              )
              if (idx < 0) return prev
              const index = prev.length - 1 - idx
              return prev.map((item, i) => i === index ? { ...item, speakerName: displayName } : item)
            })
          } else {
            rememberSpeakerName(identitySid, displayName)
            setItems(prev => prev.map(item =>
              item.speakerId === identitySid ? { ...item, speakerName: displayName } : item,
            ))
          }
        }
        break
      }
      case 'started':
        clearSessionState()
        break
      case 'stopped':
        setCurrentRecognizing('')
        setCurrentSpeakerId('')
        setCurrentSpeakerName('')
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
          if (resultTimer) {
            window.clearInterval(resultTimer)
            resultTimer = null
          }
          setActiveSessionId(newSessionId)
          setIsWaiting(!newSessionId)

          if (newSessionId) {
            connectWs(newSessionId)
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
      } catch { /* ignore poll error */ }
    }

    void poll()
    const pollTimer = window.setInterval(poll, 5000)

    return () => {
      pollStopped = true
      window.clearInterval(pollTimer)
      if (resultTimer) window.clearInterval(resultTimer)
      disconnectWs()
    }
  }, [userId, connectWs, disconnectWs, clearSessionState])

  return (
    <div className="si-root">
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
            </div>

            <div className="si-tri-transcript-dock">
              <div className="si-tri-transcript-dock-inner" ref={bodyRef}>
                {isWaiting && (
                  <div className="si-tri-empty">等待同传开始...</div>
                )}

                {!isWaiting && items.length === 0 && !currentRecognizing && (
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
