import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { getPublicInterpretationResults } from '../api'
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

  const upsertPersistedItems = (list: InterpretationResultItem[]) => {
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
              <span className="si-live-indicator is-running" />
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
