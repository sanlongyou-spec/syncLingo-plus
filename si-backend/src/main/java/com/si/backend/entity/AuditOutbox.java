package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Transactional audit outbox entry. The final audit log remains append-only; this table tracks delivery state.
 */
@Data
public class AuditOutbox {
    private Long id;
    private String actorType;
    private String actorId;
    private String role;
    private String action;
    private String resourceType;
    private String resourceId;
    private String result;
    private String ip;
    private String detail;
    private String status;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime sentTime;
}
