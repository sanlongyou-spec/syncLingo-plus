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
} as const

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
  MIN_AUDIO_SECONDS: 0.5,
  RECOMMENDED_AUDIO_SECONDS: '5~10',
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
