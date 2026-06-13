package com.si.backend.mapper;

import com.si.backend.dto.CrossMeetingSnippet;
import com.si.backend.entity.InterpretationResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
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

    @Update("ALTER TABLE interpretation_result ADD COLUMN speaker_id VARCHAR(128) DEFAULT NULL")
    void addSpeakerIdColumnIfNotExists();

    @Update("ALTER TABLE interpretation_result ADD COLUMN speaker_name VARCHAR(128) DEFAULT NULL")
    void addSpeakerNameColumnIfNotExists();

    @Insert("INSERT INTO interpretation_result (session_id, source_text, translated_text, source_lang, target_lang, speaker_id, speaker_name, create_time) " +
            "VALUES (#{sessionId}, #{sourceText}, #{translatedText}, #{sourceLang}, #{targetLang}, #{speakerId}, #{speakerName}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationResult result);

    @Select("SELECT * FROM interpretation_result WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<InterpretationResult> findBySessionId(String sessionId);

    @Update("UPDATE interpretation_result SET speaker_name = #{speakerName} WHERE session_id = #{sessionId} AND speaker_id = #{speakerId}")
    int updateSpeakerName(@Param("sessionId") String sessionId, @Param("speakerId") String speakerId, @Param("speakerName") String speakerName);

    @Select("SELECT DISTINCT speaker_id FROM interpretation_result WHERE session_id = #{sessionId} AND speaker_id IS NOT NULL AND speaker_id <> '' ORDER BY speaker_id")
    List<String> findDistinctSpeakerIds(String sessionId);

    @Select("""
            SELECT r.session_id, r.source_text, r.translated_text,
                   s.title AS session_title, s.start_time AS session_start_time
            FROM interpretation_result r
            JOIN interpretation_session s ON r.session_id = s.session_id
            WHERE s.user_id = #{userId}
              AND COALESCE(s.deleted, 0) = 0
              AND (r.source_text    LIKE CONCAT('%', #{keyword}, '%')
                OR r.translated_text LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{since} IS NULL OR #{since} = '' OR s.start_time >= #{since})
            ORDER BY s.start_time DESC, r.id ASC
            LIMIT #{limit}
            """)
    List<CrossMeetingSnippet> searchSnippetsByKeyword(
            @Param("userId") long userId,
            @Param("keyword") String keyword,
            @Param("since") String since,
            @Param("limit") int limit);
}
