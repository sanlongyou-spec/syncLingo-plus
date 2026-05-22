package com.si.backend.mapper;

import com.si.backend.entity.UserLanguagePreference;
import org.apache.ibatis.annotations.*;

/**
 * Mapper for user-owned default interpretation language preferences.
 */
@Mapper
public interface UserLanguagePreferenceMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS user_language_preference (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL UNIQUE,
                default_source_lang VARCHAR(16) NOT NULL DEFAULT 'auto',
                enabled_languages VARCHAR(128) NOT NULL DEFAULT 'zh-CN,id-ID',
                create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
            """)
    void createTableIfNotExists();

    @Select("SELECT * FROM user_language_preference WHERE user_id = #{userId}")
    UserLanguagePreference findByUserId(Long userId);

    @Insert("""
            INSERT INTO user_language_preference (
                user_id, default_source_lang, enabled_languages, create_time, update_time
            )
            VALUES (
                #{userId}, #{defaultSourceLang}, #{enabledLanguages}, NOW(), NOW()
            )
            ON DUPLICATE KEY UPDATE
                default_source_lang = VALUES(default_source_lang),
                enabled_languages = VALUES(enabled_languages),
                update_time = NOW()
            """)
    int upsert(UserLanguagePreference preference);
}
