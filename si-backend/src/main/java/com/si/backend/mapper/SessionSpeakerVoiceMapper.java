package com.si.backend.mapper;

import com.si.backend.entity.SessionSpeakerVoice;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 会话说话人音色 Mapper，操作 session_speaker_voice 表。
 */
@Mapper
public interface SessionSpeakerVoiceMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS session_speaker_voice (
                id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id        VARCHAR(64) NOT NULL,
                speaker_id        VARCHAR(64) NOT NULL,
                cartesia_voice_id VARCHAR(128),
                clone_status      VARCHAR(32) NOT NULL,
                audio_seconds     INT DEFAULT 0,
                language          VARCHAR(16),
                error_message     VARCHAR(512),
                create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_session_speaker (session_id, speaker_id),
                INDEX idx_session_id (session_id),
                INDEX idx_cartesia_voice_id (cartesia_voice_id)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO session_speaker_voice
            (session_id, speaker_id, cartesia_voice_id, clone_status, audio_seconds, language, error_message, create_time, update_time)
            VALUES
            (#{sessionId}, #{speakerId}, #{cartesiaVoiceId}, #{cloneStatus}, #{audioSeconds}, #{language}, #{errorMessage}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SessionSpeakerVoice speakerVoice);

    @Select("SELECT * FROM session_speaker_voice WHERE session_id = #{sessionId} AND speaker_id = #{speakerId}")
    SessionSpeakerVoice findBySessionAndSpeaker(@Param("sessionId") String sessionId, @Param("speakerId") String speakerId);

    @Select("SELECT * FROM session_speaker_voice WHERE session_id = #{sessionId} ORDER BY speaker_id ASC")
    List<SessionSpeakerVoice> findBySessionId(String sessionId);

    @Update("""
            UPDATE session_speaker_voice
            SET clone_status = #{cloneStatus},
                cartesia_voice_id = #{cartesiaVoiceId},
                audio_seconds = #{audioSeconds},
                language = #{language},
                error_message = #{errorMessage},
                update_time = NOW()
            WHERE session_id = #{sessionId} AND speaker_id = #{speakerId}
            """)
    int updateStatus(SessionSpeakerVoice speakerVoice);
}
