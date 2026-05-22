package com.si.backend.mapper;

import com.si.backend.entity.InterpretationResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InterpretationResultMapper {

    @Update("""
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTableIfNotExists();

    @Insert("INSERT INTO interpretation_result (session_id, source_text, translated_text, source_lang, target_lang, create_time) " +
            "VALUES (#{sessionId}, #{sourceText}, #{translatedText}, #{sourceLang}, #{targetLang}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationResult result);

    @Select("SELECT * FROM interpretation_result WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<InterpretationResult> findBySessionId(String sessionId);
}
