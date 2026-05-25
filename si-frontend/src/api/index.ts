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
  TeamsSummarySendResponse,
  MeetingParticipant,
  MeetingParticipantsResponse,
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

export const startInterpretation = (params: StartInterpretationParams): Promise<Result<string>> =>
  client.post<Result<string>>('/api/interpretation/start', params).then(r => r.data)

export const stopInterpretation = (sessionId: string): Promise<Result<void>> =>
  client.post<Result<void>>('/api/interpretation/stop', { sessionId }).then(r => r.data)

export const getInterpretationStatus = (sessionId: string): Promise<Result<InterpretationStatus>> =>
  client.get<Result<InterpretationStatus>>(`/api/interpretation/status/${sessionId}`).then(r => r.data)

export const saveInterpretationResult = (params: SaveInterpretationResultParams): Promise<Result<InterpretationResultItem>> =>
  client.post<Result<InterpretationResultItem>>('/api/interpretation/results', params).then(r => r.data)

export const getPublicInterpretationResults = (sessionId: string): Promise<Result<InterpretationResultItem[]>> =>
  client.get<Result<InterpretationResultItem[]>>(`/api/interpretation/public/${sessionId}/results`).then(r => r.data)

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

export const uploadPreMeetingFile = (file: File): Promise<Result<PreMeetingFile[]>> => {
  const form = new FormData()
  form.append('file', file)
  return client.post<Result<PreMeetingFile[]>>('/api/pre-meeting/upload', form, {
    headers: { 'Content-Type': undefined },
  }).then(r => r.data)
}

export const summarizePreMeetingFile = (
  fileId: string,
  requirements: string,
  userId?: number,
): Promise<Result<PreMeetingSummaryResult>> =>
  client.post<Result<PreMeetingSummaryResult>>('/api/pre-meeting/summarize', { fileId, requirements, userId }, {
    timeout: 300_000,
  }).then(r => r.data)

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
