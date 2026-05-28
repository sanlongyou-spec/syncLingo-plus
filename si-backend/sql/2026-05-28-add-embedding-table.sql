CREATE TABLE IF NOT EXISTS interpretation_embedding (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    result_id      BIGINT       NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    meeting_id     BIGINT       DEFAULT NULL,
    session_title  VARCHAR(255) DEFAULT NULL,
    session_date   DATE         DEFAULT NULL,
    speaker_name   VARCHAR(128) DEFAULT NULL,
    chunk_text     TEXT         NOT NULL,
    translated_text TEXT        DEFAULT NULL,
    embedding      MEDIUMBLOB   NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_emb_result (result_id),
    INDEX idx_emb_session (session_id),
    INDEX idx_emb_meeting (meeting_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
