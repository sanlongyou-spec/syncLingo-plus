export interface Result<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: string
}

export interface UserSummary {
  id: number
  username: string
  nickname?: string | null
  email?: string | null
  role: string
  status: string
  createTime?: string | null
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
  sourceLang: string
  targetLang: string
  title?: string
  voiceId?: string
  hotwordIds?: number[]
  enabledLanguages?: string[]
  meetingId?: number | null
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
  llmSummaryInputTokens?: number
  llmSummaryOutputTokens?: number
  meetingSummary?: string
}

export interface MeetingSummaryVo {
  sessionId: string
  title?: string
  summary: string
  recordCount: number | null
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
  error?: string
}

export interface PreMeetingFile {
  fileId: string
  fileName: string
  meetingTitle?: string | null
}

export interface InterpretationResultItem {
  id: number
  sessionId: string
  meetingTitle?: string
  sourceText: string
  translatedText: string
  sourceLang?: string
  targetLang?: string
  speakerId?: string
  speakerName?: string
  createTime?: string
}

export interface PublicSessionInfo {
  sessionId: string
  title?: string
  status?: string
  enabledLanguages: string[]
}

export interface SaveInterpretationResultParams {
  sessionId: string
  sourceText: string
  translatedText: string
  sourceLang?: string
  targetLang?: string
  speakerId?: string
  speakerName?: string
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
  authorized?: boolean
  scope?: string
  disabled?: boolean
  createTime: string
  updateTime?: string
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

export interface SystemUserInfo {
  id?: number
  department?: string
  personName?: string
  positionTitle?: string
  email?: string
  microsoftId?: string
  robinUid?: string
  teamsVerified?: string
  employmentStatus?: string
  sourceSheet?: string
  sourceRow?: number
  createTime?: string
  updateTime?: string
}

export interface SystemUserImportResult {
  sheetName?: string
  createdCount: number
  updatedCount: number
  skippedCount: number
  totalCount: number
}

export interface HotwordSuggestion {
  phrase: string
  category: string
  language: string
  exists?: boolean
}

export interface PreMeetingDailyUsage {
  date: string
  llmInputTokens: number
  llmOutputTokens: number
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface PreMeetingChatResponse {
  answer: string
  contextSummary: string
  referencedSessions?: string[]
}

export interface Meeting {
  id: number
  userId: number
  title: string
  scheduledTime?: string | null
  note?: string | null
  meetingUrl?: string | null
  hasExpectedParticipants?: boolean
  createTime?: string
  files?: MeetingFile[]
}

export interface MeetingMember {
  userId: number
  username?: string | null
  accessLevel: 'VIEW' | 'OPERATE' | string
}

export interface SupportAccessGrant {
  id: number
  granteeUserId: number
  resourceType: string
  resourceId: string
  permissions: string
  reason: string
  requestedBy: number
  approvedBy?: number | null
  expiresAt?: string | null
  revokedAt?: string | null
  createTime?: string | null
}

export interface AuditLog {
  id: number
  actorType: string
  actorId?: string | null
  role?: string | null
  action: string
  resourceType?: string | null
  resourceId?: string | null
  result: string
  ip?: string | null
  detail?: string | null
  createTime?: string | null
}

export interface MeetingFile {
  id: number
  meetingId: number
  fileName: string
  fileType?: string | null
  summary?: string | null
  createTime?: string
}

export interface MeetingNotificationRecipient {
  scheduleName: string
  accountName: string
  email: string
  teamsAccount: string
}

export interface MeetingNotificationPreview {
  meetingName: string
  dateText?: string | null
  timeLines: string[]
  venue?: string | null
  meetingCode?: string | null
  passcode?: string | null
  meetingUrl?: string | null
  notificationContent: string
  participantNames: string[]
  teamsRecipients: MeetingNotificationRecipient[]
  nonTeamsSkipped: string[]
  unmatched: string[]
}

export interface MeetingNotificationSendResult {
  selectedRecipientCount: number
  deliveryRecipientCount: number
  botStatusCode: number
  sentCount: number
  failedCount: number
  successfulRecipients: string[]
  failedRecipients: string[]
  error?: string | null
}

export interface SpeakerSummaryResult {
  speakerId?: string
  speakerName: string
  title?: string | null
  summary: string
}

export interface SpeakerSummaryRecord {
  id?: number
  sessionId?: string
  speakerId?: string | null
  speakerName: string
  title?: string | null
  textSnippet?: string | null
  summary: string
  createTime?: string | null
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

export interface MeetingActionItem {
  id: number
  sessionId: string
  meetingId?: number | null
  userId?: number | null
  assignee?: string | null
  content: string
  deadline?: string | null
  status: 'pending' | 'done' | 'cancelled'
  createTime?: string | null
}

export interface CostRates {
  asrPerMs: number
  transPerChar: number
  ttsPerChar: number
  llmInPerToken: number
  llmOutPerToken: number
  summaryLlmInPerToken: number
  summaryLlmOutPerToken: number
  monthlyBudgetUsd: number
  sessionBudgetUsd: number
}

export interface MonthlyCostSummary {
  month: string
  sessionCount: number
  totalAsrMs: number
  totalTransChars: number
  totalTtsChars: number
  totalLlmIn: number
  totalLlmOut: number
  totalSummaryLlmIn?: number
  totalSummaryLlmOut?: number
  estimatedCostUsd: number
}

export interface AudioRecord {
  id: number
  sessionId: string
  userId: number
  meetingId?: number | null
  name: string
  filePath?: string
  fileSizeBytes: number
  durationMs: number
  sampleRate: number
  createTime: string
}

