/**
 * 前端全局常量
 * 禁止在代码中使用魔法值，所有值必须在此文件定义
 */
export const STORAGE_KEYS = {
  USER_ID: 'si_user_id',
  TOKEN: 'si_token',
  CURRENT_SESSION_ID: 'si_current_session_id',
} as const

export const ROUTES = {
  LOGIN: '#/login',
  HOME: '#/',
  VOICE_CLONE: '#/voice-clone',
  HISTORY: '#/history',
  TERMINOLOGY: '#/terminology',
  TEAMS_BOT: '#/teams-bot',
  COST_ANALYSIS: '#/cost-analysis',
} as const

export const TEAMS_BOT_STORAGE_KEYS = {
  RECIPIENTS: 'si_teams_recipients',
  RECIPIENT_HISTORY: 'si_teams_recipient_history',
  MEETING_LINK: 'si_teams_last_meeting_link',
  PRE_MEETING_DEFAULT_REQ: 'si_pre_meeting_default_req',
} as const

export const HTTP_STATUS = {
  OK: 200,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
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
