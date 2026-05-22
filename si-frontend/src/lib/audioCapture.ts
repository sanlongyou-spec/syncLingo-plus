import { AUDIO_DEFAULTS, PCM } from '../api/constants'

interface AudioCaptureOptions {
  sampleRate?: number
  channels?: number
  onData: (pcmData: Int16Array) => void
}

export function pcmToBase64(pcmData: Int16Array): string {
  const bytes = new Uint8Array(pcmData.buffer, pcmData.byteOffset, pcmData.byteLength)
  let binary = ''
  const chunkSize = 0x8000
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
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
    console.log('[AudioCapture] start system audio capture')
    this.stream = await navigator.mediaDevices.getDisplayMedia({
      video: true,
      audio: true,
    })

    const audioTracks = this.stream.getAudioTracks()
    if (audioTracks.length === 0) {
      this.stop()
      throw new Error('没有获取到系统音频。请在共享弹窗中选择浏览器标签页/窗口，并勾选共享音频。')
    }

    this.audioContext = new AudioContext({ sampleRate: this.sampleRate })
    if ('setSinkId' in this.audioContext) {
      try {
        await (this.audioContext as AudioContext & { setSinkId: (sinkId: { type: 'none' }) => Promise<void> })
          .setSinkId({ type: 'none' })
      } catch (err) {
        console.warn('[AudioCapture] setSinkId none failed:', err)
      }
    }

    this.sourceNode = this.audioContext.createMediaStreamSource(this.stream)
    this.processorNode = this.audioContext.createScriptProcessor(
      AUDIO_DEFAULTS.BUFFER_SIZE,
      this.channels,
      this.channels,
    )

    this.processorNode.onaudioprocess = event => {
      this.frameCount += 1
      const inputData = event.inputBuffer.getChannelData(0)
      if (this.isSilent(inputData)) {
        return
      }
      this.onData(this.floatToPcm16(inputData))
    }

    this.sourceNode.connect(this.processorNode)
    this.processorNode.connect(this.audioContext.destination)
    console.log('[AudioCapture] system audio capture ready, track=', audioTracks[0].label)
  }

  stop(): void {
    console.log('[AudioCapture] stop system audio capture, frames=', this.frameCount)
    this.processorNode?.disconnect()
    this.sourceNode?.disconnect()
    void this.audioContext?.close()
    this.stream?.getTracks().forEach(track => track.stop())

    this.processorNode = null
    this.sourceNode = null
    this.audioContext = null
    this.stream = null
    this.frameCount = 0
  }

  private isSilent(inputData: Float32Array): boolean {
    let sum = 0
    for (let i = 0; i < inputData.length; i += 1) {
      sum += inputData[i] * inputData[i]
    }
    return Math.sqrt(sum / inputData.length) < 0.001
  }

  private floatToPcm16(floatData: Float32Array): Int16Array {
    const pcmData = new Int16Array(floatData.length)
    for (let i = 0; i < floatData.length; i += 1) {
      const sample = Math.max(-1, Math.min(1, floatData[i]))
      pcmData[i] = sample < 0 ? sample * PCM.SIGNED_MASK : sample * PCM.CLIP_THRESHOLD
    }
    return pcmData
  }
}
