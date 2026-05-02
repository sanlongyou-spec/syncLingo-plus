import client from './client'
import type {
  Result,
  StartInterpretationParams,
  InterpretationStatus,
  CloneVoiceParams,
  CloneVoiceResponse,
  UserVoice,
} from '../types'

export const startInterpretation = (params: StartInterpretationParams): Promise<Result<string>> =>
  client.post<Result<string>>('/api/interpretation/start', params).then(r => r.data)

export const stopInterpretation = (sessionId: string): Promise<Result<void>> =>
  client.post<Result<void>>('/api/interpretation/stop', { sessionId }).then(r => r.data)

export const getInterpretationStatus = (sessionId: string): Promise<Result<InterpretationStatus>> =>
  client.get<Result<InterpretationStatus>>(`/api/interpretation/status/${sessionId}`).then(r => r.data)

export const translateText = (text: string, sourceLang: string, targetLang: string): Promise<Result<string>> =>
  client.post<Result<string>>('/api/translate', { text, sourceLang, targetLang }).then(r => r.data)

export const cloneVoice = (params: CloneVoiceParams): Promise<Result<CloneVoiceResponse>> =>
  client.post<Result<CloneVoiceResponse>>('/api/voice/clone', params).then(r => r.data)

export const getUserVoice = (userId: number): Promise<Result<UserVoice | null>> =>
  client.get<Result<UserVoice | null>>(`/api/voice/${userId}`).then(r => r.data)

export const deleteUserVoice = (userId: number): Promise<Result<void>> =>
  client.delete<Result<void>>(`/api/voice/${userId}`).then(r => r.data)

export const login = (username: string, password: string): Promise<Result<{ userId: number; token: string }>> =>
  client.post<Result<{ userId: number; token: string }>>('/api/auth/login', { username, password }).then(r => r.data)
