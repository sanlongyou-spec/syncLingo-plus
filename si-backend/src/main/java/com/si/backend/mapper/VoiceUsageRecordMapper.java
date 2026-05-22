package com.si.backend.mapper;

import com.si.backend.entity.VoiceUsageRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 音色使用记录 Mapper，操作 voice_usage_record 表。
 */
@Mapper
public interface VoiceUsageRecordMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS voice_usage_record (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id  VARCHAR(64),
                user_id     BIGINT,
                voice_id    VARCHAR(128),
                target_lang VARCHAR(16),
                text_len    INT,
                used_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_session_id (session_id),
                INDEX idx_voice_id (voice_id)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO voice_usage_record (session_id, user_id, voice_id, target_lang, text_len, used_at)
            VALUES (#{sessionId}, #{userId}, #{voiceId}, #{targetLang}, #{textLen}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(VoiceUsageRecord record);

    @Select("SELECT * FROM voice_usage_record WHERE session_id = #{sessionId} ORDER BY used_at ASC")
    List<VoiceUsageRecord> findBySessionId(String sessionId);
}
