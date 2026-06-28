package com.si.backend.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会议知识包 Mapper，操作 meeting_knowledge 表（每场会议一条：该会议上传材料蒸馏出的背景知识）。
 * 按 meeting_id 隔离，避免跨会议串用知识包。
 */
@Mapper
public interface MeetingKnowledgeMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS meeting_knowledge (
                meeting_id  BIGINT PRIMARY KEY,
                content     TEXT,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
            """)
    void createTableIfNotExists();

    /** 是否已是按 meeting_id 建的新表（旧表按 user_id，返回 0）。 */
    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'meeting_knowledge' AND COLUMN_NAME = 'meeting_id'
            """)
    int hasMeetingIdColumn();

    /** 知识包是派生缓存，旧表(user_id 主键)可安全重建。 */
    @Update("DROP TABLE IF EXISTS meeting_knowledge")
    void dropTable();

    @Insert("""
            INSERT INTO meeting_knowledge (meeting_id, content, update_time)
            VALUES (#{meetingId}, #{content}, NOW())
            ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = NOW()
            """)
    int upsert(@Param("meetingId") Long meetingId, @Param("content") String content);

    @Select("SELECT content FROM meeting_knowledge WHERE meeting_id = #{meetingId}")
    String findContent(@Param("meetingId") Long meetingId);
}
