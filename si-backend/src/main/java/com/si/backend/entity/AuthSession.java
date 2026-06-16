package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Refresh-token session row for browser sign-in.
 */
@Data
public class AuthSession {
    private Long id;
    private Long userId;
    private String sessionId;
    private String familyId;
    private String refreshTokenHash;
    private String rotatedFromHash;
    private String status;
    private LocalDateTime absoluteExpiresAt;
    private LocalDateTime idleExpiresAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime rotatedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
}
