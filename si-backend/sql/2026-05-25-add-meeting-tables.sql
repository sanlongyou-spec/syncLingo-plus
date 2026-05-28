-- Migration: 2026-05-25
-- Adds standalone meeting entity, persistent pre-meeting file storage,
-- and links interpretation_session to a meeting.

CREATE TABLE IF NOT EXISTS meeting (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    title          VARCHAR(256) NOT NULL,
    scheduled_time DATETIME     DEFAULT NULL,
    note           TEXT         DEFAULT NULL,
    deleted        TINYINT(1)   NOT NULL DEFAULT 0,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_meeting_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_meeting_file_persistent (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id   BIGINT        NOT NULL,
    file_name    VARCHAR(512)  NOT NULL,
    file_type    VARCHAR(32)   DEFAULT NULL,
    file_content MEDIUMTEXT    DEFAULT NULL,
    summary      MEDIUMTEXT    DEFAULT NULL,
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pmfp_meeting_id (meeting_id),
    CONSTRAINT fk_pmfp_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- meeting_id column added to interpretation_session at application startup
-- via InterpretationSessionService.initColumns() / addMeetingIdColumnIfNotExists()
