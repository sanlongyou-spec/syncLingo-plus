import client from './client'
import type {
  Result,
  StartInterpretationParams,
  InterpretationStatus,
  InterpretationResultItem,
  SaveInterpretationResultParams,
  CloneVoiceParams,
  CloneVoiceResponse,
  UserVoice,
  SpeakerIdentity,
  SessionSpeakerIdentity,
  Terminology,
  AsrHotword,
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
  SpeakerSummaryResult,
  SpeakerSummaryRecord,
  MeetingActionItem,
  CostRates,
  MonthlyCostSummary,
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

export const getActiveSessionForUser = (userId: number): Promise<Result<string | null>> =>
  client.get<Result<string | null>>(`/api/interpretation/public/user/${userId}/active`).then(r => r.data)

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
): Promise<Result<PreMeetingSummaryResult>> =>
  client.post<Result<PreMeetingSummaryResult>>('/api/pre-meeting/summarize', { fileId, requirements, userId }, {
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

export const exportPreMeetingAttendanceDocx = (
  fileId: string,
  actualParticipants: MeetingParticipant[],
): Promise<Blob> =>
  client.post<Blob>('/api/pre-meeting/attendance/export', {
    fileId,
    actualParticipants,
  }, { responseType: 'blob', timeout: 60_000 })
    .then(r => r.data)
    .catch(async error => {
      throw new Error(await getApiErrorMessage(error, '导出实际参会名单失败'))
    })

export const getPreMeetingUsage = (userId: number, days = 365): Promise<Result<PreMeetingDailyUsage[]>> =>
  client.get<Result<PreMeetingDailyUsage[]>>('/api/pre-meeting/usage', { params: { userId, days } }).then(r => r.data)

export const exportPreMeetingDocx = (fileId: string, summary: string): Promise<Blob> =>
  client.post<Blob>(`/api/pre-meeting/export/${fileId}`, { summary }, { responseType: 'blob', timeout: 60_000 }).then(r => r.data)

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
  client.post<Result<Meeting>>('/api/meetings', params).then(r => r.data)

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
