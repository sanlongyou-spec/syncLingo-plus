CREATE TABLE IF NOT EXISTS auth_session (
    sid VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    family_id VARCHAR(64) NOT NULL,
    rotated_from VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    user_agent VARCHAR(255),
    ip VARCHAR(64),
    absolute_expires_at DATETIME NOT NULL,
    idle_expires_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_auth_session_user (user_id),
    INDEX idx_auth_session_family (family_id),
    INDEX idx_auth_session_hash (refresh_token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS meeting_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    access_level VARCHAR(16) NOT NULL,
    assigned_by BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_meeting_member (meeting_id, user_id),
    INDEX idx_meeting_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS support_access_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    grantee_user_id BIGINT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    permissions VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    requested_by BIGINT NOT NULL,
    approved_by BIGINT,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_support_grant_resource (resource_type, resource_id),
    INDEX idx_support_grant_grantee (grantee_user_id),
    INDEX idx_support_grant_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS share_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    session_id VARCHAR(64),
    owner_user_id BIGINT,
    expires_at DATETIME,
    revoked_at DATETIME,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_share_token_hash (token_hash),
    INDEX idx_share_token_owner (owner_user_id),
    INDEX idx_share_token_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_type VARCHAR(16),
    actor_id VARCHAR(64),
    role VARCHAR(16),
    action VARCHAR(48) NOT NULL,
    resource_type VARCHAR(32),
    resource_id VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    ip VARCHAR(64),
    detail VARCHAR(1024),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_action (action),
    INDEX idx_audit_actor (actor_id),
    INDEX idx_audit_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_type VARCHAR(16),
    actor_id VARCHAR(64),
    role VARCHAR(16),
    action VARCHAR(48) NOT NULL,
    resource_type VARCHAR(32),
    resource_id VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    ip VARCHAR(64),
    detail VARCHAR(1024),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_time DATETIME,
    INDEX idx_audit_outbox_status (status, id),
    INDEX idx_audit_outbox_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
