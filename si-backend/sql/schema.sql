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
    status      VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    start_time  DATETIME              DEFAULT NULL,
    end_time    DATETIME              DEFAULT NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES si_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Default admin user (password: admin123, BCrypt hashed)
INSERT INTO si_user (username, password, nickname, role)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '管理员', 'admin')
ON DUPLICATE KEY UPDATE username = username;
