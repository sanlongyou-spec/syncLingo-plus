/**
 * 音频采集模块
 * 使用 getDisplayMedia 捕获系统音频（标签页/窗口音频）
 * 采集器本身静音（setSinkId none），所有路由由调用方通过 onData 回调处理。
 */
import { AUDIO_DEFAULTS, PCM } from '../api/constants'

interface AudioCaptureOptions {
  sampleRate?: number
  channels?: number
  onData: (pcmData: Int16Array) => void
}

export function pcmToBase64(pcmData: Int16Array): string {
  const binary = String.fromCharCode(...new Uint8Array(pcmData.buffer))
  return btoa(binary)
}

export class AudioCapture {
  private audioContext: AudioContext | null = null
  private sourceNode: MediaStreamAudioSourceNode | null = null
  private processorNode: ScriptProcessorNode | null = null
  private stream: MediaStream | null = null
  private readonly sampleRate: number
  private readonly channels: number
  private readonly onData: (pcmData: Int16Array) => void
  private frameCount = 0

  constructor(options: AudioCaptureOptions) {
    this.sampleRate = options.sampleRate ?? AUDIO_DEFAULTS.SAMPLE_RATE
    this.channels = options.channels ?? AUDIO_DEFAULTS.CHANNELS
    this.onData = options.onData
  }

  async start(): Promise<void> {
    console.log('[AudioCapture] 开始采集系统音频')

    try {
      // Chrome/Edge 要求至少请求一种媒体类型，所以用 video: true 然后忽略视频轨道
      this.stream = await navigator.mediaDevices.getDisplayMedia({
        video: true,
        audio: true,
      })

      const audioTracks = this.stream.getAudioTracks()
      console.log('[AudioCapture] 获取到音频轨道:', audioTracks.length, 'tracks')

      if (audioTracks.length === 0) {
        throw new Error('没有获取到音频轨道，请确保选择了带音频的标签页或窗口')
      }

      const track = audioTracks[0]
      console.log('[AudioCapture] 音频轨道信息:', {
        label: track.label,
        id: track.id,
        enabled: track.enabled,
        readyState: track.readyState,
      })

      this.audioContext = new AudioContext({ sampleRate: this.sampleRate })
      console.log('[AudioCapture] AudioContext 创建完成, 采样率:', this.audioContext.sampleRate)

      // 静音本采集器的默认输出，由调用方通过 onData 负责路由
      if ('setSinkId' in this.audioContext) {
        try {
          await (this.audioContext as any).setSinkId({ type: 'none' })
        } catch (err) {
          console.warn('[AudioCapture] setSinkId(none) 失败，音频可能播放到默认设备:', err)
        }
      }

      this.sourceNode = this.audioContext.createMediaStreamSource(this.stream)

      this.processorNode = this.audioContext.createScriptProcessor(
        AUDIO_DEFAULTS.BUFFER_SIZE,
        this.channels,
        this.channels
      )

      this.processorNode.onaudioprocess = (event) => {
        this.frameCount++
        const inputData = event.inputBuffer.getChannelData(0)

        // 计算 RMS 检查是否有音频数据
        let sum = 0
        for (let i = 0; i < inputData.length; i++) {
          sum += inputData[i] * inputData[i]
        }
        const rms = Math.sqrt(sum / inputData.length)

        if (this.frameCount % 100 === 0) {
          console.log(`[AudioCapture] 帧 ${this.frameCount}, RMS=${rms.toFixed(4)}`)
        }

        if (rms < 0.001) {
          return
        }

        const pcmData = this.floatToPcm16(inputData)
        this.onData(pcmData)
      }

      this.sourceNode.connect(this.processorNode)
      this.processorNode.connect(this.audioContext.destination)

      console.log('[AudioCapture] 音频管线连接完成')

    } catch (err) {
      console.error('[AudioCapture] 启动失败:', err)
      throw err
    }
  }

  stop(): void {
    console.log('[AudioCapture] 停止采集, 共处理', this.frameCount, '帧')
    this.processorNode?.disconnect()
    this.sourceNode?.disconnect()
    this.audioContext?.close()
    this.stream?.getTracks().forEach(track => track.stop())

    this.processorNode = null
    this.sourceNode = null
    this.audioContext = null
    this.stream = null
  }

  private floatToPcm16(floatData: Float32Array): Int16Array {
    const pcmData = new Int16Array(floatData.length)
    for (let i = 0; i < floatData.length; i++) {
      const sample = Math.max(-1, Math.min(1, floatData[i]))
      pcmData[i] = sample < 0 ? sample * PCM.SIGNED_MASK : sample * PCM.CLIP_THRESHOLD
    }
    return pcmData
  }
}
