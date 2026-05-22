ALTER TABLE interpretation_session
  ADD COLUMN title VARCHAR(128) DEFAULT '未命名同传' AFTER voice_id,
  ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 AFTER status;

UPDATE interpretation_session
SET title = '未命名同传'
WHERE title IS NULL OR title = '';
