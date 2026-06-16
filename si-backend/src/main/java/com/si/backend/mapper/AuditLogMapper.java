package com.si.backend.mapper;

import com.si.backend.entity.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审计日志 Mapper(仅追加:只 INSERT/SELECT,不提供 UPDATE/DELETE)。
 */
@Mapper
public interface AuditLogMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS audit_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                actor_type VARCHAR(16),
                actor_id VARCHAR(64),
                role VARCHAR(16),
                action VARCHAR(48) NOT NULL,
                resource_type VARCHAR(32),
                resource_id VARCHAR(64),
                result VARCHAR(16) NOT NULL,
                ip VARCHAR(64),
                detail VARCHAR(1024),
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_audit_action (action),
                INDEX idx_audit_actor (actor_id),
                INDEX idx_audit_create_time (create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO audit_log (actor_type, actor_id, role, action, resource_type, resource_id, result, ip, detail, create_time)
            VALUES (#{actorType}, #{actorId}, #{role}, #{action}, #{resourceType}, #{resourceId}, #{result}, #{ip}, #{detail}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLog log);

    @Select("SELECT * FROM audit_log ORDER BY id DESC LIMIT #{limit}")
    List<AuditLog> findRecent(@Param("limit") int limit);
}
