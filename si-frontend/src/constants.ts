/**
 * 前端全局常量
 * 禁止在代码中使用魔法值，所有值必须在此文件定义
 */
export const STORAGE_KEYS = {
  USER_ID: 'si_user_id',
  TOKEN: 'si_token',
  ROLE: 'si_role',
  CURRENT_SESSION_ID: 'si_current_session_id',
  MEETING_SUMMARY_INCLUDE_CHAT: 'si_meeting_summary_include_chat',
  SPEAKER_SUMMARY_REQUIREMENTS: 'si_speaker_summary_requirements',
  MEETING_SUMMARY_REQUIREMENTS: 'si_meeting_summary_requirements',
  TRANSCRIPT_FONT_SCALE: 'si_transcript_font_scale',
} as const

/**
 * 同传文本（ASR 原文 + 译文）字号档位。
 * 使用界面与共享页共用同一组档位与本地存储，确保两边字号一致。
 */
export const TRANSCRIPT_FONT_SCALES = [
  { label: '小', value: 0.85 },
  { label: '标准', value: 1 },
  { label: '大', value: 1.2 },
  { label: '特大', value: 1.45 },
] as const

export const DEFAULT_TRANSCRIPT_FONT_SCALE = 1

export const ROUTES = {
  LOGIN: '#/login',
  HOME: '#/',
  HISTORY: '#/history',
  VOICES: '#/voices',
  TERMINOLOGY: '#/terminology',
  MEETINGS: '#/meetings',
  COST_ANALYSIS: '#/cost-analysis',
  ADMIN_CONSOLE: '#/admin',
  USER_MANAGEMENT: '#/user-management',
  SECURITY_OPERATIONS: '#/security-operations',
} as const

export const MEETINGS_STORAGE_KEYS = {
  LAST_SELECTED_MEETING_ID: 'si_meetings_last_selected_meeting_id',
} as const

export const HTTP_STATUS = {
  OK: 200,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  CAPTCHA_REQUIRED: 428,
  NOT_FOUND: 404,
  SERVER_ERROR: 500,
} as const

export const VOICE_CLONE = {
  MAX_NAME_LENGTH: 50,
  MIN_AUDIO_SECONDS: 20,
  RECOMMENDED_AUDIO_SECONDS: '45~60',
  SUPPORTED_FORMATS: 'audio/*',
} as const

export const LANGUAGE = {
  AUTO: 'auto',
  ZH_CN: 'zh-CN',
  ID_ID: 'id-ID',
  EN_US: 'en-US',
  ZH: 'zh',
  ID: 'id',
  EN: 'en',
} as const

export const LANGUAGE_LABELS: Record<string, string> = {
  [LANGUAGE.AUTO]: '自动检测',
  [LANGUAGE.ZH_CN]: '中文（简体）',
  [LANGUAGE.ID_ID]: '印尼语',
  [LANGUAGE.EN_US]: '英语',
}
