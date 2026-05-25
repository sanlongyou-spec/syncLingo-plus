package com.si.backend.mapper;

import com.si.backend.entity.AsrHotword;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for user-owned ASR hotwords.
 */
@Mapper
public interface AsrHotwordMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS asr_hotword (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                phrase VARCHAR(255) NOT NULL,
                language VARCHAR(16),
                category VARCHAR(64),
                weight DOUBLE DEFAULT 1.0,
                source_type VARCHAR(32) DEFAULT 'MANUAL',
                source_terminology_id BIGINT,
                enabled TINYINT DEFAULT 1,
                expires_at DATETIME DEFAULT NULL,
                last_used_time DATETIME DEFAULT NULL,
                create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_user_enabled (user_id, enabled),
                INDEX idx_expires_at (expires_at)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO asr_hotword (
                user_id, phrase, language, category, weight, source_type, source_terminology_id,
                enabled, expires_at, create_time, update_time
            )
            VALUES (
                #{userId}, #{phrase}, #{language}, #{category}, #{weight}, #{sourceType}, #{sourceTerminologyId},
                #{enabled}, #{expiresAt}, NOW(), NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AsrHotword hotword);

    @Select("""
            SELECT * FROM asr_hotword
            WHERE user_id = #{userId}
            AND (#{keyword} IS NULL OR #{keyword} = '' OR phrase LIKE CONCAT('%', #{keyword}, '%') OR category LIKE CONCAT('%', #{keyword}, '%'))
            AND (#{enabled} IS NULL OR enabled = #{enabled})
            AND (#{language} IS NULL OR #{language} = '' OR language = #{language})
            AND (#{category} IS NULL OR #{category} = '' OR category = #{category})
            ORDER BY id DESC
            """)
    List<AsrHotword> findAll(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("enabled") Boolean enabled,
            @Param("language") String language,
            @Param("category") String category
    );

    @Select("""
            SELECT * FROM asr_hotword
            WHERE user_id = #{userId}
            AND enabled = 1
            AND (expires_at IS NULL OR expires_at > NOW())
            AND (language IS NULL OR language = '' OR language = #{language})
            ORDER BY weight DESC, id ASC
            """)
    List<AsrHotword> findActiveByUserId(@Param("userId") Long userId, @Param("language") String language);

    @Update("""
            UPDATE asr_hotword
            SET phrase = #{phrase},
                language = #{language},
                category = #{category},
                weight = #{weight},
                enabled = #{enabled},
                expires_at = #{expiresAt},
                update_time = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int update(AsrHotword hotword);

    @Update("UPDATE asr_hotword SET enabled = #{enabled}, update_time = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateEnabled(@Param("id") Long id, @Param("userId") Long userId, @Param("enabled") Boolean enabled);

    @Delete("DELETE FROM asr_hotword WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            <script>
            UPDATE asr_hotword
            SET last_used_time = NOW(), update_time = NOW()
            WHERE user_id = #{userId}
            AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    int markUsed(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    @Select("SELECT COUNT(*) FROM asr_hotword WHERE user_id = #{userId} AND phrase = #{phrase} AND (language IS NULL OR language = '' OR language = #{language})")
    int countByUserIdPhraseAndLanguage(@Param("userId") Long userId, @Param("phrase") String phrase, @Param("language") String language);

    @Update("ALTER TABLE asr_hotword ADD COLUMN last_used_time DATETIME DEFAULT NULL AFTER expires_at")
    void addLastUsedTimeColumnIfNotExists();
}
