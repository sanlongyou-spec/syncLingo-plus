package com.si.backend.mapper;

import com.si.backend.entity.SupportAccessGrant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 管理员临时内容授权 Mapper(P3)。
 */
@Mapper
public interface SupportAccessGrantMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS support_access_grant (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                grantee_user_id BIGINT NOT NULL,
                resource_type VARCHAR(32) NOT NULL,
                resource_id VARCHAR(64) NOT NULL,
                permissions VARCHAR(255) NOT NULL,
                reason VARCHAR(512) NOT NULL,
                requested_by BIGINT NOT NULL,
                approved_by BIGINT,
                expires_at DATETIME NOT NULL,
                revoked_at DATETIME,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_sag_grantee (grantee_user_id, resource_type, resource_id),
                INDEX idx_sag_create (create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO support_access_grant
              (grantee_user_id, resource_type, resource_id, permissions, reason, requested_by, expires_at, create_time)
            VALUES (#{granteeUserId}, #{resourceType}, #{resourceId}, #{permissions}, #{reason}, #{requestedBy}, #{expiresAt}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SupportAccessGrant grant);

    @Select("SELECT * FROM support_access_grant WHERE id = #{id}")
    SupportAccessGrant findById(@Param("id") Long id);

    /** 批准:仅未批准、未撤销者可批准。返回受影响行数(0=已批准/不存在)。 */
    @Update("UPDATE support_access_grant SET approved_by = #{approvedBy} WHERE id = #{id} AND approved_by IS NULL AND revoked_at IS NULL")
    int approve(@Param("id") Long id, @Param("approvedBy") Long approvedBy);

    @Update("UPDATE support_access_grant SET revoked_at = NOW() WHERE id = #{id} AND revoked_at IS NULL")
    int revoke(@Param("id") Long id);

    /** 当前是否存在生效授权(已批准、未撤销、未过期)。 */
    @Select("""
            SELECT COUNT(*) FROM support_access_grant
            WHERE grantee_user_id = #{granteeUserId} AND resource_type = #{resourceType} AND resource_id = #{resourceId}
              AND approved_by IS NOT NULL AND revoked_at IS NULL AND expires_at > NOW()
            """)
    int countActive(@Param("granteeUserId") Long granteeUserId,
                    @Param("resourceType") String resourceType,
                    @Param("resourceId") String resourceId);

    @Select("SELECT * FROM support_access_grant ORDER BY id DESC LIMIT #{limit}")
    List<SupportAccessGrant> findRecent(@Param("limit") int limit);
}
