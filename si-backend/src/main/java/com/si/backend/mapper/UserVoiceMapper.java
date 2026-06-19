package com.si.backend.mapper;

import com.si.backend.entity.UserVoice;
import org.apache.ibatis.annotations.*;

/**
 * 用户音色 Mapper，操作 user_voice 表。
 */
@Mapper
public interface UserVoiceMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS user_voice (
                id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id          BIGINT       NOT NULL,
                voice_id         VARCHAR(128) NOT NULL,
                voice_name       VARCHAR(128) NOT NULL,
                duration_seconds INT          DEFAULT NULL,
                sample_url       VARCHAR(512) DEFAULT NULL,
                authorized       TINYINT      DEFAULT 1,
                scope            VARCHAR(64)  DEFAULT 'SELF',
                disabled         TINYINT      DEFAULT 0,
                create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_user_id (user_id),
                INDEX idx_voice_id (voice_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Update("ALTER TABLE user_voice ADD COLUMN authorized TINYINT DEFAULT 1")
    void addAuthorizedColumnIfNotExists();

    @Update("ALTER TABLE user_voice ADD COLUMN scope VARCHAR(64) DEFAULT 'ALL'")
    void addScopeColumnIfNotExists();

    @Update("ALTER TABLE user_voice ADD COLUMN disabled TINYINT DEFAULT 0")
    void addDisabledColumnIfNotExists();

    @Insert("INSERT INTO user_voice (user_id, voice_id, voice_name, duration_seconds, sample_url, authorized, scope, disabled, create_time, update_time) " +
            "VALUES (#{userId}, #{voiceId}, #{voiceName}, #{durationSeconds}, #{sampleUrl}, #{authorized}, #{scope}, #{disabled}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserVoice userVoice);

    @Select("SELECT * FROM user_voice WHERE user_id = #{userId} AND COALESCE(disabled, 0) = 0 ORDER BY create_time DESC")
    java.util.List<UserVoice> findEnabledByUserId(Long userId);

    @Select("SELECT * FROM user_voice WHERE user_id = #{userId} ORDER BY create_time DESC")
    java.util.List<UserVoice> findAllByUserId(Long userId);

    @Select("SELECT * FROM user_voice WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT 1")
    UserVoice findByUserId(Long userId);

    @Select("SELECT * FROM user_voice WHERE user_id = #{userId} AND voice_id = #{voiceId}")
    UserVoice findByUserIdAndVoiceId(@Param("userId") Long userId, @Param("voiceId") String voiceId);

    @Select("SELECT * FROM user_voice WHERE voice_id = #{voiceId}")
    UserVoice findByVoiceId(String voiceId);

    @Update("UPDATE user_voice SET voice_id = #{voiceId}, voice_name = #{voiceName}, update_time = NOW() WHERE user_id = #{userId}")
    int updateByUserId(UserVoice userVoice);

    @Update("UPDATE user_voice SET authorized = #{authorized}, disabled = #{disabled}, scope = #{scope}, update_time = NOW() WHERE voice_id = #{voiceId}")
    int updateAuthorization(UserVoice userVoice);

    @Delete("DELETE FROM user_voice WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    @Delete("DELETE FROM user_voice WHERE user_id = #{userId} AND voice_id = #{voiceId}")
    int deleteByUserIdAndVoiceId(@Param("userId") Long userId, @Param("voiceId") String voiceId);
}
