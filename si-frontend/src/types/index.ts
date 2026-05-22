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
  speakerId?: string
  speakerName?: string
  speakerProfileId?: string
  speakerIdentityStatus?: string
  speakerIdentitySource?: string
  translatedText?: string
  targetLanguage?: string
  ttsTaskId?: string
  ttsSequence?: number
  chunkIndex?: number
  code?: string
  message?: string
}

export interface StartInterpretationParams {
  userId: number
  sourceLang: string
  targetLang: string
  voiceId?: string
  hotwordIds?: number[]
  enabledLanguages?: string[]
}

export interface InterpretationStatus {
  sessionId: string
  sourceLang: string
  targetLang: string
  title?: string
  status: string
  voiceId?: string
  startTime?: string
  endTime?: string
  resultCount?: number
  asrAudioMs?: number
  translateChars?: number
  ttsChars?: number
  llmInputTokens?: number
  llmOutputTokens?: number
  meetingSummary?: string
}

export interface MeetingSummaryVo {
  sessionId: string
  summary: string
  recordCount: number | null
}

export interface MeetingMaterial {
  id?: number
  sessionId?: string
  title?: string
  agendaText?: string
  reportText?: string
  executiveNames?: string
  summaryText?: string
  recordCount?: number
  createTime?: string
  updateTime?: string
}

export interface TeamsSummarySendTarget {
  recipient: string
  aadId: string
  displayName?: string | null
  email?: string | null
}

export interface TeamsSummarySendFailure {
  recipient: string
  error: string
}

export interface TeamsSummarySendResponse {
  sent: boolean
  sentCount: number
  failedCount: number
  recipients: TeamsSummarySendTarget[]
  failures: TeamsSummarySendFailure[]
}

export interface MeetingParticipant {
  aadId: string
  displayName?: string | null
  email?: string | null
}

export interface MeetingParticipantsResponse {
  callId?: string | null
  threadId?: string | null
  participants: MeetingParticipant[]
}

export interface InterpretationResultItem {
  id: number
  sessionId: string
  sourceText: string
  translatedText: string
  sourceLang?: string
  targetLang?: string
  createTime?: string
}

export interface SaveInterpretationResultParams {
  sessionId: string
  sourceText: string
  translatedText: string
  sourceLang?: string
  targetLang?: string
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

export interface SpeakerIdentity {
  id?: number
  personName: string
  speakerProfileId?: string
  cartesiaVoiceId?: string
  language?: string
  note?: string
  createTime?: string
  updateTime?: string
}

export interface SessionSpeakerIdentity {
  sessionId: string
  speakerId: string
  personName?: string
  speakerProfileId?: string
  cartesiaVoiceId?: string
  status?: string
  source?: string
}

export interface Terminology {
  id?: number
  termZh?: string
  termId?: string
  termEn?: string
  pinyin?: string
  category?: string
  note?: string
  sourceSheet?: string
  sourceRow?: number
  reviewStatus?: string
  enabled?: boolean
  createTime?: string
  updateTime?: string
}

export interface AsrHotword {
  id?: number
  userId?: number
  phrase?: string
  language?: string
  category?: string
  weight?: number
  sourceType?: string
  sourceTerminologyId?: number
  enabled?: boolean
  expiresAt?: string
  lastUsedTime?: string
}

export interface UserGlossaryConfig {
  id?: number
  userId?: number
  sourceLang?: string
  targetLang?: string
  glossaryId?: string
  enabled?: boolean
}

export interface UserLanguagePreference {
  userId: number
  defaultSourceLang: string
  enabledLanguages: string[]
}

/**
 * 同传界面支持的语种选项
 */
export const LANGUAGE_OPTIONS = [
  { label: '中文（简体）', value: 'zh-CN' },
  { label: '印尼语', value: 'id-ID' },
  { label: '英语', value: 'en-US' },
] as const

/**
 * 音色克隆支持的语种选项（Cartesia 语言代码）
 */
export const VOICE_CLONE_LANGUAGE_OPTIONS = [
  { label: '中文', value: 'zh' },
  { label: '印尼语', value: 'id' },
  { label: '英语', value: 'en' },
] as const
