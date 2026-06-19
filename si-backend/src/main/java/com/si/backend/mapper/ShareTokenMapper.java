package com.si.backend.mapper;

import com.si.backend.entity.ShareToken;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分享令牌 Mapper(P4)。
 */
@Mapper
public interface ShareTokenMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS share_token (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                token_hash VARCHAR(128) NOT NULL,
                kind VARCHAR(16) NOT NULL,
                session_id VARCHAR(64),
                owner_user_id BIGINT,
                expires_at DATETIME,
                revoked_at DATETIME,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_share_token_hash (token_hash),
                INDEX idx_share_owner (owner_user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO share_token (token_hash, kind, session_id, owner_user_id, expires_at, create_time)
            VALUES (#{tokenHash}, #{kind}, #{sessionId}, #{ownerUserId}, #{expiresAt}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ShareToken token);

    @Select("SELECT * FROM share_token WHERE token_hash = #{tokenHash} LIMIT 1")
    ShareToken findByHash(@Param("tokenHash") String tokenHash);

    @Select("SELECT * FROM share_token WHERE id = #{id}")
    ShareToken findById(@Param("id") Long id);

    @Update("UPDATE share_token SET revoked_at = NOW() WHERE id = #{id} AND revoked_at IS NULL")
    int revoke(@Param("id") Long id);

    /**
     * 一次性失效"无过期时间"的历史令牌（6 小时有效期策略上线前签发的旧链接）。
     * 只命中 expires_at 为空且未撤销的旧令牌；新令牌一律带 expires_at，故本操作重启幂等、不会误伤。
     */
    @Update("UPDATE share_token SET revoked_at = NOW() WHERE expires_at IS NULL AND revoked_at IS NULL")
    int revokeLegacyTokensWithoutExpiry();

    @Select("SELECT * FROM share_token WHERE owner_user_id = #{ownerUserId} ORDER BY id DESC")
    List<ShareToken> findByOwner(@Param("ownerUserId") Long ownerUserId);
}
