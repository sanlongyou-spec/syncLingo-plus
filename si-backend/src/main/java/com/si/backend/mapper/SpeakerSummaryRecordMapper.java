package com.si.backend.mapper;

import com.si.backend.entity.SpeakerSummaryRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SpeakerSummaryRecordMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS speaker_summary (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id   VARCHAR(64)  NOT NULL,
                speaker_id   VARCHAR(255) DEFAULT NULL,
                speaker_name VARCHAR(255) DEFAULT NULL,
                title        VARCHAR(512) DEFAULT NULL,
                text_snippet MEDIUMTEXT   DEFAULT NULL,
                summary      MEDIUMTEXT   DEFAULT NULL,
                create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_ss_session_id (session_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Update("ALTER TABLE speaker_summary ADD COLUMN title VARCHAR(512) DEFAULT NULL AFTER speaker_name")
    void addTitleColumnIfNotExists();

    @Insert("INSERT INTO speaker_summary (session_id, speaker_id, speaker_name, title, text_snippet, summary, create_time) " +
            "VALUES (#{sessionId}, #{speakerId}, #{speakerName}, #{title}, #{textSnippet}, #{summary}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SpeakerSummaryRecord record);

    @Update("UPDATE speaker_summary SET title = #{title}, summary = #{summary} WHERE id = #{id}")
    int updateSummary(SpeakerSummaryRecord record);

    @Update("UPDATE speaker_summary SET title = #{title}, summary = #{summary}, text_snippet = #{textSnippet}, speaker_id = #{speakerId} WHERE id = #{id}")
    int updateSummaryAndSnippet(SpeakerSummaryRecord record);

    /** Latest summary record for a given speaker within a session — one summary per person. */
    @Select("SELECT * FROM speaker_summary WHERE session_id = #{sessionId} AND speaker_name = #{speakerName} ORDER BY id DESC LIMIT 1")
    SpeakerSummaryRecord findBySessionIdAndSpeakerName(@Param("sessionId") String sessionId, @Param("speakerName") String speakerName);

    @Select("SELECT * FROM speaker_summary WHERE id = #{id}")
    SpeakerSummaryRecord findById(Long id);

    @Select("SELECT * FROM speaker_summary WHERE session_id = #{sessionId} ORDER BY create_time ASC")
    List<SpeakerSummaryRecord> findBySessionId(String sessionId);
}
