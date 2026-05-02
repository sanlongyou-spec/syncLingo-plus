package com.si.backend.mapper;

import com.si.backend.entity.InterpretationSession;
import org.apache.ibatis.annotations.*;

/**
 * 同传会话 Mapper，操作 interpretation_session 表。
 */
@Mapper
public interface InterpretationSessionMapper {

    @Insert("INSERT INTO interpretation_session (session_id, user_id, source_lang, target_lang, voice_id, status, start_time, create_time) " +
            "VALUES (#{sessionId}, #{userId}, #{sourceLang}, #{targetLang}, #{voiceId}, #{status}, #{startTime}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationSession session);

    @Select("SELECT * FROM interpretation_session WHERE session_id = #{sessionId}")
    InterpretationSession findBySessionId(String sessionId);

    @Update("UPDATE interpretation_session SET status = #{status}, end_time = #{endTime} WHERE session_id = #{sessionId}")
    int updateStatus(@Param("sessionId") String sessionId, @Param("status") String status, @Param("endTime") java.time.LocalDateTime endTime);

    @Select("SELECT * FROM interpretation_session WHERE user_id = #{userId} ORDER BY create_time DESC")
    java.util.List<InterpretationSession> findByUserId(Long userId);
}
