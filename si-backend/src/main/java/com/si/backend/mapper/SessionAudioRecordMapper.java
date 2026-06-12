package com.si.backend.mapper;

import com.si.backend.entity.SessionAudioRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SessionAudioRecordMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS session_audio_record (
                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id      VARCHAR(100) NOT NULL,
                user_id         BIGINT       NOT NULL,
                meeting_id      BIGINT       DEFAULT NULL,
                name            VARCHAR(200) DEFAULT NULL,
                file_path       VARCHAR(500) NOT NULL,
                file_size_bytes BIGINT       DEFAULT 0,
                duration_ms     BIGINT       DEFAULT 0,
                sample_rate     INT          DEFAULT 16000,
                create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_sar_user_id   (user_id),
                INDEX idx_sar_session   (session_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO session_audio_record
              (session_id, user_id, meeting_id, name, file_path, file_size_bytes, duration_ms, sample_rate, create_time)
            VALUES
              (#{sessionId}, #{userId}, #{meetingId}, #{name}, #{filePath}, #{fileSizeBytes}, #{durationMs}, #{sampleRate}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SessionAudioRecord record);

    @Update("UPDATE session_audio_record SET name = #{name} WHERE id = #{id} AND user_id = #{userId}")
    int updateName(@Param("id") Long id, @Param("userId") Long userId, @Param("name") String name);

    @Delete("DELETE FROM session_audio_record WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM session_audio_record WHERE id = #{id}")
    SessionAudioRecord findById(Long id);

    @Select("""
            SELECT * FROM session_audio_record
            WHERE user_id = #{userId}
              AND (#{keyword} IS NULL OR #{keyword} = '' OR name LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY create_time DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<SessionAudioRecord> search(@Param("userId") Long userId,
                                    @Param("keyword") String keyword,
                                    @Param("size") int size,
                                    @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*) FROM session_audio_record
            WHERE user_id = #{userId}
              AND (#{keyword} IS NULL OR #{keyword} = '' OR name LIKE CONCAT('%', #{keyword}, '%'))
            """)
    long count(@Param("userId") Long userId, @Param("keyword") String keyword);
}
