/**
 * WebSocket 客户端封装
 * 管理 ASR 实时语音 WebSocket 连接（重连、超时、消息序列化）
 */
import type { TtsPlaybackLog, WsMessage } from '../types'
import { WS_DEFAULTS } from '../api/constants'
import { mintWsTicket } from '../api'

type MessageHandler = (msg: WsMessage) => void

export class AsrWebSocket {
  private socket: WebSocket | null = null
  private url: string
  private sessionId: string | null = null
  private startParams: { sessionId: string; sourceLang: string; targetLang: string; voiceId?: string } | null = null
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

    const fullUrl = await this.buildAuthenticatedUrl()

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

  /**
   * P5:优先用一次性票据(每次连接/重连重新申请),避免把长效 JWT 放进 query;
   * 票据获取失败时直接终止连接,不再回退旧版 token。
   */
  private async buildAuthenticatedUrl(): Promise<string> {
    const res = await mintWsTicket()
    if (res.code === 200 && res.data?.ticket) {
      return `${this.url}?ticket=${encodeURIComponent(res.data.ticket)}`
    }
    throw new Error(res.message || 'WebSocket 票据获取失败')
  }

  onMessage(handler: MessageHandler): void {
    this.messageHandler = handler
  }

  start(params: { sessionId: string; sourceLang: string; targetLang: string; voiceId?: string }): void {
    this.startParams = params
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

  setVoice(sessionId: string, speakerId: string, voiceId?: string): void {
    this.send({ type: 'set_voice', sessionId, speakerId, voiceId: voiceId || '' })
  }

  sendTtsPlaybackLog(sessionId: string, log: TtsPlaybackLog): void {
    this.send({
      type: 'tts_playback_log',
      sessionId,
      targetLanguage: log.targetLanguage,
      playbackLang: log.playbackLang,
      ttsTaskId: log.ttsTaskId,
      ttsSequence: log.ttsSequence,
      chunkIndex: log.chunkIndex,
      event: log.event,
      reason: log.reason,
      durationMs: log.durationMs,
      scheduledAheadMs: log.scheduledAheadMs,
      pendingCount: log.pendingCount,
      contextState: log.contextState,
      audioPaused: log.audioPaused,
      sinkReady: log.sinkReady,
      sampleRate: log.sampleRate,
      detail: log.detail,
    })
  }

  close(): void {
    this.destroyed = true
    this.socket?.close()
    this.socket = null
    this.sessionId = null
    this.startParams = null
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
        this.connect(this.sessionId)
          .then(() => {
            // 重连成功后重新发送 start 消息，让后端重新初始化识别器
            if (this.startParams) {
              this.send({ type: 'start', ...this.startParams })
            }
          })
          .catch(() => {
            // 连接失败交给 onclose 继续重试
          })
      }
    }, WS_DEFAULTS.RECONNECT_DELAY_MS)
  }
}
