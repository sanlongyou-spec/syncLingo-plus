package com.si.backend.mapper;

import com.si.backend.entity.AuditOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for the audit transactional outbox.
 */
@Mapper
public interface AuditOutboxMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS audit_outbox (
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
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                retry_count INT NOT NULL DEFAULT 0,
                last_error VARCHAR(512),
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                sent_time DATETIME NULL,
                INDEX idx_audit_outbox_status (status, id),
                INDEX idx_audit_outbox_create_time (create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO audit_outbox
              (actor_type, actor_id, role, action, resource_type, resource_id, result, ip, detail, status, retry_count, create_time, update_time)
            VALUES
              (#{actorType}, #{actorId}, #{role}, #{action}, #{resourceType}, #{resourceId}, #{result}, #{ip}, #{detail}, 'PENDING', 0, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditOutbox entry);

    @Select("""
            SELECT * FROM audit_outbox
            WHERE status IN ('PENDING', 'FAILED')
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<AuditOutbox> findPending(@Param("limit") int limit);

    @Update("UPDATE audit_outbox SET status = 'SENT', sent_time = NOW(), update_time = NOW(), last_error = NULL WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    @Update("""
            UPDATE audit_outbox
            SET status = 'FAILED', retry_count = retry_count + 1, last_error = #{error}, update_time = NOW()
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id, @Param("error") String error);
}
