package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 安全审计日志(P2,仅追加)。记录权限变更、账号管理、登录、跨用户/拒绝等敏感事件。
 * detail_json 须脱敏:不存密码、token、密钥、完整正文。
 */
@Data
public class AuditLog {
    private Long id;
    private String actorType;   // USER / SERVICE / SYSTEM / ANONYMOUS
    private String actorId;     // userId 或用户名
    private String role;
    private String action;      // USER_CREATE / ROLE_CHANGE / STATUS_CHANGE / PASSWORD_RESET / LOGIN / LOGIN_FAIL / AUTHZ_DENY ...
    private String resourceType;
    private String resourceId;
    private String result;      // ALLOW / DENY / SUCCESS / FAIL
    private String ip;
    private String detail;      // 脱敏说明
    private LocalDateTime createTime;
}
