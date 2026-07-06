/**
 * API layer constants.
 * HTTP and WS configuration must not be hard-coded in call sites.
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
 * VoiceMeeter virtual output labels used by host TTS playback.
 * zh -> VoiceMeeter Input (B1), id -> VoiceMeeter Aux Input (B2),
 * en -> VoiceMeeter VAIO3 (B3).
 */
export const VOICEMEETER = {
  ZH_DEVICE_LABEL: 'VoiceMeeter Input',
  ID_DEVICE_LABEL: 'VoiceMeeter Aux Input',
  EN_DEVICE_LABEL: 'VoiceMeeter VAIO3',
} as const

/** Cartesia TTS output sample rate, in Hz. */
export const TTS_OUTPUT_SAMPLE_RATE = 24000
