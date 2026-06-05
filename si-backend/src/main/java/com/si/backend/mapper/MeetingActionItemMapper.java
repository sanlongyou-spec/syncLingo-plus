package com.si.backend.mapper;

import com.si.backend.entity.MeetingActionItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MeetingActionItemMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS meeting_action_item (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id  VARCHAR(64)  NOT NULL,
                meeting_id  BIGINT       DEFAULT NULL,
                user_id     BIGINT       DEFAULT NULL,
                assignee    VARCHAR(255) DEFAULT NULL,
                content     TEXT         NOT NULL,
                deadline    VARCHAR(64)  DEFAULT NULL,
                status      VARCHAR(32)  NOT NULL DEFAULT 'pending',
                create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_mai_session (session_id),
                INDEX idx_mai_meeting (meeting_id),
                INDEX idx_mai_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Insert("INSERT INTO meeting_action_item (session_id, meeting_id, user_id, assignee, content, deadline, status) " +
            "VALUES (#{sessionId}, #{meetingId}, #{userId}, #{assignee}, #{content}, #{deadline}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MeetingActionItem item);

    @Select("SELECT * FROM meeting_action_item WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<MeetingActionItem> findBySessionId(String sessionId);

    @Select("SELECT * FROM meeting_action_item WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<MeetingActionItem> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM meeting_action_item WHERE id = #{id}")
    MeetingActionItem findById(Long id);

    @Update("UPDATE meeting_action_item SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("DELETE FROM meeting_action_item WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM meeting_action_item WHERE meeting_id = #{meetingId}")
    int deleteByMeetingId(Long meetingId);

    @Delete("DELETE FROM meeting_action_item WHERE session_id = #{sessionId}")
    int deleteBySessionId(String sessionId);
}
