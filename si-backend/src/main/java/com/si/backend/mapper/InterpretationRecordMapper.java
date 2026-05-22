package com.si.backend.mapper;

import com.si.backend.entity.InterpretationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 同传对话记录 Mapper，操作 interpretation_record 表。
 */
@Mapper
public interface InterpretationRecordMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS interpretation_record (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id   VARCHAR(64) NOT NULL,
                seq          INT NOT NULL,
                source_lang  VARCHAR(16),
                target_lang  VARCHAR(16),
                source_text  TEXT,
                target_text  TEXT,
                spoken_at    DATETIME(3),
                create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_session_seq (session_id, seq),
                INDEX idx_session_id (session_id)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO interpretation_record
            (session_id, seq, source_lang, target_lang, source_text, target_text, spoken_at, create_time)
            VALUES
            (#{sessionId}, #{seq}, #{sourceLang}, #{targetLang}, #{sourceText}, #{targetText}, #{spokenAt}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationRecord record);

    @Select("SELECT * FROM interpretation_record WHERE session_id = #{sessionId} ORDER BY seq ASC")
    List<InterpretationRecord> findBySessionId(String sessionId);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM interpretation_record WHERE session_id = #{sessionId}")
    int findMaxSeqBySessionId(String sessionId);
}
