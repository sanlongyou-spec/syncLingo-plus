/**
 * WebSocket 客户端封装
 * 管理 ASR 实时语音 WebSocket 连接（重连、超时、消息序列化）
 */
import type { WsMessage } from '../types'
import { WS_DEFAULTS } from '../api/constants'
import { STORAGE_KEYS } from '../constants'

type MessageHandler = (msg: WsMessage) => void

export class AsrWebSocket {
  private socket: WebSocket | null = null
  private url: string
  private sessionId: string | null = null
  private messageHandler: MessageHandler | null = null
  private reconnectAttempts = 0
  private destroyed = false

  constructor(url?: string) {
    this.url = url ?? WS_DEFAULTS.BASE_URL.replace(/^http/, 'ws') + '/ws/asr'
  }

  async connect(sessionId: string): Promise<void> {
    this.sessionId = sessionId
    this.reconnectAttempts = 0
    this.destroyed = false

    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    const fullUrl = token
      ? `${this.url}?token=${encodeURIComponent(token)}`
      : this.url

    return new Promise((resolve, reject) => {
      this.socket = new WebSocket(fullUrl)

      this.socket.onopen = () => {
        this.reconnectAttempts = 0
        resolve()
      }

      this.socket.onerror = (event) => {
        console.error('[AsrWebSocket] connection error:', event)
        reject(new Error('WebSocket 连接失败'))
      }

      this.socket.onmessage = (event) => {
        try {
          const msg: WsMessage = JSON.parse(event.data)
          this.messageHandler?.(msg)
        } catch (err) {
          console.error('[AsrWebSocket] failed to parse message:', err)
        }
      }

      this.socket.onclose = (event) => {
        if (this.destroyed) return
        console.warn(`[AsrWebSocket] connection closed: code=${event.code}, reason=${event.reason}`)
        this.attemptReconnect()
      }
    })
  }

  onMessage(handler: MessageHandler): void {
    this.messageHandler = handler
  }

  start(params: { sessionId: string; sourceLang: string; targetLang: string; voiceId?: string }): void {
    this.send({ type: 'start', ...params })
  }

  sendAudio(sessionId: string, audioBase64: string): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.send({ type: 'audio', sessionId, audioBase64 })
    }
  }

  stop(sessionId: string): void {
    this.send({ type: 'stop', sessionId })
  }

  close(): void {
    this.destroyed = true
    this.socket?.close()
    this.socket = null
    this.sessionId = null
    this.messageHandler = null
  }

  private send(msg: Partial<WsMessage>): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(msg))
    }
  }

  private attemptReconnect(): void {
    if (
      this.destroyed ||
      !this.sessionId ||
      this.reconnectAttempts >= WS_DEFAULTS.MAX_RECONNECT
    ) {
      return
    }

    this.reconnectAttempts++
    console.warn(`[AsrWebSocket] reconnecting, attempt=${this.reconnectAttempts}`)

    setTimeout(() => {
      if (!this.destroyed && this.sessionId) {
        this.connect(this.sessionId).catch(() => {
          // 连接失败交给 onclose 继续重试
        })
      }
    }, WS_DEFAULTS.RECONNECT_DELAY_MS)
  }
}
