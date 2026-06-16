package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 分享能力令牌(P4)。不可枚举、可过期、可撤销。
 * SESSION:绑定单个会话;CHANNEL:绑定操作员,解析为其"当前活动会话"。
 */
@Data
public class ShareToken {
    private Long id;
    private String tokenHash;       // SHA-256(rawToken),原始令牌只在签发时返回一次
    private String kind;            // SESSION / CHANNEL
    private String sessionId;       // SESSION 用
    private Long ownerUserId;       // CHANNEL 用(指向操作员)
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
}
