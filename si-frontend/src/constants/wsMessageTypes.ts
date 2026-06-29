/**
 * WebSocket 消息类型常量
 * 与后端 WsMessage.Type 保持一致，禁止硬编码字符串
 */
export const WS_MESSAGE_TYPE = {
  RECOGNIZING: 'recognizing',
  RECOGNIZED: 'recognized',
  TRANSLATED: 'translated',
  STARTED: 'started',
  STOPPED: 'stopped',
  ERROR: 'error',
  AUDIO: 'audio',
  TTS_AUDIO: 'tts_audio',
  TTS_PLAYBACK_LOG: 'tts_playback_log',
  SWITCH_ENGINE: 'switch_engine',
  ENGINE_STATUS: 'engine_status',
  START: 'start',
  STOP: 'stop',
} as const
