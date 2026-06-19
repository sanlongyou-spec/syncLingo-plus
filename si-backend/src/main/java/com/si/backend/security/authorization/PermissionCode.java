package com.si.backend.security.authorization;

/**
 * Stable capability names used by the P0 entry-point inventory and future authorization enforcement.
 */
public enum PermissionCode {
    AUTH_LOGIN,
    REGISTRATION_CLOSED,
    HEALTH_READ,
    API_DOCS_READ,
    USER_PREFERENCE_MANAGE,
    COST_RATES_READ,
    COST_READ_SELF,
    BOT_OPERATE,
    TEAMS_SEND,
    BOT_MESSAGES,
    TEAMS_BOT_QUERY,
    LANGUAGE_PREFERENCE_MANAGE,
    TRANSLATE_USE,
    TERMINOLOGY_MANAGE,
    AUDIO_MANAGE,
    DIRECTORY_ACCESS,
    PRE_MEETING_MANAGE,
    HOTWORD_MANAGE,
    VOICE_MANAGE,
    SUMMARY_MANAGE,
    OPS_EXECUTE,
    MEETING_CONTENT_MANAGE,
    INTERPRETATION_OPERATE,
    SHARE_READ,
    MEETING_MANAGE,
    INTERNAL_ASYNC,
    /** 任意已认证用户读取本人账号资料(P1);密码统一由 ADMIN 在用户管理中设置。 */
    ACCOUNT_SELF,
    /** 用户管理(列表/建号/改角色/启停/重置密码),仅 ADMIN(P1)。 */
    USER_MANAGE,
    /** 查看安全审计日志,仅 ADMIN(P2)。 */
    AUDIT_READ
}
