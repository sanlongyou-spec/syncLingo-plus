CREATE TABLE IF NOT EXISTS meeting_action_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,
    meeting_id  BIGINT       DEFAULT NULL,
    user_id     BIGINT       DEFAULT NULL,
    assignee    VARCHAR(255) DEFAULT NULL,
    content     TEXT         NOT NULL,
    deadline    VARCHAR(64)  DEFAULT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'pending',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_mai_session (session_id),
    INDEX idx_mai_meeting (meeting_id),
    INDEX idx_mai_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
