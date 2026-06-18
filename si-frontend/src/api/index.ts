import client from './client'
import { clearAccessToken, setAccessToken } from './authToken'
import { STORAGE_KEYS } from '../constants'
import type {
  Result,
  UserSummary,
  StartInterpretationParams,
  InterpretationStatus,
  InterpretationResultItem,
  PublicSessionInfo,
  SaveInterpretationResultParams,
  SessionSpeakerIdentity,
  Terminology,
  AsrHotword,
  SystemUserInfo,
  SystemUserImportResult,
  HotwordSuggestion,
  UserLanguagePreference,
  MeetingSummaryVo,
  PreMeetingDailyUsage,
  PreMeetingFile,
  ChatMessage,
  PreMeetingChatResponse,
  TeamsSummarySendResponse,
  Meeting,
  MeetingNotificationPreview,
  MeetingNotificationRecipient,
  MeetingNotificationSendResult,
  MeetingMember,
  MeetingFile,
  SpeakerSummaryResult,
  SpeakerSummaryRecord,
  MeetingActionItem,
  SupportAccessGrant,
  AuditLog,
  CostRates,
  MonthlyCostSummary,
  AudioRecord,
} from '../types'

const RESULT_OK_CODE = 200

const resultFromError = <T>(error: unknown): Result<T> | null => {
  const data = (error as { response?: { data?: unknown } })?.response?.data
  if (data && typeof data === 'object' && 'code' in data && 'message' in data) {
    return data as Result<T>
  }
  return null
}

export const startInterpretation = (params: StartInterpretationParams): Promise<Result<string>> =>
  client.post<Result<string>>('/api/interpretation/start', params).then(r => r.data)

export const stopInterpretation = (sessionId: string): Promise<Result<Record<string, unknown>>> =>
  client.post<Result<Record<string, unknown>>>('/api/interpretation/stop', { sessionId }).then(r => r.data)

export const getCostRates = (): Promise<Result<CostRates>> =>
  client.get<Result<CostRates>>('/api/cost/rates').then(r => r.data)

export const getCostMonthlySummary = (): Promise<Result<MonthlyCostSummary[]>> =>
  client.get<Result<MonthlyCostSummary[]>>('/api/cost/monthly-summary').then(r => r.data)

export const getInterpretationStatus = (sessionId: string): Promise<Result<InterpretationStatus>> =>
  client.get<Result<InterpretationStatus>>(`/api/interpretation/status/${sessionId}`).then(r => r.data)

export const saveInterpretationResult = (params: SaveInterpretationResultParams): Promise<Result<InterpretationResultItem>> =>
  client.post<Result<InterpretationResultItem>>('/api/interpretation/results', params).then(r => r.data)

export const getPublicInterpretationResults = (sessionId: string): Promise<Result<InterpretationResultItem[]>> =>
  client.get<Result<InterpretationResultItem[]>>(`/api/interpretation/public/${sessionId}/results`).then(r => r.data)

export const getPublicSessionInfo = (sessionId: string): Promise<Result<PublicSessionInfo>> =>
  client.get<Result<PublicSessionInfo>>(`/api/interpretation/public/${sessionId}/info`).then(r => r.data)

export interface ShareTokenIssued {
  id: number
  token: string
  kind: string
}

// P4 频道分享令牌:所有者为自己签发不可枚举、可撤销的令牌(原始 token 仅此一次返回)。
export const mintChannelShareToken = (): Promise<Result<ShareTokenIssued>> =>
  client.post<Result<ShareTokenIssued>>('/api/share-tokens/channel').then(r => r.data)

// P4 匿名听众用分享令牌解析当前可收听的 sessionId(替代按 userId 枚举)。
export const resolveShareToken = (token: string): Promise<Result<string | null>> =>
  client.get<Result<string | null>>('/api/interpretation/public/share-resolve', { params: { token } }).then(r => r.data)

export interface ShareWsTicket {
  ticket: string
}

export const mintShareWsTicket = (token: string, lang?: string): Promise<Result<ShareWsTicket>> =>
  client.post<Result<ShareWsTicket>>('/api/interpretation/public/share-ws-tickets', null, {
    params: { token, ...(lang ? { lang } : {}) },
  }).then(r => r.data)

export interface WsTicket {
  ticket: string
}

// P5 WebSocket 一次性握手票据:连接 ASR WS 前换取,避免把长效 JWT 放进 query。
export const mintWsTicket = (): Promise<Result<WsTicket>> =>
  client.post<Result<WsTicket>>('/api/ws-tickets').then(r => r.data)

export interface PublicLatencyReport {
  sessionId: string
  lang: string
  e2eMs: number
  captureMs: number
  rttMs: number
  tailMs: number
  outputLatencyMs: number
  backlogMs: number
  playbackRateMilli: number
}

export const reportPublicLatency = (params: PublicLatencyReport): Promise<Result<void>> =>
  client.post<Result<void>>('/api/interpretation/public/latency', params).then(r => r.data)

export const getUserInterpretationSessions = (keyword = ''): Promise<Result<InterpretationStatus[]>> =>
  client.get<Result<InterpretationStatus[]>>('/api/interpretation/sessions', { params: { keyword } }).then(r => r.data)

export const updateInterpretationSessionTitle = (sessionId: string, title: string): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/interpretation/${sessionId}/title`, { title }).then(r => r.data)

export const deleteInterpretationSession = (sessionId: string): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/interpretation/${sessionId}`).then(r => r.data)

export const translateText = (text: string, sourceLang: string, targetLang: string): Promise<Result<string>> =>
  client.post<Result<string>>('/api/translate', { text, sourceLang, targetLang }).then(r => r.data)

export const getSessionSpeakerIdentities = (sessionId: string): Promise<Result<SessionSpeakerIdentity[]>> =>
  client.get<Result<SessionSpeakerIdentity[]>>(`/api/interpretation/session-speakers/${sessionId}`).then(r => r.data)

export const mapSessionSpeakerIdentity = (
  sessionId: string,
  speakerId: string,
  personName: string,
): Promise<Result<SessionSpeakerIdentity>> =>
  client.put<Result<SessionSpeakerIdentity>>(
    `/api/interpretation/session-speakers/${sessionId}/${encodeURIComponent(speakerId)}`,
    { personName },
  ).then(r => r.data)

export interface CaptchaChallenge {
  captchaId: string
  question: string
  expiresInSeconds: number
}

export interface LoginResult {
  userId: number
  token: string
  role: string
}

export const issueLoginCaptcha = (username: string): Promise<Result<CaptchaChallenge>> =>
  client.get<Result<CaptchaChallenge>>('/api/auth/captcha', { params: { username } }).then(r => r.data)

export const login = (
  username: string,
  password: string,
  captchaId?: string,
  captchaAnswer?: string,
): Promise<Result<LoginResult>> =>
  client.post<Result<LoginResult>>(
    '/api/auth/login',
    { username, password, captchaId, captchaAnswer },
  ).then(r => r.data).catch(error => {
    const result = resultFromError<LoginResult>(error)
    if (result) return result
    throw error
  })

export const refreshAuth = async (): Promise<Result<LoginResult>> => {
  const result = await client.post<Result<LoginResult>>('/api/auth/refresh', {}).then(r => r.data)
  if (result.code === RESULT_OK_CODE && result.data?.token) {
    setAccessToken(result.data.token)
    if (result.data.role) {
      localStorage.setItem(STORAGE_KEYS.ROLE, result.data.role)
    }
  }
  return result
}

export const logout = async (): Promise<Result<void>> => {
  try {
    return await client.post<Result<void>>('/api/auth/logout', {}).then(r => r.data)
  } finally {
    clearAccessToken()
    localStorage.removeItem(STORAGE_KEYS.TOKEN)
    localStorage.removeItem(STORAGE_KEYS.USER_ID)
    localStorage.removeItem(STORAGE_KEYS.ROLE)
  }
}

export const getTerminologies = (keyword = '', enabled?: boolean): Promise<Result<Terminology[]>> =>
  client.get<Result<Terminology[]>>('/api/terminology', { params: { keyword, enabled } }).then(r => r.data)

export const createTerminology = (params: Terminology): Promise<Result<Terminology>> =>
  client.post<Result<Terminology>>('/api/terminology', params).then(r => r.data)

export const updateTerminology = (id: number, params: Terminology): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/terminology/${id}`, params).then(r => r.data)

export const updateTerminologyEnabled = (id: number, enabled: boolean): Promise<Result<void>> =>
  client.patch<Result<void>>(`/api/terminology/${id}/enabled`, null, { params: { enabled } }).then(r => r.data)

export const deleteTerminology = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/terminology/${id}`).then(r => r.data)

export const createHotwordsFromTerminology = (terminologyId: number): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>(`/api/asr-hotwords/from-terminology/${terminologyId}`).then(r => r.data)

export const getAsrHotwords = (
  keyword = '',
  enabled?: boolean,
  language?: string,
  category?: string,
): Promise<Result<AsrHotword[]>> =>
  client.get<Result<AsrHotword[]>>('/api/asr-hotwords', { params: { keyword, enabled, language, category } }).then(r => r.data)

export const createAsrHotword = (params: AsrHotword): Promise<Result<AsrHotword>> =>
  client.post<Result<AsrHotword>>('/api/asr-hotwords', params).then(r => r.data)

export const updateAsrHotword = (id: number, params: AsrHotword): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/asr-hotwords/${id}`, params).then(r => r.data)

export const updateAsrHotwordEnabled = (id: number, enabled: boolean): Promise<Result<void>> =>
  client.patch<Result<void>>(`/api/asr-hotwords/${id}/enabled`, null, { params: { enabled } }).then(r => r.data)

export const deleteAsrHotword = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/asr-hotwords/${id}`).then(r => r.data)

export const createAsrHotwordsBatch = (params: AsrHotword[]): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>('/api/asr-hotwords/batch', params).then(r => r.data)

export const addMeetingHotwords = (
  names: string[],
  venue?: string,
): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>('/api/asr-hotwords/from-meeting', { names, venue }).then(r => r.data)

export const previewHotwordsFromSession = (sessionId: string): Promise<Result<HotwordSuggestion[]>> =>
  client.get<Result<HotwordSuggestion[]>>(`/api/asr-hotwords/extract-preview/${sessionId}`).then(r => r.data)

export const confirmHotwordsFromSession = (hotwords: HotwordSuggestion[]): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>('/api/asr-hotwords/extract-confirm', hotwords).then(r => r.data)

export const getSystemUsers = (keyword = ''): Promise<Result<SystemUserInfo[]>> =>
  client.get<Result<SystemUserInfo[]>>('/api/system-users', { params: { keyword } }).then(r => r.data)

export const createSystemUser = (params: SystemUserInfo): Promise<Result<SystemUserInfo>> =>
  client.post<Result<SystemUserInfo>>('/api/system-users', params).then(r => r.data)

export const updateSystemUser = (id: number, params: SystemUserInfo): Promise<Result<SystemUserInfo>> =>
  client.put<Result<SystemUserInfo>>(`/api/system-users/${id}`, params).then(r => r.data)

export const deleteSystemUser = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/system-users/${id}`).then(r => r.data)

export const importSystemUsers = (file: File): Promise<Result<SystemUserImportResult>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<SystemUserImportResult>>('/api/system-users/import', form, {
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export interface TerminologyImportResult {
  sheetName?: string
  createdCount: number
  skippedCount: number
  totalCount: number
}

export const importTerminology = (file: File): Promise<Result<TerminologyImportResult>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<TerminologyImportResult>>('/api/terminology/import', form, {
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export const getUserLanguagePreference = (): Promise<Result<UserLanguagePreference>> =>
  client.get<Result<UserLanguagePreference>>('/api/language-preferences').then(r => r.data)

export const saveUserLanguagePreference = (
  params: Pick<UserLanguagePreference, 'defaultSourceLang' | 'enabledLanguages'>,
): Promise<Result<UserLanguagePreference>> =>
  client.put<Result<UserLanguagePreference>>('/api/language-preferences', params).then(r => r.data)

export const getMeetingSummary = (sessionId: string): Promise<Result<MeetingSummaryVo>> =>
  client.get<Result<MeetingSummaryVo>>(`/api/summary/${sessionId}`).then(r => r.data)

export const regenerateMeetingSummary = (sessionId: string, customRequirements?: string): Promise<Result<MeetingSummaryVo>> =>
  client.post<Result<MeetingSummaryVo>>(`/api/summary/${sessionId}`, customRequirements ? { customRequirements } : undefined).then(r => r.data)

export const getPreMeetingUsage = (days = 365): Promise<Result<PreMeetingDailyUsage[]>> =>
  client.get<Result<PreMeetingDailyUsage[]>>('/api/pre-meeting/usage', { params: { days } }).then(r => r.data)

export const uploadPreMeetingFile = (file: File): Promise<Result<PreMeetingFile[]>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<PreMeetingFile[]>>('/api/pre-meeting/upload', form, {
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export const saveExpectedParticipants = (fileId: string, meetingId: number): Promise<Result<number>> =>
  client.post<Result<number>>('/api/pre-meeting/attendance/save-expected', { fileId, meetingId }).then(r => r.data)

export interface SummaryExportFormat {
  bodyFont?: string
  bodySize?: number
  headingSize?: number
}

const formatBody = (summary: string, fmt?: SummaryExportFormat) => ({
  summary,
  ...(fmt?.bodyFont ? { bodyFont: fmt.bodyFont } : {}),
  ...(fmt?.bodySize ? { bodySize: String(fmt.bodySize) } : {}),
  ...(fmt?.headingSize ? { headingSize: String(fmt.headingSize) } : {}),
})

export const chatWithPreMeeting = (
  question: string,
  history: ChatMessage[],
  options?: {
    fileId?: string
    sessionId?: string
    crossMeeting?: boolean
    days?: number
  },
): Promise<Result<PreMeetingChatResponse>> =>
  client.post<Result<PreMeetingChatResponse>>('/api/pre-meeting/chat', {
    question,
    history,
    fileId: options?.fileId || null,
    sessionId: options?.sessionId || null,
    crossMeeting: options?.crossMeeting || false,
    days: options?.days || 0,
  }, { timeout: 120_000 }).then(r => r.data)

// Generate a standalone Word from a history summary text (markdown-rendered, with title).
export const generateSummaryDoc = (title: string, summary: string, fmt?: SummaryExportFormat): Promise<Blob> =>
  client.post<Blob>('/api/pre-meeting/summary-doc', { title, ...formatBody(summary, fmt) }, { responseType: 'blob', timeout: 60_000 }).then(r => r.data)

// Generate a 会议总结 PDF (仿宋18/TNR16) from a history summary text.
export const generateSummaryPdf = (title: string, summary: string): Promise<Blob> =>
  client.post<Blob>('/api/pre-meeting/summary-pdf', { title, summary }, { responseType: 'blob', timeout: 120_000 }).then(r => r.data)

// Generate a 发言摘要 PDF: 会议名(标题) / 发言人小标题 / 正文 / 日期 / 整理.
export const generateSpeakerSummaryPdf = (params: {
  meetingName: string
  speakerName: string
  sequence: number
  dateText: string
  body: string
}): Promise<Blob> =>
  client.post<Blob>('/api/pre-meeting/speaker-summary-pdf', {
    meetingName: params.meetingName,
    speakerName: params.speakerName,
    sequence: String(params.sequence),
    dateText: params.dateText,
    body: params.body,
  }, { responseType: 'blob', timeout: 120_000 }).then(r => r.data)

export const sendTeamsSummaryToUsers = (
  content: string,
  recipients: string[],
): Promise<TeamsSummarySendResponse> =>
  client.post<TeamsSummarySendResponse>('/bot-api/api/meetings/summary', { content, recipients })
    .then(r => r.data)
    .catch(error => {
      const data = error?.response?.data as Partial<TeamsSummarySendResponse> | undefined
      if (data && typeof data === 'object' && 'sent' in data) {
        return {
          sent: Boolean(data.sent),
          sentCount: Number(data.sentCount ?? 0),
          failedCount: Number(data.failedCount ?? data.failures?.length ?? recipients.length),
          recipients: Array.isArray(data.recipients) ? data.recipients : [],
          failures: Array.isArray(data.failures) ? data.failures : [],
          error: data.error,
        }
      }
      const message = error?.response?.data?.error || error?.message || 'Teams user summary delivery failed'
      throw new Error(message)
    })

// ── Meeting management ───────────────────────────────────────────────────────

export const createMeeting = (params: {
  title: string
  scheduledTime?: string
  note?: string
}): Promise<Result<Meeting>> =>
  client.post<Result<Meeting>>('/api/meetings', params).then(r => {
    // BizException (e.g. duplicate meeting name) returns HTTP 200 with an error code in the body.
    if (r.data?.code !== 200) throw new Error(r.data?.message || '创建会议失败')
    return r.data
  })

export const getMeetings = (): Promise<Result<Meeting[]>> =>
  client.get<Result<Meeting[]>>('/api/meetings').then(r => r.data)

export const uploadFileToMeeting = (meetingId: number, file: File): Promise<Result<MeetingFile>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<MeetingFile>>(`/api/meetings/${meetingId}/files`, form, {
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export const deleteMeetingFile = (meetingId: number, fileId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/${meetingId}/files/${fileId}`).then(r => r.data)

export const previewMeetingNotification = (
  meetingId: number,
  meetingUrl?: string,
  fileId?: string,
): Promise<MeetingNotificationPreview> =>
  client.post<Result<MeetingNotificationPreview>>(`/api/meetings/${meetingId}/notification-preview`, {
    ...(meetingUrl ? { meetingUrl } : {}),
    ...(fileId ? { fileId } : {}),
  }).then(r => {
    if (r.data?.code !== RESULT_OK_CODE) {
      throw new Error(r.data?.message || '解析会议通知失败')
    }
    return r.data.data
  })

export const getMeetingNotificationRecipients = (meetingId: number): Promise<MeetingNotificationRecipient[]> =>
  client.get<Result<MeetingNotificationRecipient[]>>(`/api/meetings/${meetingId}/notification-recipients`).then(r => {
    if (r.data?.code !== RESULT_OK_CODE) {
      throw new Error(r.data?.message || '获取通知账号失败')
    }
    return r.data.data || []
  })

export const sendMeetingNotification = (
  meetingId: number,
  content: string,
  recipients: string[],
): Promise<MeetingNotificationSendResult> =>
  client.post<Result<MeetingNotificationSendResult>>(`/api/meetings/${meetingId}/notification-send`, {
    content,
    recipients,
  }).then(r => {
    if (r.data?.code !== RESULT_OK_CODE) {
      throw new Error(r.data?.message || '发送会议通知失败')
    }
    return r.data.data
  })

export const generateSpeakerSummary = (params: {
  sessionId: string
  speakerId?: string
  speakerName?: string
  requirements?: string
  text: string
}): Promise<Result<SpeakerSummaryResult>> =>
  client.post<Result<SpeakerSummaryResult>>('/api/meetings/speaker-summary', params, {
    timeout: 60_000,
  }).then(r => r.data)

export const getSpeakerSummaries = (sessionId: string): Promise<Result<SpeakerSummaryRecord[]>> =>
  client.get<Result<SpeakerSummaryRecord[]>>(`/api/meetings/speaker-summaries/${encodeURIComponent(sessionId)}`).then(r => r.data)

export const updateSpeakerSummary = (
  id: number,
  params: Pick<SpeakerSummaryRecord, 'speakerName' | 'summary'>,
): Promise<Result<SpeakerSummaryRecord>> =>
  client.put<Result<SpeakerSummaryRecord>>(`/api/meetings/speaker-summaries/${id}`, params).then(r => r.data)

export const regenerateSpeakerSummary = (
  id: number,
  requirements?: string,
): Promise<Result<SpeakerSummaryResult>> =>
  client.post<Result<SpeakerSummaryResult>>(
    `/api/meetings/speaker-summaries/${id}/regenerate`,
    requirements ? { requirements } : undefined,
    { timeout: 60_000 },
  ).then(r => r.data)

export const getMeetingSessions = (meetingId: number): Promise<Result<InterpretationStatus[]>> =>
  client.get<Result<InterpretationStatus[]>>(`/api/meetings/${meetingId}/sessions`).then(r => r.data)

export const deleteMeeting = (meetingId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/${meetingId}`).then(r => r.data)

export const listMeetingMembers = (meetingId: number): Promise<Result<MeetingMember[]>> =>
  client.get<Result<MeetingMember[]>>(`/api/meetings/${meetingId}/members`).then(r => r.data)

export const assignMeetingMember = (
  meetingId: number,
  userId: number,
  accessLevel: 'VIEW' | 'OPERATE',
): Promise<Result<MeetingMember>> =>
  client.post<Result<MeetingMember>>(`/api/meetings/${meetingId}/members`, { userId, accessLevel }).then(r => r.data)

export const revokeMeetingMember = (meetingId: number, userId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/${meetingId}/members/${userId}`).then(r => r.data)

export const requestSupportGrant = (
  meetingId: number,
  reason: string,
  ttlMinutes: number,
): Promise<Result<number>> =>
  client.post<Result<number>>('/api/support-grants', { meetingId, reason, ttlMinutes }).then(r => r.data)

export const approveSupportGrant = (id: number): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/support-grants/${id}/approve`).then(r => r.data)

export const revokeSupportGrant = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/support-grants/${id}`).then(r => r.data)

export const listSupportGrants = (limit = 100): Promise<Result<SupportAccessGrant[]>> =>
  client.get<Result<SupportAccessGrant[]>>('/api/support-grants', { params: { limit } }).then(r => r.data)

export const listAuditLogs = (limit = 100): Promise<Result<AuditLog[]>> =>
  client.get<Result<AuditLog[]>>('/api/users/audit', { params: { limit } }).then(r => r.data)

export const getActionItems = (sessionId: string): Promise<Result<MeetingActionItem[]>> =>
  client.get<Result<MeetingActionItem[]>>(`/api/meetings/sessions/${encodeURIComponent(sessionId)}/action-items`).then(r => r.data)

export const extractActionItems = (
  sessionId: string,
  meetingId?: number | null,
): Promise<Result<MeetingActionItem[]>> =>
  client.post<Result<MeetingActionItem[]>>(
    `/api/meetings/sessions/${encodeURIComponent(sessionId)}/action-items/extract`,
    { meetingId },
    { timeout: 60_000 },
  ).then(r => r.data)

export const updateActionItemStatus = (id: number, status: string): Promise<Result<MeetingActionItem>> =>
  client.patch<Result<MeetingActionItem>>(`/api/meetings/action-items/${id}/status`, { status }).then(r => r.data)

export const deleteActionItem = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/action-items/${id}`).then(r => r.data)

export const getAudioRecords = (
  keyword?: string,
  page?: number,
  size?: number,
  meetingId?: number,
): Promise<Result<{ items: AudioRecord[]; total: number }>> =>
  client.get<Result<{ items: AudioRecord[]; total: number }>>('/api/audio-records', {
    params: { keyword: keyword || '', page: page ?? 1, size: size ?? 50, ...(meetingId ? { meetingId } : {}) },
  }).then(r => r.data)

export const renameAudioRecord = (id: number, name: string): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/audio-records/${id}/name`, null, { params: { name } }).then(r => r.data)

export const deleteAudioRecord = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/audio-records/${id}`).then(r => r.data)

export const getAudioDownloadUrl = (id: number): string =>
  `/api/audio-records/${id}/download`

export const getSummaryRecipients = (): Promise<Result<string[]>> =>
  client.get<Result<string[]>>('/api/user/preference/summary-recipients').then(r => r.data)

export const saveSummaryRecipients = (recipients: string[]): Promise<Result<void>> =>
  client.put<Result<void>>('/api/user/preference/summary-recipients', recipients).then(r => r.data)

// ── P1 用户管理(仅 ADMIN;/me 任意已登录用户) ──────────────────────────
export const getMe = (): Promise<Result<UserSummary>> =>
  client.get<Result<UserSummary>>('/api/users/me').then(r => r.data)

export const listUsers = (): Promise<Result<UserSummary[]>> =>
  client.get<Result<UserSummary[]>>('/api/users').then(r => r.data)

export const createUser = (params: { username: string; password: string; role: string; nickname?: string; email?: string }): Promise<Result<UserSummary>> =>
  client.post<Result<UserSummary>>('/api/users', params).then(r => r.data)

export const updateUserRole = (id: number, role: string): Promise<Result<UserSummary>> =>
  client.put<Result<UserSummary>>(`/api/users/${id}/role`, { role }).then(r => r.data)

export const updateUserStatus = (id: number, status: string): Promise<Result<UserSummary>> =>
  client.put<Result<UserSummary>>(`/api/users/${id}/status`, { status }).then(r => r.data)

export const resetUserPassword = (id: number, password: string): Promise<Result<void>> =>
  client.post<Result<void>>(`/api/users/${id}/reset-password`, { password }).then(r => r.data)
