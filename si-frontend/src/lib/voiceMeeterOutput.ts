import { TTS_OUTPUT_SAMPLE_RATE, VOICEMEETER } from '../api/constants'

type OutputLang = 'zh' | 'id' | 'en'

interface OutputChannel {
  dest: MediaStreamAudioDestinationNode | null
  audioEl: HTMLAudioElement | null
  sinkReady: boolean
  scheduleTime: number
  pending: Set<AudioBufferSourceNode>
}

/**
 * 译文 TTS 出口：每种目标语言一条独立的 Web Audio 链，经 HTMLAudioElement.setSinkId
 * 路由到对应的 VoiceMeeter 虚拟输入设备（中文→Input/B1、印尼→Aux/B2、英语→VAIO3/B3），
 * 再由 VoiceMeeter 灌进会议麦克风。原始会议音频绝不经过这里，不进 VoiceMeeter。
 *
 * <p>设计对齐 {@link AudioCapture}：纯封装、由 InterpretationView 持有实例并显式 init/applySinks/stop。</p>
 */
export class VoiceMeeterOutput {
  private context: AudioContext | null = null
  private readonly channels: Record<OutputLang, OutputChannel> = {
    zh: VoiceMeeterOutput.emptyChannel(),
    id: VoiceMeeterOutput.emptyChannel(),
    en: VoiceMeeterOutput.emptyChannel(),
  }

  private static emptyChannel(): OutputChannel {
    return { dest: null, audioEl: null, sinkReady: false, scheduleTime: 0, pending: new Set() }
  }

  /** 创建播放上下文与三条语言链路（静音、暂停，待 applySinks 绑定设备后才出声）。 */
  async init(): Promise<void> {
    if (!this.context) {
      this.context = new AudioContext()
    }
    const ctx = this.context
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      const channel = this.channels[lang]
      if (channel.dest) continue
      channel.dest = ctx.createMediaStreamDestination()
      const el = new Audio()
      el.srcObject = channel.dest.stream
      el.autoplay = false
      el.volume = 0
      el.pause()
      channel.audioEl = el
    }
  }

  /**
   * 枚举音频输出设备，按 VoiceMeeter 设备标签把每种语言链路 setSinkId 到对应虚拟设备。
   * 设备不存在或绑定失败 → 该语言链路保持未就绪（绝不回退默认扬声器）。
   */
  async applySinks(): Promise<void> {
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      this.muteAndPause(this.channels[lang])
    }

    const outputs = (await navigator.mediaDevices.enumerateDevices())
      .filter(device => device.kind === 'audiooutput')
    const voiceMeeterOutputs = outputs.filter(device => {
      const label = device.label.toLowerCase()
      return label.includes('voicemeeter') || label.includes('voice meeter')
    })

    const zhDevice = voiceMeeterOutputs.find(device =>
      device.label.toLowerCase().includes(VOICEMEETER.ZH_DEVICE_LABEL.toLowerCase())
      && !device.label.toLowerCase().includes('aux')
      && !device.label.toLowerCase().includes('vaio3'),
    ) || voiceMeeterOutputs.find(device =>
      !device.label.toLowerCase().includes('aux') && !device.label.toLowerCase().includes('vaio3'),
    )
    const idDevice = voiceMeeterOutputs.find(device =>
      device.label.toLowerCase().includes(VOICEMEETER.ID_DEVICE_LABEL.toLowerCase()),
    ) || voiceMeeterOutputs.find(device => device.label.toLowerCase().includes('aux'))
    const enDevice = voiceMeeterOutputs.find(device =>
      device.label.toLowerCase().includes(VOICEMEETER.EN_DEVICE_LABEL.toLowerCase()),
    ) || voiceMeeterOutputs.find(device => device.label.toLowerCase().includes('vaio3'))

    this.channels.zh.sinkReady = zhDevice ? await this.setSink(this.channels.zh, zhDevice.deviceId) : false
    this.channels.id.sinkReady = idDevice ? await this.setSink(this.channels.id, idDevice.deviceId) : false
    this.channels.en.sinkReady = enDevice ? await this.setSink(this.channels.en, enDevice.deviceId) : false
    console.log('[VoiceMeeterOutput] sinks applied, zh=%s id=%s en=%s',
      this.channels.zh.sinkReady, this.channels.id.sinkReady, this.channels.en.sinkReady)
  }

  /** 中文与印尼语为必选语种，二者均就绪才允许启动同传。 */
  isReady(): boolean {
    return this.channels.zh.sinkReady && this.channels.id.sinkReady
  }

  /** 把一段译文 PCM(24kHz/Int16) 排入对应语言链路，按顺序无缝播放。 */
  play(pcmData: Int16Array, targetLang: string): void {
    const lang = VoiceMeeterOutput.resolveLang(targetLang)
    const channel = this.channels[lang]
    if (!this.context || !channel.dest || !channel.sinkReady) return
    const ctx = this.context
    const floatData = new Float32Array(pcmData.length)
    for (let i = 0; i < pcmData.length; i += 1) {
      floatData[i] = pcmData[i] / 32768
    }
    const buffer = ctx.createBuffer(1, floatData.length, TTS_OUTPUT_SAMPLE_RATE)
    buffer.copyToChannel(floatData, 0)
    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(channel.dest)
    const startAt = Math.max(ctx.currentTime + 0.02, channel.scheduleTime)
    channel.pending.add(source)
    source.onended = () => channel.pending.delete(source)
    source.start(startAt)
    channel.scheduleTime = startAt + buffer.duration
  }

  /** 释放所有链路与播放上下文。 */
  stop(): void {
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      const channel = this.channels[lang]
      channel.pending.forEach(source => {
        try { source.stop() } catch { /* already stopped */ }
      })
      channel.pending.clear()
      this.muteAndPause(channel)
      channel.dest = null
      channel.audioEl = null
      channel.sinkReady = false
      channel.scheduleTime = 0
    }
    void this.context?.close()
    this.context = null
  }

  private async setSink(channel: OutputChannel, deviceId: string): Promise<boolean> {
    const el = channel.audioEl
    if (!el || !('setSinkId' in el)) return false
    try {
      await (el as HTMLAudioElement & { setSinkId: (id: string) => Promise<void> }).setSinkId(deviceId)
      el.volume = 1
      await el.play()
      return true
    } catch (err) {
      console.warn('[VoiceMeeterOutput] setSinkId/play failed:', err)
      this.muteAndPause(channel)
      return false
    }
  }

  private muteAndPause(channel: OutputChannel): void {
    const el = channel.audioEl
    if (!el) return
    el.volume = 0
    el.autoplay = false
    el.pause()
  }

  private static resolveLang(targetLang: string): OutputLang {
    if (targetLang.startsWith('en')) return 'en'
    if (targetLang.startsWith('id')) return 'id'
    return 'zh'
  }
}
