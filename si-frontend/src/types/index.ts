export interface Result<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: string
}

export interface WsMessage {
  type: string
  sessionId?: string
  sourceLang?: string
  targetLang?: string
  voiceId?: string
  audioBase64?: string
  text?: string
  language?: string
  translatedText?: string
  targetLanguage?: string
  code?: string
  message?: string
}

export interface StartInterpretationParams {
  userId: number
  sourceLang: string
  targetLang: string
  voiceId?: string
}

export interface InterpretationStatus {
  sessionId: string
  sourceLang: string
  targetLang: string
  status: string
  voiceId?: string
  startTime?: string
  endTime?: string
}

export interface CloneVoiceParams {
  userId: number
  voiceName: string
  audioSample: string
  language?: string
}

export interface CloneVoiceResponse {
  voiceId: string
  voiceName: string
  durationSeconds: number
  createTime: string
}

export interface UserVoice {
  id: number
  userId: number
  voiceId: string
  voiceName: string
  durationSeconds: number
  createTime: string
}

/**
 * 同传界面支持的语种选项
 */
export const LANGUAGE_OPTIONS = [
  { label: '中文（简体）', value: 'zh-CN' },
  { label: '印尼语', value: 'id-ID' },
] as const

/**
 * 音色克隆支持的语种选项（Cartesia 语言代码）
 */
export const VOICE_CLONE_LANGUAGE_OPTIONS = [
  { label: '中文', value: 'zh' },
  { label: '印尼语', value: 'id' },
  { label: '英语', value: 'en' },
] as const
