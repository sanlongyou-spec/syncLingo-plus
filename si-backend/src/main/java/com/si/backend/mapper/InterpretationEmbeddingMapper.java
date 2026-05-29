package com.si.backend.mapper;

import com.si.backend.dto.EmbedCandidate;
import com.si.backend.entity.InterpretationEmbedding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InterpretationEmbeddingMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS interpretation_embedding (
                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                result_id       BIGINT       NOT NULL,
                session_id      VARCHAR(64)  NOT NULL,
                meeting_id      BIGINT       DEFAULT NULL,
                session_title   VARCHAR(255) DEFAULT NULL,
                session_date    DATE         DEFAULT NULL,
                speaker_name    VARCHAR(128) DEFAULT NULL,
                chunk_text      TEXT         NOT NULL,
                translated_text TEXT         DEFAULT NULL,
                embedding       MEDIUMBLOB   NOT NULL,
                create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_emb_result (result_id),
                INDEX idx_emb_session (session_id),
                INDEX idx_emb_meeting (meeting_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTableIfNotExists();

    // ── Schema migrations (called from @PostConstruct) ───────────────────

    @Update("ALTER TABLE interpretation_embedding MODIFY COLUMN result_id BIGINT DEFAULT NULL")
    void makeResultIdNullable();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'result'")
    void addSourceTypeColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN source_id BIGINT DEFAULT NULL")
    void addSourceIdColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD UNIQUE KEY uk_emb_source (source_type, source_id)")
    void addSourceUniqueIndexIfNotExists();

    // ── Inserts ──────────────────────────────────────────────────────────

    @Insert("""
            INSERT INTO interpretation_embedding
                (result_id, session_id, meeting_id, session_title, session_date,
                 speaker_name, chunk_text, translated_text, embedding, create_time)
            VALUES
                (#{resultId}, #{sessionId}, #{meetingId}, #{sessionTitle}, #{sessionDate},
                 #{speakerName}, #{chunkText}, #{translatedText}, #{embedding}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationEmbedding emb);

    /** Insert for non-result content types; silently ignored if (source_type, source_id) already exists. */
    @Insert("""
            INSERT IGNORE INTO interpretation_embedding
                (source_type, source_id, session_id, meeting_id, session_title, session_date,
                 speaker_name, chunk_text, translated_text, embedding, create_time)
            VALUES
                (#{sourceType}, #{sourceId}, #{sessionId}, #{meetingId}, #{sessionTitle}, #{sessionDate},
                 #{speakerName}, #{chunkText}, #{translatedText}, #{embedding}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertContent(InterpretationEmbedding emb);

    // ── Dedup queries ────────────────────────────────────────────────────

    @Select("SELECT COUNT(*) FROM interpretation_embedding WHERE result_id = #{resultId}")
    int countByResultId(@Param("resultId") long resultId);

    @Select("SELECT COUNT(*) FROM interpretation_embedding WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    int countBySourceTypeAndId(@Param("sourceType") String sourceType, @Param("sourceId") long sourceId);

    // ── Queries ──────────────────────────────────────────────────────────

    /** Dynamic filter query — implemented in InterpretationEmbeddingMapper.xml */
    List<InterpretationEmbedding> findByUserId(
            @Param("userId") long userId,
            @Param("filterMeetingId") Long filterMeetingId,
            @Param("filterSpeakerName") String filterSpeakerName,
            @Param("filterSince") String filterSince);

    /** Returns rows in interpretation_result that have no embedding yet */
    List<EmbedCandidate> findResultsWithoutEmbedding(@Param("limit") int limit);
}
