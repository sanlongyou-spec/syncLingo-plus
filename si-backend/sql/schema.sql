-- Database: si_backend
-- Create database
CREATE DATABASE IF NOT EXISTS si_backend
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE si_backend;

-- Table: si_user
CREATE TABLE IF NOT EXISTS si_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    nickname     VARCHAR(128)          DEFAULT NULL,
    email        VARCHAR(255)          DEFAULT NULL,
    role         VARCHAR(32)           DEFAULT 'user',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table: user_voice
CREATE TABLE IF NOT EXISTS user_voice (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT      NOT NULL,
    voice_id         VARCHAR(128) NOT NULL,
    voice_name       VARCHAR(128) NOT NULL,
    duration_seconds INT                  DEFAULT NULL,
    sample_url       VARCHAR(512)         DEFAULT NULL,
    create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_voice_id (voice_id),
    CONSTRAINT fk_voice_user FOREIGN KEY (user_id) REFERENCES si_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table: interpretation_session
CREATE TABLE IF NOT EXISTS interpretation_session (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL,
    source_lang VARCHAR(16)  NOT NULL,
    target_lang VARCHAR(16)  NOT NULL,
    voice_id    VARCHAR(128)          DEFAULT NULL,
    hotword_ids VARCHAR(2048)         DEFAULT NULL,
    enabled_languages VARCHAR(128)    DEFAULT NULL,
    title       VARCHAR(128)          DEFAULT '未命名同传',
    status      VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    deleted     TINYINT(1)   NOT NULL DEFAULT 0,
    start_time  DATETIME              DEFAULT NULL,
    end_time        DATETIME              DEFAULT NULL,
    meeting_summary TEXT                  DEFAULT NULL,
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES si_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table: interpretation_result
CREATE TABLE IF NOT EXISTS interpretation_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    source_text     TEXT        NOT NULL,
    translated_text TEXT        NOT NULL,
    source_lang     VARCHAR(16)          DEFAULT NULL,
    target_lang     VARCHAR(16)          DEFAULT NULL,
    create_time     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_result_session_id (session_id),
    CONSTRAINT fk_result_session FOREIGN KEY (session_id) REFERENCES interpretation_session (session_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table: terminology
CREATE TABLE IF NOT EXISTS terminology (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT DEFAULT 1,
    term_zh       VARCHAR(255),
    term_id       VARCHAR(255),
    term_en       VARCHAR(255),
    pinyin        VARCHAR(255),
    category      VARCHAR(64),
    note          VARCHAR(512),
    source_sheet  VARCHAR(128),
    source_row    INT,
    review_status VARCHAR(32) DEFAULT 'APPROVED',
    enabled       TINYINT DEFAULT 1,
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS asr_hotword (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT NOT NULL,
    phrase                VARCHAR(255) NOT NULL,
    language              VARCHAR(16),
    category              VARCHAR(64),
    weight                DOUBLE DEFAULT 1.0,
    source_type           VARCHAR(32) DEFAULT 'MANUAL',
    source_terminology_id BIGINT,
    enabled               TINYINT DEFAULT 1,
    expires_at            DATETIME DEFAULT NULL,
    last_used_time        DATETIME DEFAULT NULL,
    create_time           DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_enabled (user_id, enabled),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_glossary_config (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    source_lang   VARCHAR(16) NOT NULL,
    target_lang   VARCHAR(16) NOT NULL,
    glossary_id   VARCHAR(255) NOT NULL,
    enabled       TINYINT DEFAULT 1,
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_direction (user_id, source_lang, target_lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_language_preference (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL UNIQUE,
    default_source_lang VARCHAR(16) NOT NULL DEFAULT 'auto',
    enabled_languages   VARCHAR(128) NOT NULL DEFAULT 'zh-CN,id-ID',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Default admin user (password: admin123, BCrypt hashed)
INSERT INTO si_user (username, password, nickname, role)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '管理员', 'admin')
ON DUPLICATE KEY UPDATE username = username;
