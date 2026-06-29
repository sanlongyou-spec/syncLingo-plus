import { TTS_OUTPUT_CABLE, TTS_OUTPUT_SAMPLE_RATE } from '../api/constants'
import type { TtsPlaybackLog } from '../types'

type OutputLang = 'zh' | 'id' | 'en'
type PlaybackLogger = (event: TtsPlaybackLog) => void

interface PlaybackMeta {
  taskId?: string
  sequence?: number
  chunkIndex?: number
}

interface OutputChannel {
  dest: MediaStreamAudioDestinationNode | null
  audioEl: HTMLAudioElement | null
  keepAlive: ConstantSourceNode | null
  sinkReady: boolean
  scheduleTime: number
  pending: Set<AudioBufferSourceNode>
}

const SCHEDULE_LEAD_SECONDS = 0.08

/**
 * Routes translated TTS PCM to per-language VB-CABLE output devices.
 *
 * The browser is not used as a user-facing speaker. It is only the Web Audio
 * bridge that feeds CABLE Input / CABLE-A Input / CABLE-B Input.
 */
export class VbCableOutput {
  constructor(private readonly playbackLogger?: PlaybackLogger) {}

  private context: AudioContext | null = null
  private readonly channels: Record<OutputLang, OutputChannel> = {
    zh: VbCableOutput.emptyChannel(),
    id: VbCableOutput.emptyChannel(),
    en: VbCableOutput.emptyChannel(),
  }

  private static emptyChannel(): OutputChannel {
    return {
      dest: null,
      audioEl: null,
      keepAlive: null,
      sinkReady: false,
      scheduleTime: 0,
      pending: new Set(),
    }
  }

  async init(): Promise<void> {
    if (!this.context) {
      this.context = new AudioContext({ sampleRate: TTS_OUTPUT_SAMPLE_RATE })
      this.context.onstatechange = () => {
        console.info('[VbCableOutput] AudioContext state=%s sampleRate=%d',
          this.context?.state ?? 'none', this.context?.sampleRate ?? 0)
      }
    }
    const ctx = this.context
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      const channel = this.channels[lang]
      if (channel.dest) continue

      channel.dest = ctx.createMediaStreamDestination()
      channel.keepAlive = VbCableOutput.startSilentKeepAlive(ctx, channel.dest)

      const el = new Audio()
      el.srcObject = channel.dest.stream
      el.autoplay = true
      el.volume = 0
      el.onpause = () => {
        if (channel.sinkReady && channel.pending.size > 0) {
          console.warn('[VbCableOutput] audio element paused with pending audio, lang=%s pending=%d',
            lang, channel.pending.size)
          this.emit('audio_element_paused', lang, undefined, {}, {
            pendingCount: channel.pending.size,
            audioPaused: el.paused,
            sinkReady: channel.sinkReady,
          })
        }
      }
      el.onended = () => {
        if (channel.sinkReady && channel.pending.size > 0) {
          console.warn('[VbCableOutput] audio element ended with pending audio, lang=%s pending=%d',
            lang, channel.pending.size)
          this.emit('audio_element_ended', lang, undefined, {}, {
            pendingCount: channel.pending.size,
            audioPaused: el.paused,
            sinkReady: channel.sinkReady,
          })
        }
      }
      el.onerror = () => {
        console.warn('[VbCableOutput] audio element error, lang=%s code=%s message=%s',
          lang, el.error?.code ?? 'unknown', el.error?.message ?? '')
        this.emit('audio_element_error', lang, undefined, {}, {
          audioPaused: el.paused,
          sinkReady: channel.sinkReady,
          detail: `code=${el.error?.code ?? 'unknown'} message=${el.error?.message ?? ''}`,
        })
      }
      channel.audioEl = el
    }
  }

  async applySinks(): Promise<void> {
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      const channel = this.channels[lang]
      channel.sinkReady = false
      this.muteAndPause(channel)
    }

    const outputs = (await navigator.mediaDevices.enumerateDevices())
      .filter(device => device.kind === 'audiooutput')
    const cableOutputs = outputs.filter(device => device.label.toLowerCase().includes('cable'))

    const zhDevice = cableOutputs.find(device =>
      device.label.toLowerCase().includes(TTS_OUTPUT_CABLE.ZH_DEVICE_LABEL.toLowerCase())
      && !device.label.toLowerCase().includes('cable-a')
      && !device.label.toLowerCase().includes('cable-b'),
    )
    const idDevice = cableOutputs.find(device =>
      device.label.toLowerCase().includes(TTS_OUTPUT_CABLE.ID_DEVICE_LABEL.toLowerCase()),
    ) || cableOutputs.find(device => device.label.toLowerCase().includes('cable-a'))
    const enDevice = cableOutputs.find(device =>
      device.label.toLowerCase().includes(TTS_OUTPUT_CABLE.EN_DEVICE_LABEL.toLowerCase()),
    ) || cableOutputs.find(device => device.label.toLowerCase().includes('cable-b'))

    console.log('[VbCableOutput] devices found:', cableOutputs.map(d => d.label))
    console.log('[VbCableOutput] matched: zh="%s" id="%s" en="%s"',
      zhDevice?.label ?? '(none)', idDevice?.label ?? '(none)', enDevice?.label ?? '(none)')
    this.emit('devices_matched', 'zh', undefined, {}, {
      detail: `zh=${zhDevice?.label ?? '(none)'}; id=${idDevice?.label ?? '(none)'}; en=${enDevice?.label ?? '(none)'}`,
    })
    if (zhDevice && idDevice && zhDevice.deviceId === idDevice.deviceId) {
      console.error('[VbCableOutput] zh and id resolved to the same VB-CABLE device:', zhDevice.label)
      this.emit('device_mapping_conflict', 'zh', undefined, {}, {
        detail: `zh and id resolved to ${zhDevice.label}`,
      })
    }

    this.channels.zh.sinkReady = zhDevice ? await this.setSink('zh', this.channels.zh, zhDevice) : false
    this.channels.id.sinkReady = idDevice ? await this.setSink('id', this.channels.id, idDevice) : false
    this.channels.en.sinkReady = enDevice ? await this.setSink('en', this.channels.en, enDevice) : false
    console.log('[VbCableOutput] sinks applied, zh=%s id=%s en=%s',
      this.channels.zh.sinkReady, this.channels.id.sinkReady, this.channels.en.sinkReady)
    this.emit('sinks_applied', 'zh', undefined, {}, {
      detail: `zh=${this.channels.zh.sinkReady}; id=${this.channels.id.sinkReady}; en=${this.channels.en.sinkReady}`,
    })
  }

  isReady(): boolean {
    return this.channels.zh.sinkReady && this.channels.id.sinkReady
  }

  play(pcmData: Int16Array, targetLang: string, meta: PlaybackMeta = {}): void {
    const lang = VbCableOutput.resolveLang(targetLang)
    const channel = this.channels[lang]
    if (!this.context || !channel.dest || !channel.sinkReady) {
      this.logDroppedChunk('not_ready', lang, targetLang, meta)
      return
    }
    if (pcmData.length === 0) {
      this.logDroppedChunk('empty_pcm', lang, targetLang, meta)
      return
    }
    if (this.context.state === 'closed') {
      this.logDroppedChunk('context_closed', lang, targetLang, meta)
      return
    }

    const ctx = this.context
    this.ensureOutputActive(lang, targetLang, channel, meta)

    const floatData = new Float32Array(pcmData.length)
    for (let i = 0; i < pcmData.length; i += 1) {
      floatData[i] = pcmData[i] / 32768
    }

    const buffer = ctx.createBuffer(1, floatData.length, TTS_OUTPUT_SAMPLE_RATE)
    buffer.copyToChannel(floatData, 0)

    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(channel.dest)

    const startAt = Math.max(ctx.currentTime + SCHEDULE_LEAD_SECONDS, channel.scheduleTime)
    const durationMs = Math.round(buffer.duration * 1000)
    const scheduledAheadMs = Math.round(Math.max(0, startAt - ctx.currentTime) * 1000)
    channel.pending.add(source)

    source.onended = () => {
      channel.pending.delete(source)
      console.debug('[VbCableOutput] chunk ended, lang=%s taskId=%s sequence=%s chunk=%s pending=%d',
        lang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '', channel.pending.size)
    }

    try {
      source.start(startAt)
    } catch (err) {
      channel.pending.delete(source)
      console.warn('[VbCableOutput] source start failed, lang=%s taskId=%s sequence=%s chunk=%s',
        lang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '', err)
      this.emit('source_start_failed', lang, targetLang, meta, {
        durationMs,
        scheduledAheadMs,
        pendingCount: channel.pending.size,
        contextState: ctx.state,
        audioPaused: channel.audioEl?.paused ?? true,
        sinkReady: channel.sinkReady,
        detail: err instanceof Error ? err.message : String(err),
      })
      return
    }

    channel.scheduleTime = startAt + buffer.duration
    console.debug('[VbCableOutput] chunk scheduled, lang=%s targetLang=%s taskId=%s sequence=%s chunk=%s durationMs=%d scheduledAheadMs=%d pending=%d contextState=%s',
      lang, targetLang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '',
      durationMs, scheduledAheadMs, channel.pending.size, ctx.state)

    if (VbCableOutput.shouldCheckpoint(meta)) {
      console.info('[VbCableOutput] schedule checkpoint, lang=%s targetLang=%s taskId=%s sequence=%s chunk=%s durationMs=%d scheduledAheadMs=%d pending=%d contextState=%s audioPaused=%s',
        lang, targetLang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '',
        durationMs, scheduledAheadMs, channel.pending.size, ctx.state, channel.audioEl?.paused ?? true)
      this.emit('schedule_checkpoint', lang, targetLang, meta, {
        durationMs,
        scheduledAheadMs,
        pendingCount: channel.pending.size,
        contextState: ctx.state,
        audioPaused: channel.audioEl?.paused ?? true,
        sinkReady: channel.sinkReady,
      })
    }
  }

  stop(): void {
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      const channel = this.channels[lang]
      const scheduledAheadMs = this.context
        ? Math.round(Math.max(0, channel.scheduleTime - this.context.currentTime) * 1000)
        : 0
      if (channel.pending.size > 0 || scheduledAheadMs > 0) {
        console.warn('[VbCableOutput] stop clears queued audio, lang=%s pending=%d scheduledAheadMs=%d',
          lang, channel.pending.size, scheduledAheadMs)
        this.emit('stop_clears_queued_audio', lang, undefined, {}, {
          scheduledAheadMs,
          pendingCount: channel.pending.size,
          contextState: this.context?.state,
          audioPaused: channel.audioEl?.paused ?? true,
          sinkReady: channel.sinkReady,
        })
      }

      channel.sinkReady = false
      channel.pending.forEach(source => {
        try { source.stop() } catch { /* already stopped */ }
      })
      channel.pending.clear()
      try { channel.keepAlive?.stop() } catch { /* already stopped */ }
      channel.keepAlive?.disconnect()
      channel.keepAlive = null
      this.muteAndPause(channel)
      channel.dest = null
      channel.audioEl = null
      channel.scheduleTime = 0
    }
    void this.context?.close()
    this.context = null
  }

  private async setSink(lang: OutputLang, channel: OutputChannel, device: MediaDeviceInfo): Promise<boolean> {
    const el = channel.audioEl
    if (!el || !('setSinkId' in el)) {
      console.warn('[VbCableOutput] setSinkId unsupported, lang=%s', lang)
      this.emit('set_sink_unsupported', lang, undefined, {}, {
        sinkReady: false,
        detail: device.label,
      })
      return false
    }
    try {
      await (el as HTMLAudioElement & { setSinkId: (id: string) => Promise<void> }).setSinkId(device.deviceId)
      el.volume = 1
      el.autoplay = true
      await el.play()
      await this.resumeContext(lang)
      console.log('[VbCableOutput] sink ready, lang=%s contextState=%s audioPaused=%s',
        lang, this.context?.state ?? 'none', el.paused)
      this.emit('sink_ready', lang, undefined, {}, {
        contextState: this.context?.state ?? 'none',
        audioPaused: el.paused,
        sinkReady: true,
        detail: device.label,
      })
      return true
    } catch (err) {
      console.warn('[VbCableOutput] setSinkId/play failed, lang=%s:', lang, err)
      this.emit('set_sink_failed', lang, undefined, {}, {
        contextState: this.context?.state ?? 'none',
        audioPaused: el.paused,
        sinkReady: false,
        detail: `${device.label}: ${err instanceof Error ? err.message : String(err)}`,
      })
      this.muteAndPause(channel)
      return false
    }
  }

  private ensureOutputActive(
    lang: OutputLang,
    targetLang: string,
    channel: OutputChannel,
    meta: PlaybackMeta,
  ): void {
    void this.resumeContext(lang, meta)
    const el = channel.audioEl
    if (!el || (!el.paused && !el.ended)) return
    el.volume = 1
    el.autoplay = true
    void el.play()
      .then(() => {
        console.warn('[VbCableOutput] resumed audio element, lang=%s taskId=%s sequence=%s chunk=%s',
          lang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '')
        this.emit('audio_element_resumed', lang, targetLang, meta, {
          pendingCount: channel.pending.size,
          audioPaused: el.paused,
          sinkReady: channel.sinkReady,
        })
      })
      .catch(err => {
        console.warn('[VbCableOutput] resume audio element failed, lang=%s taskId=%s sequence=%s chunk=%s',
          lang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '', err)
        this.emit('audio_element_resume_failed', lang, targetLang, meta, {
          pendingCount: channel.pending.size,
          audioPaused: el.paused,
          sinkReady: channel.sinkReady,
          detail: err instanceof Error ? err.message : String(err),
        })
      })
  }

  private async resumeContext(lang: OutputLang, meta: PlaybackMeta = {}): Promise<void> {
    const ctx = this.context
    if (!ctx || ctx.state !== 'suspended') return
    try {
      await ctx.resume()
      console.warn('[VbCableOutput] resumed AudioContext, lang=%s taskId=%s sequence=%s chunk=%s',
        lang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '')
      this.emit('audio_context_resumed', lang, undefined, meta, {
        contextState: ctx.state,
        sampleRate: ctx.sampleRate,
      })
    } catch (err) {
      console.warn('[VbCableOutput] resume AudioContext failed, lang=%s taskId=%s sequence=%s chunk=%s',
        lang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '', err)
      this.emit('audio_context_resume_failed', lang, undefined, meta, {
        contextState: ctx.state,
        sampleRate: ctx.sampleRate,
        detail: err instanceof Error ? err.message : String(err),
      })
    }
  }

  private muteAndPause(channel: OutputChannel): void {
    const el = channel.audioEl
    if (!el) return
    el.volume = 0
    el.autoplay = false
    el.pause()
  }

  private static startSilentKeepAlive(
    ctx: AudioContext,
    dest: MediaStreamAudioDestinationNode,
  ): ConstantSourceNode {
    const keepAlive = ctx.createConstantSource()
    const gain = ctx.createGain()
    keepAlive.offset.value = 0
    gain.gain.value = 0
    keepAlive.connect(gain)
    gain.connect(dest)
    keepAlive.start()
    return keepAlive
  }

  private static shouldCheckpoint(meta: PlaybackMeta): boolean {
    return meta.chunkIndex === 0
      || (typeof meta.chunkIndex === 'number' && meta.chunkIndex > 0 && meta.chunkIndex % 25 === 0)
  }

  private logDroppedChunk(
    reason: string,
    lang: OutputLang,
    targetLang: string,
    meta: PlaybackMeta,
  ): void {
    console.warn('[VbCableOutput] drop chunk, reason=%s lang=%s targetLang=%s taskId=%s sequence=%s chunk=%s',
      reason, lang, targetLang, meta.taskId ?? '', meta.sequence ?? '', meta.chunkIndex ?? '')
    this.emit('drop_chunk', lang, targetLang, meta, {
      reason,
      pendingCount: this.channels[lang].pending.size,
      contextState: this.context?.state ?? 'none',
      audioPaused: this.channels[lang].audioEl?.paused ?? true,
      sinkReady: this.channels[lang].sinkReady,
    })
  }

  private emit(
    event: string,
    playbackLang: OutputLang,
    targetLanguage: string | undefined,
    meta: PlaybackMeta,
    extra: Partial<TtsPlaybackLog> = {},
  ): void {
    this.playbackLogger?.({
      event,
      targetLanguage,
      playbackLang,
      ttsTaskId: meta.taskId,
      ttsSequence: meta.sequence,
      chunkIndex: meta.chunkIndex,
      sampleRate: this.context?.sampleRate ?? TTS_OUTPUT_SAMPLE_RATE,
      ...extra,
    })
  }

  private static resolveLang(targetLang: string): OutputLang {
    const lower = targetLang.toLowerCase()
    if (lower.startsWith('en')) return 'en'
    if (lower.startsWith('id')) return 'id'
    return 'zh'
  }
}
