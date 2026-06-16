package com.si.backend.mapper;

import com.si.backend.entity.MeetingMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 会议成员 Mapper(P3)。
 */
@Mapper
public interface MeetingMemberMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS meeting_member (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                meeting_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                access_level VARCHAR(16) NOT NULL,
                assigned_by BIGINT,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_meeting_member (meeting_id, user_id),
                INDEX idx_mm_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO meeting_member (meeting_id, user_id, access_level, assigned_by, create_time)
            VALUES (#{meetingId}, #{userId}, #{accessLevel}, #{assignedBy}, NOW())
            ON DUPLICATE KEY UPDATE access_level = #{accessLevel}, assigned_by = #{assignedBy}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(MeetingMember member);

    @Select("SELECT * FROM meeting_member WHERE meeting_id = #{meetingId} ORDER BY id ASC")
    List<MeetingMember> findByMeetingId(@Param("meetingId") Long meetingId);

    @Select("SELECT * FROM meeting_member WHERE meeting_id = #{meetingId} AND user_id = #{userId} LIMIT 1")
    MeetingMember findMember(@Param("meetingId") Long meetingId, @Param("userId") Long userId);

    @Delete("DELETE FROM meeting_member WHERE meeting_id = #{meetingId} AND user_id = #{userId}")
    int deleteMember(@Param("meetingId") Long meetingId, @Param("userId") Long userId);

    @Delete("DELETE FROM meeting_member WHERE meeting_id = #{meetingId}")
    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
