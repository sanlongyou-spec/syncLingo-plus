package com.si.backend.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会议知识包 Mapper,操作 meeting_knowledge 表(每账号一条:最近上传会议文件蒸馏出的背景知识)。
 */
@Mapper
public interface MeetingKnowledgeMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS meeting_knowledge (
                user_id     BIGINT PRIMARY KEY,
                content     TEXT,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO meeting_knowledge (user_id, content, update_time)
            VALUES (#{userId}, #{content}, NOW())
            ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = NOW()
            """)
    int upsert(@Param("userId") Long userId, @Param("content") String content);

    @Select("SELECT content FROM meeting_knowledge WHERE user_id = #{userId}")
    String findContent(@Param("userId") Long userId);
}
