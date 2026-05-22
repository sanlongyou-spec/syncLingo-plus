CREATE TABLE IF NOT EXISTS interpretation_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  source_text TEXT NOT NULL,
  translated_text TEXT NOT NULL,
  source_lang VARCHAR(16) DEFAULT NULL,
  target_lang VARCHAR(16) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_result_session_id (session_id),
  CONSTRAINT fk_result_session
    FOREIGN KEY (session_id)
    REFERENCES interpretation_session (session_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
