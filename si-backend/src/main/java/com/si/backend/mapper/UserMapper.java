package com.si.backend.mapper;

import com.si.backend.entity.SiUser;
import org.apache.ibatis.annotations.*;

/**
 * 用户 Mapper，操作 si_user 表。
 */
@Mapper
public interface UserMapper {

    @Select("SELECT * FROM si_user WHERE username = #{username}")
    SiUser findByUsername(String username);

    @Select("SELECT * FROM si_user WHERE id = #{id}")
    SiUser findById(Long id);

    @Select("""
            SELECT *
            FROM si_user
            WHERE LOWER(username) = LOWER(#{identity})
               OR LOWER(email) = LOWER(#{identity})
            LIMIT 1
            """)
    SiUser findByUsernameOrEmailIgnoreCase(String identity);

    @Insert("INSERT INTO si_user (username, password, nickname, email, role, create_time, update_time) " +
            "VALUES (#{username}, #{password}, #{nickname}, #{email}, #{role}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SiUser user);

    @Select("SELECT summary_recipients FROM si_user WHERE id = #{userId}")
    String getSummaryRecipients(@Param("userId") Long userId);

    @Update("UPDATE si_user SET summary_recipients = #{recipients}, update_time = NOW() WHERE id = #{userId}")
    int updateSummaryRecipients(@Param("userId") Long userId, @Param("recipients") String recipients);

    /** P1:为存量库补 status 列(列已存在时 MySQL 抛 Duplicate column,由调用方忽略)。 */
    @Update("ALTER TABLE si_user ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'")
    void addStatusColumnIfNotExists();

    // ── P1 用户管理 ──────────────────────────────────────────────
    @Select("SELECT * FROM si_user ORDER BY id ASC")
    java.util.List<SiUser> findAll();

    @Update("UPDATE si_user SET role = #{role}, update_time = NOW() WHERE id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);

    @Update("UPDATE si_user SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE si_user SET password = #{password}, update_time = NOW() WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 当前有效(未停用)管理员数量,用于"最后一个管理员"保护。 */
    @Select("SELECT COUNT(*) FROM si_user WHERE role = 'ADMIN' AND (status IS NULL OR status <> 'DISABLED')")
    int countActiveAdmins();
}
