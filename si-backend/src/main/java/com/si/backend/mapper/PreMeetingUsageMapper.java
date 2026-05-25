package com.si.backend.mapper;

import com.si.backend.entity.PreMeetingUsageRecord;
import com.si.backend.vo.PreMeetingDailyUsageVo;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PreMeetingUsageMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS pre_meeting_usage_record (
                id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id           BIGINT,
                file_name         VARCHAR(512),
                llm_input_tokens  BIGINT DEFAULT 0,
                llm_output_tokens BIGINT DEFAULT 0,
                create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_user_id    (user_id),
                INDEX idx_create_time (create_time)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO pre_meeting_usage_record (user_id, file_name, llm_input_tokens, llm_output_tokens, create_time)
            VALUES (#{userId}, #{fileName}, #{llmInputTokens}, #{llmOutputTokens}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PreMeetingUsageRecord record);

    @Select("""
            SELECT DATE(create_time) AS date,
                   SUM(llm_input_tokens)  AS llmInputTokens,
                   SUM(llm_output_tokens) AS llmOutputTokens
            FROM pre_meeting_usage_record
            WHERE user_id = #{userId}
              AND create_time >= #{since}
            GROUP BY DATE(create_time)
            ORDER BY DATE(create_time)
            """)
    List<PreMeetingDailyUsageVo> findDailyUsage(@Param("userId") long userId, @Param("since") String since);
}
