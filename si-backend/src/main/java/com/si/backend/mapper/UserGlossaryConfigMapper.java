package com.si.backend.mapper;

import com.si.backend.entity.UserGlossaryConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for user-owned glossary configuration.
 */
@Mapper
public interface UserGlossaryConfigMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS user_glossary_config (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                source_lang VARCHAR(16) NOT NULL,
                target_lang VARCHAR(16) NOT NULL,
                glossary_id VARCHAR(255) NOT NULL,
                enabled TINYINT DEFAULT 1,
                create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_user_direction (user_id, source_lang, target_lang)
            )
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT * FROM user_glossary_config
            WHERE user_id = #{userId}
            ORDER BY source_lang ASC, target_lang ASC
            """)
    List<UserGlossaryConfig> findByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM user_glossary_config
            WHERE user_id = #{userId}
              AND source_lang = #{sourceLang}
              AND target_lang = #{targetLang}
              AND enabled = 1
            LIMIT 1
            """)
    UserGlossaryConfig findEnabledDirection(
            @Param("userId") Long userId,
            @Param("sourceLang") String sourceLang,
            @Param("targetLang") String targetLang
    );

    @Insert("""
            INSERT INTO user_glossary_config (
                user_id, source_lang, target_lang, glossary_id, enabled, create_time, update_time
            ) VALUES (
                #{userId}, #{sourceLang}, #{targetLang}, #{glossaryId}, #{enabled}, NOW(), NOW()
            )
            ON DUPLICATE KEY UPDATE
                glossary_id = VALUES(glossary_id),
                enabled = VALUES(enabled),
                update_time = NOW()
            """)
    int upsert(UserGlossaryConfig config);

    @Delete("DELETE FROM user_glossary_config WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);
}
