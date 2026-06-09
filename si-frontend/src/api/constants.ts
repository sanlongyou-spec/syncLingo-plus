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
