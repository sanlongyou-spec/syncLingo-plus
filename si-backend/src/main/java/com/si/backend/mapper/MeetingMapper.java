package com.si.backend.mapper;

import com.si.backend.entity.Meeting;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MeetingMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS meeting (
                id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id        BIGINT       NOT NULL,
                title          VARCHAR(256) NOT NULL,
                scheduled_time DATETIME     DEFAULT NULL,
                note           TEXT         DEFAULT NULL,
                meeting_url    TEXT         DEFAULT NULL,
                deleted        TINYINT(1)   NOT NULL DEFAULT 0,
                create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_meeting_user_id (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("INSERT INTO meeting (user_id, title, scheduled_time, note, deleted, create_time) " +
            "VALUES (#{userId}, #{title}, #{scheduledTime}, #{note}, 0, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Meeting meeting);

    @Select("SELECT * FROM meeting WHERE id = #{id} AND deleted = 0")
    Meeting findById(Long id);

    /** Count non-deleted meetings of a user with the exact same title (for duplicate-name guard). */
    @Select("SELECT COUNT(*) FROM meeting WHERE user_id = #{userId} AND title = #{title} AND deleted = 0")
    int countByUserIdAndTitle(@Param("userId") Long userId, @Param("title") String title);

    @Select("SELECT * FROM meeting WHERE user_id = #{userId} AND deleted = 0 ORDER BY create_time DESC")
    List<Meeting> findByUserId(Long userId);

    @Update("UPDATE meeting SET deleted = 1 WHERE id = #{id}")
    int softDelete(Long id);

    /** Wipe the meeting's stored 应到名单 / 实到核对 when the meeting is deleted. */
    @Update("UPDATE meeting SET expected_participants_json = NULL, attendance_json = NULL, meeting_url = NULL WHERE id = #{id}")
    int clearAssociatedData(Long id);

    @Update("UPDATE meeting SET title = #{title}, scheduled_time = #{scheduledTime}, note = #{note} WHERE id = #{id}")
    int update(Meeting meeting);

    @Update("ALTER TABLE meeting ADD COLUMN attendance_json MEDIUMTEXT DEFAULT NULL")
    void addAttendanceJsonColumnIfNotExists();

    @Update("UPDATE meeting SET attendance_json = #{json} WHERE id = #{id}")
    int updateAttendanceJson(@Param("id") Long id, @Param("json") String json);

    @Update("ALTER TABLE meeting ADD COLUMN expected_participants_json MEDIUMTEXT DEFAULT NULL")
    void addExpectedParticipantsColumnIfNotExists();

    @Update("UPDATE meeting SET expected_participants_json = #{json} WHERE id = #{id}")
    int updateExpectedParticipants(@Param("id") Long id, @Param("json") String json);

    @Update("ALTER TABLE meeting ADD COLUMN meeting_url TEXT DEFAULT NULL")
    void addMeetingUrlColumnIfNotExists();

    @Update("UPDATE meeting SET meeting_url = #{url} WHERE id = #{id}")
    int updateMeetingUrl(@Param("id") Long id, @Param("url") String url);
}
