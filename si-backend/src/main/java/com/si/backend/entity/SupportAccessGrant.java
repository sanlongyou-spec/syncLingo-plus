package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 管理员临时内容授权(P3)。ADMIN 申请 → owner/另一管理员批准 → 有效期内可读指定会议内容。
 */
@Data
public class SupportAccessGrant {
    private Long id;
    private Long granteeUserId;     // 被授权的管理员
    private String resourceType;    // MEETING
    private String resourceId;      // meetingId
    private String permissions;     // 如 MEETING_CONTENT_READ
    private String reason;
    private Long requestedBy;
    private Long approvedBy;        // null=未批准
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
}
