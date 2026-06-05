package com.si.backend.mapper;

import com.si.backend.entity.SystemUserInfo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for system-wide user information.
 */
@Mapper
public interface SystemUserInfoMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS system_user_info (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                department VARCHAR(128),
                person_name VARCHAR(128) NOT NULL,
                position_title VARCHAR(128),
                email VARCHAR(255) NOT NULL,
                microsoft_id VARCHAR(128),
                robin_uid VARCHAR(64),
                teams_verified VARCHAR(64),
                employment_status VARCHAR(64),
                source_sheet VARCHAR(128),
                source_row INT,
                create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_system_user_email (email),
                INDEX idx_system_user_name (person_name),
                INDEX idx_system_user_department (department)
            )
            """)
    void createTableIfNotExists();

    @Update("ALTER TABLE system_user_info ADD COLUMN nationality VARCHAR(64) DEFAULT NULL")
    void addNationalityColumnIfNotExists();

    @Insert("""
            INSERT INTO system_user_info (
                department, person_name, position_title, email, microsoft_id, robin_uid,
                teams_verified, employment_status, nationality, source_sheet, source_row, create_time, update_time
            )
            VALUES (
                #{department}, #{personName}, #{positionTitle}, #{email}, #{microsoftId}, #{robinUid},
                #{teamsVerified}, #{employmentStatus}, #{nationality}, #{sourceSheet}, #{sourceRow}, NOW(), NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SystemUserInfo userInfo);

    @Select("""
            SELECT * FROM system_user_info
            WHERE #{keyword} IS NULL OR #{keyword} = ''
               OR person_name LIKE CONCAT('%', #{keyword}, '%')
               OR email LIKE CONCAT('%', #{keyword}, '%')
               OR department LIKE CONCAT('%', #{keyword}, '%')
               OR position_title LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY department ASC, person_name ASC, id DESC
            """)
    List<SystemUserInfo> findAll(@Param("keyword") String keyword);

    @Select("SELECT * FROM system_user_info WHERE id = #{id}")
    SystemUserInfo findById(@Param("id") Long id);

    @Select("SELECT * FROM system_user_info WHERE email = #{email}")
    SystemUserInfo findByEmail(@Param("email") String email);

    @Select("SELECT * FROM system_user_info WHERE microsoft_id = #{microsoftId} ORDER BY id ASC LIMIT 1")
    SystemUserInfo findByMicrosoftId(@Param("microsoftId") String microsoftId);

    @Select("SELECT * FROM system_user_info WHERE robin_uid = #{robinUid} ORDER BY id ASC LIMIT 1")
    SystemUserInfo findByRobinUid(@Param("robinUid") String robinUid);

    @Select("""
            SELECT * FROM system_user_info
            WHERE person_name = #{personName}
            AND (
                (#{department} IS NULL AND department IS NULL)
                OR department = #{department}
            )
            ORDER BY id ASC
            LIMIT 1
            """)
    SystemUserInfo findByPersonNameAndDepartment(
            @Param("personName") String personName,
            @Param("department") String department
    );

    @Update("""
            UPDATE system_user_info
            SET department = #{department},
                person_name = #{personName},
                position_title = #{positionTitle},
                email = #{email},
                microsoft_id = #{microsoftId},
                robin_uid = #{robinUid},
                teams_verified = #{teamsVerified},
                employment_status = #{employmentStatus},
                update_time = NOW()
            WHERE id = #{id}
            """)
    int update(SystemUserInfo userInfo);

    @Update("""
            UPDATE system_user_info
            SET department = #{department},
                person_name = #{personName},
                position_title = #{positionTitle},
                email = #{email},
                microsoft_id = #{microsoftId},
                robin_uid = #{robinUid},
                teams_verified = #{teamsVerified},
                employment_status = #{employmentStatus},
                nationality = #{nationality},
                source_sheet = #{sourceSheet},
                source_row = #{sourceRow},
                update_time = NOW()
            WHERE id = #{id}
            """)
    int updateFromImport(SystemUserInfo userInfo);

    @Delete("DELETE FROM system_user_info WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
