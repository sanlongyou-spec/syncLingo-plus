import { TTS_OUTPUT_SAMPLE_RATE, TTS_OUTPUT_CABLE } from '../api/constants'

type OutputLang = 'zh' | 'id' | 'en'

/**
 * 排程提前量(秒)：每块在"上一块结束"或"当前时间+提前量"中较晚者开播。
 * 后端为低延迟把 TTS 切成很碎的块，提前量太小时网络抖动/GC 会让某块来不及、
 * 在拼接缝出咔哒声；80ms 缓冲垫可吸收抖动、消除偶发怪音(代价是出声晚 ~60ms)。
 */
const SCHEDULE_LEAD_SECONDS = 0.08

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
      // 上下文直接建在 TTS 采样率(24kHz)：buffer 原生播放、不做逐块重采样，
      // 避免每块独立重采样在拼接缝引入杂音；到设备(48kHz)的重采样由系统连续完成。
      this.context = new AudioContext({ sampleRate: TTS_OUTPUT_SAMPLE_RATE })
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
   * 枚举音频输出设备，按 VB-CABLE 设备标签把每种语言链路 setSinkId 到对应 CABLE Input。
   * 设备不存在或绑定失败 → 该语言链路保持未就绪（绝不回退默认扬声器）。
   */
  async applySinks(): Promise<void> {
    for (const lang of ['zh', 'id', 'en'] as OutputLang[]) {
      this.muteAndPause(this.channels[lang])
    }

    const outputs = (await navigator.mediaDevices.enumerateDevices())
      .filter(device => device.kind === 'audiooutput')
    const cableOutputs = outputs.filter(device => device.label.toLowerCase().includes('cable'))

    // 中文=原始 CABLE Input(排除 CABLE-A/CABLE-B)；印尼=CABLE-A；英语=CABLE-B。
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

    // 诊断：打印每种语言匹配到的具体设备，便于核对路由是否串台。
    console.log('[VoiceMeeterOutput] devices found:', cableOutputs.map(d => d.label))
    console.log('[VoiceMeeterOutput] matched: zh="%s" id="%s" en="%s"',
      zhDevice?.label ?? '(none)', idDevice?.label ?? '(none)', enDevice?.label ?? '(none)')
    if (zhDevice && idDevice && zhDevice.deviceId === idDevice.deviceId) {
      console.error('[VoiceMeeterOutput] zh 与 id 解析到同一设备，会串台：', zhDevice.label)
    }

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
    const startAt = Math.max(ctx.currentTime + SCHEDULE_LEAD_SECONDS, channel.scheduleTime)
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
