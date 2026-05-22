package com.si.backend.mapper;

import com.si.backend.entity.SpeakerIdentity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for persistent speaker identities.
 */
@Mapper
public interface SpeakerIdentityMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS speaker_identity (
                id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                person_name        VARCHAR(128) NOT NULL,
                speaker_profile_id VARCHAR(128),
                cartesia_voice_id  VARCHAR(128),
                language           VARCHAR(16),
                note               VARCHAR(512),
                create_time        DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_person_name (person_name),
                UNIQUE KEY uk_speaker_profile_id (speaker_profile_id),
                INDEX idx_cartesia_voice_id (cartesia_voice_id)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO speaker_identity
            (person_name, speaker_profile_id, cartesia_voice_id, language, note, create_time, update_time)
            VALUES
            (#{personName}, #{speakerProfileId}, #{cartesiaVoiceId}, #{language}, #{note}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SpeakerIdentity identity);

    @Update("""
            UPDATE speaker_identity
            SET person_name = #{personName},
                speaker_profile_id = #{speakerProfileId},
                cartesia_voice_id = #{cartesiaVoiceId},
                language = #{language},
                note = #{note},
                update_time = NOW()
            WHERE id = #{id}
            """)
    int update(SpeakerIdentity identity);

    @Update("""
            UPDATE speaker_identity
            SET cartesia_voice_id = #{cartesiaVoiceId},
                language = #{language},
                update_time = NOW()
            WHERE id = #{id}
              AND (cartesia_voice_id IS NULL OR cartesia_voice_id = '')
            """)
    int updateVoiceIfBlank(SpeakerIdentity identity);

    @Select("SELECT * FROM speaker_identity ORDER BY update_time DESC, id DESC")
    List<SpeakerIdentity> findAll();

    @Select("SELECT * FROM speaker_identity WHERE id = #{id}")
    SpeakerIdentity findById(Long id);

    @Select("SELECT * FROM speaker_identity WHERE person_name = #{personName} LIMIT 1")
    SpeakerIdentity findByPersonName(String personName);

    @Select("SELECT * FROM speaker_identity WHERE speaker_profile_id = #{speakerProfileId} LIMIT 1")
    SpeakerIdentity findBySpeakerProfileId(String speakerProfileId);

    @Select("""
            SELECT * FROM speaker_identity
            WHERE speaker_profile_id IS NOT NULL AND speaker_profile_id <> ''
            ORDER BY update_time DESC
            """)
    List<SpeakerIdentity> findAllWithSpeakerProfile();

    @Update("""
            UPDATE speaker_identity
            SET speaker_profile_id = #{speakerProfileId},
                update_time = NOW()
            WHERE id = #{id}
            """)
    int updateProfileId(@Param("id") Long id, @Param("speakerProfileId") String speakerProfileId);

    @Delete("DELETE FROM speaker_identity WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
