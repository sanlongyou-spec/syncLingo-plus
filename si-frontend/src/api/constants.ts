/**
 * API 层常量
 * HTTP、WS 配置不允许硬编码
 */
export const API_DEFAULTS = {
  BASE_URL: import.meta.env.VITE_API_BASE_URL || '',
  TIMEOUT: 30000,
} as const

export const WS_DEFAULTS = {
  BASE_URL: import.meta.env.VITE_WS_BASE_URL || '',
  MAX_RECONNECT: 10,
  RECONNECT_DELAY_MS: 2000,
} as const

export const AUDIO_DEFAULTS = {
  SAMPLE_RATE: 16000,
  CHANNELS: 1,
  BUFFER_SIZE: 4096,
} as const

export const PCM = {
  BITS_PER_SAMPLE: 16,
  SIGNED_MASK: 0x8000,
  CLIP_THRESHOLD: 0x7fff,
} as const

/**
 * VoiceMeeter 虚拟设备标签：译文 TTS 按目标语言 setSinkId 到对应虚拟输入设备，
 * 由 VoiceMeeter 内部把每条虚拟输入路由到对应母线(B1/B2/B3)再灌进会议麦克风。
 * 中文→VoiceMeeter Input(B1)、印尼语→VoiceMeeter Aux Input(B2)、英语→VoiceMeeter VAIO3(B3)。
 */
export const VOICEMEETER = {
  ZH_DEVICE_LABEL: 'VoiceMeeter Input',
  ID_DEVICE_LABEL: 'VoiceMeeter Aux Input',
  EN_DEVICE_LABEL: 'VoiceMeeter VAIO3',
} as const

/** 译文 TTS 输出采样率（Cartesia 合成为 24kHz PCM）。 */
export const TTS_OUTPUT_SAMPLE_RATE = 24000
