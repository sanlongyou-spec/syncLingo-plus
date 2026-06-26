package com.si.backend.mapper;

import com.si.backend.entity.AsrCorrection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * ASR 错词库 Mapper，操作 asr_correction 表。
 */
@Mapper
public interface AsrCorrectionMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS asr_correction (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id     BIGINT DEFAULT 1,
                variant     VARCHAR(255) NOT NULL,
                canonical   VARCHAR(255) NOT NULL,
                src_lang    VARCHAR(16),
                scope       VARCHAR(16) DEFAULT 'GLOBAL',
                confidence  DOUBLE DEFAULT 0,
                hit_count   INT DEFAULT 1,
                status      VARCHAR(16) DEFAULT 'OBSERVING',
                source      VARCHAR(32) DEFAULT 'DOC_MINING',
                create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_user_variant_canonical (user_id, variant, canonical),
                INDEX idx_user_status (user_id, status)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO asr_correction (
                user_id, variant, canonical, src_lang, scope, confidence, hit_count, status, source, create_time, update_time
            ) VALUES (
                #{userId}, #{variant}, #{canonical}, #{srcLang}, #{scope}, #{confidence}, #{hitCount}, #{status}, #{source}, NOW(), NOW()
            )
            ON DUPLICATE KEY UPDATE
                hit_count = hit_count + 1,
                confidence = GREATEST(confidence, VALUES(confidence)),
                status = CASE
                    WHEN status = 'ACTIVE' THEN 'ACTIVE'
                    WHEN (hit_count + 1) >= #{promoteHitCount} OR VALUES(confidence) >= #{promoteConfidence} THEN 'ACTIVE'
                    ELSE status
                END,
                update_time = NOW()
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(@Param("userId") Long userId, @Param("variant") String variant, @Param("canonical") String canonical,
               @Param("srcLang") String srcLang, @Param("scope") String scope, @Param("confidence") Double confidence,
               @Param("hitCount") Integer hitCount, @Param("status") String status, @Param("source") String source,
               @Param("promoteHitCount") int promoteHitCount, @Param("promoteConfidence") double promoteConfidence);

    @Select("SELECT * FROM asr_correction WHERE user_id = #{userId} AND status = 'ACTIVE' ORDER BY hit_count DESC, id ASC")
    List<AsrCorrection> findActive(@Param("userId") Long userId);

    @Select("SELECT * FROM asr_correction WHERE user_id = #{userId} ORDER BY update_time DESC")
    List<AsrCorrection> findAll(@Param("userId") Long userId);
}
