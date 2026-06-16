ALTER TABLE interpretation_embedding
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(128) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS embedding_dim INT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS embedding_profile VARCHAR(64) NOT NULL DEFAULT 'default',
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS index_status VARCHAR(24) NOT NULL DEFAULT 'READY',
    ADD COLUMN IF NOT EXISTS last_embedded_at DATETIME DEFAULT NULL;

SELECT COUNT(*) INTO @idx_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'interpretation_embedding'
  AND index_name = 'idx_emb_profile';
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE interpretation_embedding ADD INDEX idx_emb_profile (embedding_profile, embedding_model, embedding_dim, index_status)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT COUNT(*) INTO @idx_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'interpretation_embedding'
  AND index_name = 'uk_emb_source';
SET @sql = IF(@idx_exists > 0,
              'ALTER TABLE interpretation_embedding DROP INDEX uk_emb_source',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT COUNT(*) INTO @idx_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'interpretation_embedding'
  AND index_name = 'uk_emb_result';
SET @sql = IF(@idx_exists > 0,
              'ALTER TABLE interpretation_embedding DROP INDEX uk_emb_result',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT COUNT(*) INTO @idx_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'interpretation_embedding'
  AND index_name = 'uk_emb_source_profile';
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE interpretation_embedding ADD UNIQUE KEY uk_emb_source_profile (source_type, source_id, embedding_profile)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT COUNT(*) INTO @idx_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'interpretation_embedding'
  AND index_name = 'uk_emb_result_profile';
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE interpretation_embedding ADD UNIQUE KEY uk_emb_result_profile (result_id, embedding_profile)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
