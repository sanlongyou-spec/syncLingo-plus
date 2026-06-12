import client from './client'
import type {
  Result,
  StartInterpretationParams,
  InterpretationStatus,
  InterpretationResultItem,
  PublicSessionInfo,
  SaveInterpretationResultParams,
  CloneVoiceParams,
  CloneVoiceResponse,
  UserVoice,
  SpeakerIdentity,
  SessionSpeakerIdentity,
  Terminology,
  AsrHotword,
  SystemUserInfo,
  SystemUserImportResult,
  HotwordSuggestion,
  UserLanguagePreference,
  MeetingSummaryVo,
  PreMeetingFile,
  PreMeetingAttendanceResult,
  PreMeetingSummaryResult,
  PreMeetingDailyUsage,
  ChatMessage,
  PreMeetingChatResponse,
  TeamsSummarySendResponse,
  MeetingParticipant,
  MeetingParticipantsResponse,
  Meeting,
  MeetingFile,
  MeetingNotificationPreview,
  MeetingNotificationRecipient,
  MeetingNotificationSendResult,
  SpeakerSummaryResult,
  SpeakerSummaryRecord,
  MeetingActionItem,
  CostRates,
  MonthlyCostSummary,
  AudioRecord,
} from '../types'

const getApiErrorMessage = async (error: unknown, fallback: string): Promise<string> => {
  const responseData = (error as { response?: { data?: unknown } })?.response?.data
  if (responseData instanceof Blob) {
    const text = await responseData.text()
    if (text) {
      try {
        const parsed = JSON.parse(text) as { message?: string; error?: string }
        return parsed.message || parsed.error || fallback
      } catch {
        return text
      }
    }
  }
  const errorLike = error as { response?: { data?: { message?: string; error?: string } }; message?: string }
  return errorLike.response?.data?.message || errorLike.response?.data?.error || errorLike.message || fallback
}

const RESULT_OK_CODE = 200

const ensureResultData = <T>(result: Result<T>, fallback: string): Result<T> => {
  if (result.code !== RESULT_OK_CODE) {
    throw new Error(result.message?.trim() || fallback)
  }
  if (result.data == null) {
    throw new Error(fallback)
  }
  return result
}

export const startInterpretation = (params: StartInterpretationParams): Promise<Result<string>> =>
  client.post<Result<string>>('/api/interpretation/start', params).then(r => r.data)

export const stopInterpretation = (sessionId: string): Promise<Result<Record<string, unknown>>> =>
  client.post<Result<Record<string, unknown>>>('/api/interpretation/stop', { sessionId }).then(r => r.data)

export const getCostRates = (): Promise<Result<CostRates>> =>
  client.get<Result<CostRates>>('/api/cost/rates').then(r => r.data)

export const getCostMonthlySummary = (userId: number): Promise<Result<MonthlyCostSummary[]>> =>
  client.get<Result<MonthlyCostSummary[]>>('/api/cost/monthly-summary', { params: { userId } }).then(r => r.data)

export const getInterpretationStatus = (sessionId: string): Promise<Result<InterpretationStatus>> =>
  client.get<Result<InterpretationStatus>>(`/api/interpretation/status/${sessionId}`).then(r => r.data)

export const saveInterpretationResult = (params: SaveInterpretationResultParams): Promise<Result<InterpretationResultItem>> =>
  client.post<Result<InterpretationResultItem>>('/api/interpretation/results', params).then(r => r.data)

export const getPublicInterpretationResults = (sessionId: string): Promise<Result<InterpretationResultItem[]>> =>
  client.get<Result<InterpretationResultItem[]>>(`/api/interpretation/public/${sessionId}/results`).then(r => r.data)

export const getPublicSessionInfo = (sessionId: string): Promise<Result<PublicSessionInfo>> =>
  client.get<Result<PublicSessionInfo>>(`/api/interpretation/public/${sessionId}/info`).then(r => r.data)

export const getActiveSessionForUser = (userId: number): Promise<Result<string | null>> =>
  client.get<Result<string | null>>(`/api/interpretation/public/user/${userId}/active`).then(r => r.data)

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

export const getUserInterpretationSessions = (userId: number, keyword = ''): Promise<Result<InterpretationStatus[]>> =>
  client.get<Result<InterpretationStatus[]>>(`/api/interpretation/users/${userId}/sessions`, { params: { keyword } }).then(r => r.data)

export const updateInterpretationSessionTitle = (sessionId: string, userId: number, title: string): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/interpretation/${sessionId}/title`, { title }, { params: { userId } }).then(r => r.data)

export const deleteInterpretationSession = (sessionId: string, userId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/interpretation/${sessionId}`, { params: { userId } }).then(r => r.data)

export const translateText = (text: string, sourceLang: string, targetLang: string, userId = 1): Promise<Result<string>> =>
  client.post<Result<string>>('/api/translate', { text, sourceLang, targetLang, userId }).then(r => r.data)

export const cloneVoice = (params: CloneVoiceParams): Promise<Result<CloneVoiceResponse>> =>
  client.post<Result<CloneVoiceResponse>>('/api/voice/clone', params).then(r => r.data)

export const getUserVoice = (userId: number): Promise<Result<UserVoice | null>> =>
  client.get<Result<UserVoice | null>>(`/api/voice/${userId}`).then(r => r.data)

export const deleteUserVoice = (userId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/voice/${userId}`).then(r => r.data)

export const getSpeakerIdentities = (): Promise<Result<SpeakerIdentity[]>> =>
  client.get<Result<SpeakerIdentity[]>>('/api/voice/speaker-identities').then(r => r.data)

export const saveSpeakerIdentity = (params: SpeakerIdentity): Promise<Result<SpeakerIdentity>> => {
  if (params.id) {
    return client.put<Result<SpeakerIdentity>>(`/api/voice/speaker-identities/${params.id}`, params).then(r => r.data)
  }
  return client.post<Result<SpeakerIdentity>>('/api/voice/speaker-identities', params).then(r => r.data)
}

export const deleteSpeakerIdentity = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/voice/speaker-identities/${id}`).then(r => r.data)

export const getSessionSpeakerIdentities = (sessionId: string): Promise<Result<SessionSpeakerIdentity[]>> =>
  client.get<Result<SessionSpeakerIdentity[]>>(`/api/interpretation/session-speakers/${sessionId}`).then(r => r.data)

export const enrollSpeakerProfile = (
  id: number,
  audioBase64: string,
  locale: string,
): Promise<Result<SpeakerIdentity>> =>
  client.post<Result<SpeakerIdentity>>(`/api/voice/speaker-identities/${id}/enroll`, { audioBase64, locale }).then(r => r.data)

export const mapSessionSpeakerIdentity = (
  sessionId: string,
  speakerId: string,
  personName: string,
): Promise<Result<SessionSpeakerIdentity>> =>
  client.put<Result<SessionSpeakerIdentity>>(
    `/api/interpretation/session-speakers/${sessionId}/${encodeURIComponent(speakerId)}`,
    { personName },
  ).then(r => r.data)

export const login = (username: string, password: string): Promise<Result<{ userId: number; token: string }>> =>
  client.post<Result<{ userId: number; token: string }>>('/api/auth/login', { username, password }).then(r => r.data)

export const getTerminologies = (userId: number, keyword = '', enabled?: boolean): Promise<Result<Terminology[]>> =>
  client.get<Result<Terminology[]>>('/api/terminology', { params: { userId, keyword, enabled } }).then(r => r.data)

export const createTerminology = (userId: number, params: Terminology): Promise<Result<Terminology>> =>
  client.post<Result<Terminology>>('/api/terminology', params, { params: { userId } }).then(r => r.data)

export const updateTerminology = (userId: number, id: number, params: Terminology): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/terminology/${id}`, params, { params: { userId } }).then(r => r.data)

export const updateTerminologyEnabled = (userId: number, id: number, enabled: boolean): Promise<Result<void>> =>
  client.patch<Result<void>>(`/api/terminology/${id}/enabled`, null, { params: { userId, enabled } }).then(r => r.data)

export const deleteTerminology = (userId: number, id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/terminology/${id}`, { params: { userId } }).then(r => r.data)

export const createHotwordsFromTerminology = (userId: number, terminologyId: number): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>(`/api/asr-hotwords/from-terminology/${terminologyId}`, null, { params: { userId } }).then(r => r.data)

export const getAsrHotwords = (
  userId: number,
  keyword = '',
  enabled?: boolean,
  language?: string,
  category?: string,
): Promise<Result<AsrHotword[]>> =>
  client.get<Result<AsrHotword[]>>('/api/asr-hotwords', { params: { userId, keyword, enabled, language, category } }).then(r => r.data)

export const createAsrHotword = (userId: number, params: AsrHotword): Promise<Result<AsrHotword>> =>
  client.post<Result<AsrHotword>>('/api/asr-hotwords', params, { params: { userId } }).then(r => r.data)

export const updateAsrHotword = (userId: number, id: number, params: AsrHotword): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/asr-hotwords/${id}`, params, { params: { userId } }).then(r => r.data)

export const updateAsrHotwordEnabled = (userId: number, id: number, enabled: boolean): Promise<Result<void>> =>
  client.patch<Result<void>>(`/api/asr-hotwords/${id}/enabled`, null, { params: { userId, enabled } }).then(r => r.data)

export const deleteAsrHotword = (userId: number, id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/asr-hotwords/${id}`, { params: { userId } }).then(r => r.data)

export const createAsrHotwordsBatch = (userId: number, params: AsrHotword[]): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>('/api/asr-hotwords/batch', params, { params: { userId } }).then(r => r.data)

export const addMeetingHotwords = (
  userId: number,
  names: string[],
  venue?: string,
): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>('/api/asr-hotwords/from-meeting', { names, venue }, { params: { userId } }).then(r => r.data)

export const previewHotwordsFromSession = (sessionId: string, userId: number): Promise<Result<HotwordSuggestion[]>> =>
  client.get<Result<HotwordSuggestion[]>>(`/api/asr-hotwords/extract-preview/${sessionId}`, { params: { userId } }).then(r => r.data)

export const confirmHotwordsFromSession = (userId: number, hotwords: HotwordSuggestion[]): Promise<Result<AsrHotword[]>> =>
  client.post<Result<AsrHotword[]>>('/api/asr-hotwords/extract-confirm', hotwords, { params: { userId } }).then(r => r.data)

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

export const importTerminology = (userId: number, file: File): Promise<Result<TerminologyImportResult>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<TerminologyImportResult>>('/api/terminology/import', form, {
    params: { userId },
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export const getUserLanguagePreference = (userId: number): Promise<Result<UserLanguagePreference>> =>
  client.get<Result<UserLanguagePreference>>('/api/language-preferences', { params: { userId } }).then(r => r.data)

export const saveUserLanguagePreference = (
  userId: number,
  params: Pick<UserLanguagePreference, 'defaultSourceLang' | 'enabledLanguages'>,
): Promise<Result<UserLanguagePreference>> =>
  client.put<Result<UserLanguagePreference>>('/api/language-preferences', params, { params: { userId } }).then(r => r.data)

export const getMeetingSummary = (sessionId: string): Promise<Result<MeetingSummaryVo>> =>
  client.get<Result<MeetingSummaryVo>>(`/api/summary/${sessionId}`).then(r => r.data)

export const regenerateMeetingSummary = (sessionId: string, customRequirements?: string): Promise<Result<MeetingSummaryVo>> =>
  client.post<Result<MeetingSummaryVo>>(`/api/summary/${sessionId}`, customRequirements ? { customRequirements } : undefined).then(r => r.data)

export const uploadPreMeetingFile = (file: File, userId?: number): Promise<Result<PreMeetingFile[]>> => {
  const form = new FormData()
  form.append('file', file)
  const params = userId != null ? { userId } : {}
  return client.post<Result<PreMeetingFile[]>>('/api/pre-meeting/upload', form, {
    headers: { 'Content-Type': undefined },
    params,
  }).then(r => r.data)
}

export const summarizePreMeetingFile = (
  fileId: string,
  requirements: string,
  userId?: number,
  meetingId?: number | null,
): Promise<Result<PreMeetingSummaryResult>> =>
  client.post<Result<PreMeetingSummaryResult>>('/api/pre-meeting/summarize', { fileId, requirements, userId, meetingId }, {
    timeout: 300_000,
  }).then(r => ensureResultData(r.data, '生成总结失败'))

export const generatePreMeetingAttendance = (
  fileId: string,
  actualParticipants: MeetingParticipant[],
): Promise<Result<PreMeetingAttendanceResult>> =>
  client.post<Result<PreMeetingAttendanceResult>>('/api/pre-meeting/attendance', {
    fileId,
    actualParticipants,
  }).then(r => r.data)

// Re-generate attendance from the 应到 list saved on the meeting (no in-memory 会议安排 needed).
export const generateAttendanceFromMeeting = (
  meetingId: number,
  actualParticipants: MeetingParticipant[],
): Promise<Result<PreMeetingAttendanceResult>> =>
  client.post<Result<PreMeetingAttendanceResult>>('/api/pre-meeting/attendance', {
    meetingId,
    actualParticipants,
  }).then(r => r.data)

// Persist the 应到 list parsed from a freshly-uploaded 会议安排 onto the meeting.
export const saveExpectedParticipants = (fileId: string, meetingId: number): Promise<Result<number>> =>
  client.post<Result<number>>('/api/pre-meeting/attendance/save-expected', { fileId, meetingId }).then(r => r.data)

export interface ExportPreMeetingAttendanceDocxParams {
  fileId?: string | null
  meetingId?: number | null
  actualParticipants: MeetingParticipant[]
}

export const exportPreMeetingAttendanceDocx = (
  params: ExportPreMeetingAttendanceDocxParams,
): Promise<Blob> =>
  client.post<Blob>('/api/pre-meeting/attendance/export', params, { responseType: 'blob', timeout: 60_000 })
    .then(r => r.data)
    .catch(async error => {
      throw new Error(await getApiErrorMessage(error, '导出实际参会名单失败'))
    })

export const getPreMeetingUsage = (userId: number, days = 365): Promise<Result<PreMeetingDailyUsage[]>> =>
  client.get<Result<PreMeetingDailyUsage[]>>('/api/pre-meeting/usage', { params: { userId, days } }).then(r => r.data)

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

export const exportPreMeetingDocx = (fileId: string, summary: string, fmt?: SummaryExportFormat): Promise<Blob> =>
  client.post<Blob>(`/api/pre-meeting/export/${fileId}`, formatBody(summary, fmt), { responseType: 'blob', timeout: 60_000 }).then(r => r.data)

export const exportPreMeetingPdf = (fileId: string, summary: string, fmt?: SummaryExportFormat): Promise<Blob> =>
  client.post<Blob>(`/api/pre-meeting/export/pdf/${fileId}`, formatBody(summary, fmt), { responseType: 'blob', timeout: 120_000 }).then(r => r.data)

export const chatWithPreMeeting = (
  question: string,
  history: ChatMessage[],
  options?: {
    fileId?: string
    sessionId?: string
    crossMeeting?: boolean
    userId?: number
    days?: number
  },
): Promise<Result<PreMeetingChatResponse>> =>
  client.post<Result<PreMeetingChatResponse>>('/api/pre-meeting/chat', {
    question,
    history,
    fileId: options?.fileId || null,
    sessionId: options?.sessionId || null,
    crossMeeting: options?.crossMeeting || false,
    userId: options?.userId || 1,
    days: options?.days || 0,
  }, { timeout: 120_000 }).then(r => r.data)

// Generate a standalone Word from a history summary text (markdown-rendered, with title).
export const generateSummaryDoc = (title: string, summary: string, fmt?: SummaryExportFormat): Promise<Blob> =>
  client.post<Blob>('/api/pre-meeting/summary-doc', { title, ...formatBody(summary, fmt) }, { responseType: 'blob', timeout: 60_000 }).then(r => r.data)

// Generate a 会议总结 PDF (仿宋18/TNR16, same as the AI summary) from a history summary text.
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

export interface SummaryFileSendResult {
  sent: boolean
  userSent: number
  userFailed: number
  chatThreadId: string | null
  downloadUrl: string
  error?: string | null
}

// Upload a generated PDF to the bot; the bot stores it in Teams/SharePoint and sends the
// SharePoint link to the chosen account(s) and/or the active meeting chat.
export const sendSummaryFileToTeams = (
  blob: Blob,
  fileName: string,
  opts: { title?: string; recipients?: string[]; sendToChat?: boolean },
): Promise<SummaryFileSendResult> => {
  const form = new FormData()
  form.append('file', blob, fileName)
  form.append('fileName', fileName)
  if (opts.title) form.append('title', opts.title)
  ;(opts.recipients ?? []).forEach(r => form.append('recipients', r))
  form.append('sendToChat', String(!!opts.sendToChat))
  return client.post<SummaryFileSendResult>('/bot-api/api/meetings/summary-file', form)
    .then(r => r.data)
    .catch(error => {
      const message = error?.response?.data?.error || error?.message || 'Word 发送失败'
      throw new Error(message)
    })
}

export const sendTeamsSummaryToUsers = (
  content: string,
  recipients: string[],
): Promise<TeamsSummarySendResponse> =>
  client.post<TeamsSummarySendResponse>('/bot-api/api/meetings/summary', { content, recipients })
    .then(r => r.data)
    .catch(error => {
      const message = error?.response?.data?.error || error?.message || 'Teams user summary delivery failed'
      throw new Error(message)
    })

export const sendSummaryToMeetingChat = (content: string): Promise<{ sent: boolean; threadId: string }> =>
  client.post<{ sent: boolean; threadId: string }>('/bot-api/api/meetings/summary/chat', { content })
    .then(r => r.data)
    .catch(error => {
      const message = error?.response?.data?.error || error?.message || '发送到会议聊天失败'
      throw new Error(message)
    })

export const joinMeeting = (meetingUrl: string): Promise<{ callId: string; threadId: string; meetingTitle?: string | null }> =>
  client.post<{ callId: string; threadId: string; meetingTitle?: string | null }>('/bot-api/api/meetings/join', { meetingUrl })
    .then(r => r.data)
    .catch(error => {
      const message = error?.response?.data?.error || error?.message || '机器人加入会议失败'
      throw new Error(message)
    })

export const getMeetingParticipants = (): Promise<MeetingParticipantsResponse> =>
  client.get<MeetingParticipantsResponse>('/bot-api/api/meetings/participants')
    .then(r => r.data)
    .catch(error => {
      const message = error?.response?.data?.error || error?.message || '获取参会人员失败'
      throw new Error(message)
    })

// ── Meeting management ───────────────────────────────────────────────────────

export const createMeeting = (params: {
  userId: number
  title: string
  scheduledTime?: string
  note?: string
}): Promise<Result<Meeting>> =>
  client.post<Result<Meeting>>('/api/meetings', params).then(r => {
    // BizException (e.g. duplicate meeting name) returns HTTP 200 with an error code in the body.
    if (r.data?.code !== 200) throw new Error(r.data?.message || '创建会议失败')
    return r.data
  })

export const getMeetings = (userId: number): Promise<Result<Meeting[]>> =>
  client.get<Result<Meeting[]>>('/api/meetings', { params: { userId } }).then(r => r.data)

export const uploadFileToMeeting = (meetingId: number, file: File): Promise<Result<MeetingFile>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<MeetingFile>>(`/api/meetings/${meetingId}/files`, form, {
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export const getMeetingFiles = (meetingId: number): Promise<Result<MeetingFile[]>> =>
  client.get<Result<MeetingFile[]>>(`/api/meetings/${meetingId}/files`).then(r => r.data)

export const deleteMeetingFile = (meetingId: number, fileId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/${meetingId}/files/${fileId}`).then(r => r.data)

// Re-load a previously uploaded meeting file into the in-memory store so it can be re-selected / re-summarized.
export const loadMeetingFileForSummary = (meetingId: number, fileId: number): Promise<Result<PreMeetingSummaryResult>> =>
  client.post<Result<PreMeetingSummaryResult>>(`/api/meetings/${meetingId}/files/${fileId}/load`, {}).then(r => r.data)

export const generateSpeakerSummary = (params: {
  userId: number
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

export const saveMeetingFileSummary = (meetingId: number, fileId: number, summary: string): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/meetings/${meetingId}/files/${fileId}/summary`, { summary }).then(r => r.data)

export const saveMeetingAttendance = (meetingId: number, attendanceJson: string): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/meetings/${meetingId}/attendance`, { attendanceJson }).then(r => r.data)

export const previewMeetingNotification = (
  meetingId: number,
  meetingUrl: string,
  fileId?: string,
): Promise<MeetingNotificationPreview> =>
  client.post<Result<MeetingNotificationPreview>>(`/api/meetings/${meetingId}/notification-preview`, {
    meetingUrl,
    ...(fileId ? { fileId } : {}),
  }).then(r => {
    if (r.data?.code !== 200) throw new Error(r.data?.message || '设置会议链接失败')
    return r.data.data
  })

export const getMeetingNotificationRecipients = (
  meetingId: number,
): Promise<MeetingNotificationRecipient[]> =>
  client.get<Result<MeetingNotificationRecipient[]>>(
    `/api/meetings/${meetingId}/notification-recipients`,
  ).then(r => {
    if (r.data?.code !== 200) throw new Error(r.data?.message || '读取会议通知账号失败')
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
    if (r.data?.code !== 200) throw new Error(r.data?.message || '发送会议通知失败')
    return r.data.data
  })

export const deleteMeeting = (meetingId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/${meetingId}`).then(r => r.data)

export const getActionItems = (sessionId: string): Promise<Result<MeetingActionItem[]>> =>
  client.get<Result<MeetingActionItem[]>>(`/api/meetings/sessions/${encodeURIComponent(sessionId)}/action-items`).then(r => r.data)

export const extractActionItems = (
  sessionId: string,
  meetingId?: number | null,
  userId?: number | null,
): Promise<Result<MeetingActionItem[]>> =>
  client.post<Result<MeetingActionItem[]>>(
    `/api/meetings/sessions/${encodeURIComponent(sessionId)}/action-items/extract`,
    { meetingId, userId },
    { timeout: 60_000 },
  ).then(r => r.data)

export const updateActionItemStatus = (id: number, status: string): Promise<Result<MeetingActionItem>> =>
  client.patch<Result<MeetingActionItem>>(`/api/meetings/action-items/${id}/status`, { status }).then(r => r.data)

export const deleteActionItem = (id: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/meetings/action-items/${id}`).then(r => r.data)

export const getAudioRecords = (
  userId: number,
  keyword?: string,
  page?: number,
  size?: number,
): Promise<Result<{ items: AudioRecord[]; total: number }>> =>
  client.get<Result<{ items: AudioRecord[]; total: number }>>('/api/audio-records', {
    params: { userId, keyword: keyword || '', page: page ?? 1, size: size ?? 50 },
  }).then(r => r.data)

export const renameAudioRecord = (id: number, userId: number, name: string): Promise<Result<void>> =>
  client.put<Result<void>>(`/api/audio-records/${id}/name`, null, { params: { userId, name } }).then(r => r.data)

export const deleteAudioRecord = (id: number, userId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/audio-records/${id}`, { params: { userId } }).then(r => r.data)

export const getAudioDownloadUrl = (id: number, userId: number): string =>
  `/api/audio-records/${id}/download?userId=${userId}`
