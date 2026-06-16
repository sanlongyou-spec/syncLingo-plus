package com.si.backend.mapper;

import com.si.backend.entity.AuthSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for auth_session refresh-token families.
 */
@Mapper
public interface AuthSessionMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS auth_session (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                family_id VARCHAR(64) NOT NULL,
                refresh_token_hash VARCHAR(128) NOT NULL,
                rotated_from_hash VARCHAR(128),
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                absolute_expires_at DATETIME NOT NULL,
                idle_expires_at DATETIME NOT NULL,
                last_seen_at DATETIME NOT NULL,
                rotated_at DATETIME,
                revoked_at DATETIME,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_auth_session_session (session_id),
                UNIQUE KEY uk_auth_session_refresh (refresh_token_hash),
                INDEX idx_auth_session_family (family_id),
                INDEX idx_auth_session_user (user_id),
                INDEX idx_auth_session_rotated_from (rotated_from_hash)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO auth_session (
                user_id, session_id, family_id, refresh_token_hash, rotated_from_hash, status,
                absolute_expires_at, idle_expires_at, last_seen_at, create_time
            ) VALUES (
                #{userId}, #{sessionId}, #{familyId}, #{refreshTokenHash}, #{rotatedFromHash}, #{status},
                #{absoluteExpiresAt}, #{idleExpiresAt}, #{lastSeenAt}, NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuthSession session);

    @Select("SELECT * FROM auth_session WHERE refresh_token_hash = #{hash} LIMIT 1")
    AuthSession findByRefreshTokenHash(@Param("hash") String hash);

    @Select("SELECT * FROM auth_session WHERE rotated_from_hash = #{hash} LIMIT 1")
    AuthSession findByRotatedFromHash(@Param("hash") String hash);

    @Update("""
            UPDATE auth_session
            SET status = 'ROTATED', rotated_at = NOW(), last_seen_at = NOW()
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int markRotated(@Param("id") Long id);

    @Update("""
            UPDATE auth_session
            SET status = 'REVOKED', revoked_at = NOW()
            WHERE family_id = #{familyId} AND revoked_at IS NULL
            """)
    int revokeFamily(@Param("familyId") String familyId);

    @Update("""
            UPDATE auth_session
            SET status = 'REVOKED', revoked_at = NOW()
            WHERE refresh_token_hash = #{hash} AND revoked_at IS NULL
            """)
    int revokeByRefreshTokenHash(@Param("hash") String hash);
}
