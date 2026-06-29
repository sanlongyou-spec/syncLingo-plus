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
 * 译文 TTS 输出设备标签（VB-CABLE 三根虚拟声卡）：按目标语言 setSinkId 到对应 CABLE
 * Input，下游把每根 CABLE Output 接到需要该语言的应用(会议麦克风)。
 * 中文→CABLE Input(原始 VB-CABLE)、印尼语→CABLE-A Input、英语→CABLE-B Input。
 * 要改「语言↔声卡」对应关系，只需调整下面三个标签。
 */
export const TTS_OUTPUT_CABLE = {
  ZH_DEVICE_LABEL: 'CABLE Input',
  ID_DEVICE_LABEL: 'CABLE-A Input',
  EN_DEVICE_LABEL: 'CABLE-B Input',
} as const

/** 译文 TTS 输出采样率（Cartesia 合成为 24kHz PCM）。 */
export const TTS_OUTPUT_SAMPLE_RATE = 24000
