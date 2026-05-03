/**
 * 音频输出管理器
 * 支持动态切换 setSinkId，将音频路由到 VoiceMeeter Input 或 Voice Aux Input
 */
export type LanguageCategory = 'zh' | 'id'  // 中文 or 印尼语

interface OutputDevice {
  label: string
  deviceId: string
}

export class AudioOutputManager {
  private audioContext: AudioContext
  private audioElement: HTMLAudioElement | null = null
  private streamDest: MediaStreamAudioDestinationNode | null = null

  private deviceMap: Map<LanguageCategory, string> = new Map()
  private currentDevice: LanguageCategory = 'zh'  // 默认中文

  constructor() {
    this.audioContext = new AudioContext()
    this.streamDest = this.audioContext.createMediaStreamDestination()
  }

  /**
   * 初始化：枚举并选择 VoiceMeeter 设备
   */
  async init(): Promise<void> {
    // 请求权限以枚举设备
    await navigator.mediaDevices.getUserMedia({ audio: true })

    const devices = await navigator.mediaDevices.enumerateDevices()
    const outputs = devices.filter(d => d.kind === 'audiooutput')

    console.log('[AudioOutputManager] 可用音频输出设备:', outputs.map(d => d.label || d.deviceId))

    // 查找 VoiceMeeter Input 和 Voice Aux Input
    const vmInput = outputs.find(d => d.label.includes('VoiceMeeter Input'))
    const auxInput = outputs.find(d => d.label.includes('Voice Aux Input'))

    if (vmInput) {
      this.deviceMap.set('zh', vmInput.deviceId)
      console.log('[AudioOutputManager] 中文设备:', vmInput.label)
    } else {
      console.warn('[AudioOutputManager] 未找到 VoiceMeeter Input 设备')
    }

    if (auxInput) {
      this.deviceMap.set('id', auxInput.deviceId)
      console.log('[AudioOutputManager] 印尼语设备:', auxInput.label)
    } else {
      console.warn('[AudioOutputManager] 未找到 Voice Aux Input 设备')
    }

    // 创建音频元素
    this.audioElement = new Audio()
    this.audioElement.srcObject = this.streamDest!.stream
    this.audioElement.volume = 1.0
    await this.audioElement.play()

    // 设置默认设备
    const defaultDeviceId = this.deviceMap.get(this.currentDevice)
    if (defaultDeviceId && 'setSinkId' in this.audioElement) {
      try {
        await (this.audioElement as any).setSinkId(defaultDeviceId)
        console.log('[AudioOutputManager] 默认输出设备已设置:', this.currentDevice)
      } catch (err) {
        console.error('[AudioOutputManager] setSinkId 失败:', err)
      }
    }
  }

  /**
   * 根据语言类别切换输出设备
   */
  async switchDevice(lang: LanguageCategory): Promise<void> {
    if (this.currentDevice === lang) return

    const deviceId = this.deviceMap.get(lang)
    if (!deviceId) {
      console.warn(`[AudioOutputManager] 未找到 ${lang} 对应的设备`)
      return
    }

    if (this.audioElement && 'setSinkId' in this.audioElement) {
      try {
        await (this.audioElement as any).setSinkId(deviceId)
        this.currentDevice = lang
        console.log(`[AudioOutputManager] 已切换到 ${lang === 'zh' ? '中文' : '印尼语'} 设备`)
      } catch (err) {
        console.error('[AudioOutputManager] 切换设备失败:', err)
      }
    }
  }

  /**
   * 播放 PCM 数据（Float32Array）
   */
  playPcm(floatData: Float32Array): void {
    if (!this.streamDest || !this.audioContext) return

    const buffer = this.audioContext.createBuffer(
      1,
      floatData.length,
      16000
    )
    buffer.copyToChannel(floatData, 0)

    const source = this.audioContext.createBufferSource()
    source.buffer = buffer
    source.connect(this.streamDest)
    source.start()
  }

  /**
   * 播放 ArrayBuffer（直接播放）
   */
  playBuffer(arrayBuffer: ArrayBuffer): void {
    // 解码并播放
    this.audioContext.decodeAudioData(arrayBuffer, (buffer) => {
      if (!this.streamDest) return
      const source = this.audioContext.createBufferSource()
      source.buffer = buffer
      source.connect(this.streamDest)
      source.start()
    })
  }

  /**
   * 销毁资源
   */
  destroy(): void {
    this.audioElement?.pause()
    this.audioElement = null
    this.streamDest = null
    this.audioContext.close()
  }
}
