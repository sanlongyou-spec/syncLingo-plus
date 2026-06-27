package com.si.backend.mapper;

import com.si.backend.entity.InterpretationSession;
import org.apache.ibatis.annotations.*;

/**
 * 同传会话 Mapper，操作 interpretation_session 表。
 */
@Mapper
public interface InterpretationSessionMapper {

    @Update("ALTER TABLE interpretation_session ADD COLUMN asr_audio_ms BIGINT DEFAULT 0")
    void addAsrAudioMsColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN translate_chars BIGINT DEFAULT 0")
    void addTranslateCharsColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN tts_chars BIGINT DEFAULT 0")
    void addTtsCharsColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN llm_input_tokens BIGINT DEFAULT 0")
    void addLlmInputTokensColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN llm_output_tokens BIGINT DEFAULT 0")
    void addLlmOutputTokensColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN llm_summary_input_tokens BIGINT DEFAULT 0")
    void addLlmSummaryInputTokensColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN llm_summary_output_tokens BIGINT DEFAULT 0")
    void addLlmSummaryOutputTokensColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN title VARCHAR(128) DEFAULT '未命名同传' AFTER voice_id")
    void addTitleColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 AFTER status")
    void addDeletedColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN hotword_ids VARCHAR(2048) DEFAULT NULL AFTER voice_id")
    void addHotwordIdsColumnIfNotExists();

    /** 自动抽取使热词数大增,选中 ID 串可能超过 VARCHAR(2048);加宽为 TEXT。幂等,可重复执行。 */
    @Update("ALTER TABLE interpretation_session MODIFY COLUMN hotword_ids TEXT")
    void widenHotwordIdsColumnToText();

    @Update("ALTER TABLE interpretation_session ADD COLUMN enabled_languages VARCHAR(128) DEFAULT NULL AFTER hotword_ids")
    void addEnabledLanguagesColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN meeting_summary TEXT DEFAULT NULL")
    void addMeetingSummaryColumnIfNotExists();

    @Update("ALTER TABLE interpretation_session ADD COLUMN meeting_id BIGINT DEFAULT NULL")
    void addMeetingIdColumnIfNotExists();

    @Update("UPDATE interpretation_session SET meeting_summary = #{summary} WHERE session_id = #{sessionId}")
    int updateMeetingSummary(@Param("sessionId") String sessionId, @Param("summary") String summary);

    @Insert("INSERT INTO interpretation_session (session_id, user_id, source_lang, target_lang, voice_id, hotword_ids, enabled_languages, title, status, deleted, start_time, asr_audio_ms, translate_chars, tts_chars, llm_input_tokens, llm_output_tokens, meeting_id, create_time) " +
            "VALUES (#{sessionId}, #{userId}, #{sourceLang}, #{targetLang}, #{voiceId}, #{hotwordIds}, #{enabledLanguages}, #{title}, #{status}, 0, #{startTime}, 0, 0, 0, 0, 0, #{meetingId}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationSession session);

    @Select("SELECT * FROM interpretation_session WHERE session_id = #{sessionId} AND COALESCE(deleted, 0) = 0")
    InterpretationSession findBySessionId(String sessionId);

    @Update("UPDATE interpretation_session SET status = #{status}, end_time = #{endTime} WHERE session_id = #{sessionId}")
    int updateStatus(@Param("sessionId") String sessionId, @Param("status") String status, @Param("endTime") java.time.LocalDateTime endTime);

    @Update("UPDATE interpretation_session SET asr_audio_ms = COALESCE(asr_audio_ms, 0) + #{delta} WHERE session_id = #{sessionId}")
    int addAsrAudioMs(@Param("sessionId") String sessionId, @Param("delta") long delta);

    @Update("UPDATE interpretation_session SET translate_chars = COALESCE(translate_chars, 0) + #{delta} WHERE session_id = #{sessionId}")
    int addTranslateChars(@Param("sessionId") String sessionId, @Param("delta") long delta);

    @Update("UPDATE interpretation_session SET tts_chars = COALESCE(tts_chars, 0) + #{delta} WHERE session_id = #{sessionId}")
    int addTtsChars(@Param("sessionId") String sessionId, @Param("delta") long delta);

    @Update("UPDATE interpretation_session SET llm_input_tokens = COALESCE(llm_input_tokens, 0) + #{inputDelta}, llm_output_tokens = COALESCE(llm_output_tokens, 0) + #{outputDelta} WHERE session_id = #{sessionId}")
    int addLlmTokens(@Param("sessionId") String sessionId, @Param("inputDelta") long inputDelta, @Param("outputDelta") long outputDelta);

    @Update("UPDATE interpretation_session SET llm_summary_input_tokens = COALESCE(llm_summary_input_tokens, 0) + #{inputDelta}, llm_summary_output_tokens = COALESCE(llm_summary_output_tokens, 0) + #{outputDelta} WHERE session_id = #{sessionId}")
    int addSummaryLlmTokens(@Param("sessionId") String sessionId, @Param("inputDelta") long inputDelta, @Param("outputDelta") long outputDelta);

    @Select("SELECT * FROM interpretation_session WHERE user_id = #{userId} AND COALESCE(deleted, 0) = 0 ORDER BY create_time DESC")
    java.util.List<InterpretationSession> findByUserId(Long userId);

    @Select("SELECT * FROM interpretation_session WHERE user_id = #{userId} AND status = 'running' AND COALESCE(deleted, 0) = 0 ORDER BY start_time DESC LIMIT 1")
    InterpretationSession findActiveByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT s.*,
                   (SELECT COUNT(*) FROM interpretation_result r WHERE r.session_id = s.session_id) AS result_count
            FROM interpretation_session s
            WHERE s.user_id = #{userId}
              AND COALESCE(s.deleted, 0) = 0
              AND (
                #{keyword} IS NULL OR #{keyword} = ''
                OR s.title LIKE CONCAT('%', #{keyword}, '%')
                OR s.session_id LIKE CONCAT('%', #{keyword}, '%')
                OR EXISTS (
                  SELECT 1 FROM interpretation_result r
                  WHERE r.session_id = s.session_id
                    AND (r.source_text LIKE CONCAT('%', #{keyword}, '%')
                         OR r.translated_text LIKE CONCAT('%', #{keyword}, '%'))
                )
              )
            ORDER BY s.create_time DESC
            """)
    java.util.List<InterpretationSession> searchByUserId(@Param("userId") Long userId, @Param("keyword") String keyword);

    @Update("UPDATE interpretation_session SET title = #{title} WHERE session_id = #{sessionId} AND user_id = #{userId} AND COALESCE(deleted, 0) = 0")
    int updateTitle(@Param("sessionId") String sessionId, @Param("userId") Long userId, @Param("title") String title);

    @Update("UPDATE interpretation_session SET deleted = 1 WHERE session_id = #{sessionId} AND user_id = #{userId}")
    int softDelete(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /** Cascade: soft-delete all sessions of a meeting (called when the meeting is deleted). */
    @Update("UPDATE interpretation_session SET deleted = 1 WHERE meeting_id = #{meetingId}")
    int softDeleteByMeetingId(@Param("meetingId") Long meetingId);

    @Select("SELECT s.*, (SELECT COUNT(*) FROM interpretation_result r WHERE r.session_id = s.session_id) AS result_count " +
            "FROM interpretation_session s " +
            "WHERE s.meeting_id = #{meetingId} AND COALESCE(s.deleted, 0) = 0 " +
            "ORDER BY s.create_time DESC")
    java.util.List<InterpretationSession> findByMeetingId(@Param("meetingId") Long meetingId);

    @Select("""
            SELECT DATE_FORMAT(start_time, '%Y-%m') AS month,
                   COUNT(*)                          AS sessionCount,
                   SUM(COALESCE(asr_audio_ms, 0))     AS totalAsrMs,
                   SUM(COALESCE(translate_chars, 0))  AS totalTransChars,
                   SUM(COALESCE(tts_chars, 0))        AS totalTtsChars,
                   SUM(COALESCE(llm_input_tokens, 0)) AS totalLlmIn,
                   SUM(COALESCE(llm_output_tokens, 0)) AS totalLlmOut,
                   SUM(COALESCE(llm_summary_input_tokens, 0)) AS totalSummaryLlmIn,
                   SUM(COALESCE(llm_summary_output_tokens, 0)) AS totalSummaryLlmOut
            FROM interpretation_session
            WHERE user_id = #{userId} AND COALESCE(deleted, 0) = 0 AND start_time IS NOT NULL
            GROUP BY DATE_FORMAT(start_time, '%Y-%m')
            ORDER BY month DESC
            """)
    java.util.List<java.util.Map<String, Object>> monthlySummaryByUser(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(COALESCE(asr_audio_ms, 0)), 0)     AS totalAsrMs,
                   COALESCE(SUM(COALESCE(translate_chars, 0)), 0)  AS totalTransChars,
                   COALESCE(SUM(COALESCE(tts_chars, 0)), 0)        AS totalTtsChars,
                   COALESCE(SUM(COALESCE(llm_input_tokens, 0)), 0) AS totalLlmIn,
                   COALESCE(SUM(COALESCE(llm_output_tokens, 0)), 0) AS totalLlmOut,
                   COALESCE(SUM(COALESCE(llm_summary_input_tokens, 0)), 0) AS totalSummaryLlmIn,
                   COALESCE(SUM(COALESCE(llm_summary_output_tokens, 0)), 0) AS totalSummaryLlmOut,
                   COUNT(*)                                         AS sessionCount
            FROM interpretation_session
            WHERE user_id = #{userId}
              AND COALESCE(deleted, 0) = 0
              AND DATE_FORMAT(start_time, '%Y-%m') = #{yearMonth}
            """)
    java.util.Map<String, Object> currentMonthSummaryByUser(@Param("userId") Long userId, @Param("yearMonth") String yearMonth);
}
