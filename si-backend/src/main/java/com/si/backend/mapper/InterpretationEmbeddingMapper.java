package com.si.backend.mapper;

import com.si.backend.dto.EmbedCandidate;
import com.si.backend.entity.InterpretationEmbedding;
import org.apache.ibatis.annotations.Delete;
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
                embedding_model VARCHAR(128) DEFAULT NULL,
                embedding_dim   INT          DEFAULT NULL,
                embedding_profile VARCHAR(64) NOT NULL DEFAULT 'default',
                content_hash    CHAR(64)     DEFAULT NULL,
                index_status    VARCHAR(24)  NOT NULL DEFAULT 'READY',
                last_embedded_at DATETIME    DEFAULT NULL,
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

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN ref_id BIGINT DEFAULT NULL")
    void addRefIdColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD INDEX idx_emb_refid (source_type, ref_id)")
    void addRefIdIndexIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD UNIQUE KEY uk_emb_source (source_type, source_id)")
    void addSourceUniqueIndexIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN chunk_start INT DEFAULT NULL")
    void addChunkStartColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN embedding_model VARCHAR(128) DEFAULT NULL")
    void addEmbeddingModelColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN embedding_dim INT DEFAULT NULL")
    void addEmbeddingDimColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN embedding_profile VARCHAR(64) NOT NULL DEFAULT 'default'")
    void addEmbeddingProfileColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN content_hash CHAR(64) DEFAULT NULL")
    void addContentHashColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN index_status VARCHAR(24) NOT NULL DEFAULT 'READY'")
    void addIndexStatusColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD COLUMN last_embedded_at DATETIME DEFAULT NULL")
    void addLastEmbeddedAtColumnIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD INDEX idx_emb_profile (embedding_profile, embedding_model, embedding_dim, index_status)")
    void addProfileIndexIfNotExists();

    @Update("ALTER TABLE interpretation_embedding DROP INDEX uk_emb_source")
    void dropLegacySourceUniqueIndexIfExists();

    @Update("ALTER TABLE interpretation_embedding DROP INDEX uk_emb_result")
    void dropLegacyResultUniqueIndexIfExists();

    @Update("ALTER TABLE interpretation_embedding ADD UNIQUE KEY uk_emb_source_profile (source_type, source_id, embedding_profile)")
    void addSourceProfileUniqueIndexIfNotExists();

    @Update("ALTER TABLE interpretation_embedding ADD UNIQUE KEY uk_emb_result_profile (result_id, embedding_profile)")
    void addResultProfileUniqueIndexIfNotExists();

    // ── Inserts / Upserts ────────────────────────────────────────────────

    @Insert("""
            INSERT INTO interpretation_embedding
                (result_id, session_id, meeting_id, session_title, session_date,
                 speaker_name, chunk_text, translated_text, embedding_model, embedding_dim,
                 embedding_profile, content_hash, index_status, last_embedded_at, embedding, create_time)
            VALUES
                (#{resultId}, #{sessionId}, #{meetingId}, #{sessionTitle}, #{sessionDate},
                 #{speakerName}, #{chunkText}, #{translatedText}, #{embeddingModel}, #{embeddingDim},
                 #{embeddingProfile}, #{contentHash}, #{indexStatus}, NOW(), #{embedding}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterpretationEmbedding emb);

    /**
     * Upsert for non-result content types.
     * ON DUPLICATE KEY UPDATE so re-generated content (new summary text) replaces the stale vector.
     */
    @Insert("""
            INSERT INTO interpretation_embedding
                (source_type, source_id, ref_id, session_id, meeting_id, session_title, session_date,
                 speaker_name, chunk_text, translated_text, chunk_start, embedding_model, embedding_dim,
                 embedding_profile, content_hash, index_status, last_embedded_at, embedding, create_time)
            VALUES
                (#{sourceType}, #{sourceId}, #{refId}, #{sessionId}, #{meetingId}, #{sessionTitle}, #{sessionDate},
                 #{speakerName}, #{chunkText}, #{translatedText}, #{chunkStart}, #{embeddingModel}, #{embeddingDim},
                 #{embeddingProfile}, #{contentHash}, #{indexStatus}, NOW(), #{embedding}, NOW())
            ON DUPLICATE KEY UPDATE
                ref_id          = VALUES(ref_id),
                session_id      = VALUES(session_id),
                meeting_id      = VALUES(meeting_id),
                session_title   = VALUES(session_title),
                session_date    = VALUES(session_date),
                speaker_name    = VALUES(speaker_name),
                chunk_text      = VALUES(chunk_text),
                translated_text = VALUES(translated_text),
                chunk_start     = VALUES(chunk_start),
                embedding_model = VALUES(embedding_model),
                embedding_dim   = VALUES(embedding_dim),
                content_hash    = VALUES(content_hash),
                index_status    = VALUES(index_status),
                last_embedded_at = VALUES(last_embedded_at),
                embedding       = VALUES(embedding)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertContent(InterpretationEmbedding emb);

    // ── Dedup queries ────────────────────────────────────────────────────

    @Select("SELECT COUNT(*) FROM interpretation_embedding WHERE result_id = #{resultId} AND embedding_profile = #{embeddingProfile}")
    int countByResultId(@Param("resultId") long resultId, @Param("embeddingProfile") String embeddingProfile);

    @Select("SELECT COUNT(*) FROM interpretation_embedding WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND embedding_profile = #{embeddingProfile}")
    int countBySourceTypeAndId(@Param("sourceType") String sourceType, @Param("sourceId") long sourceId,
                               @Param("embeddingProfile") String embeddingProfile);

    // ── Deletes ──────────────────────────────────────────────────────────

    /** Remove all embeddings for a session (used when session is soft-deleted). */
    @Delete("DELETE FROM interpretation_embedding WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /** Remove embeddings by source type + ref_id (e.g., all chunks of a file, one action item). */
    @Delete("DELETE FROM interpretation_embedding WHERE source_type = #{sourceType} AND ref_id = #{refId}")
    int deleteBySourceTypeAndRefId(@Param("sourceType") String sourceType, @Param("refId") long refId);

    /** Remove regenerated content only for one embedding profile; old profiles remain for rollback. */
    @Delete("DELETE FROM interpretation_embedding WHERE source_type = #{sourceType} AND ref_id = #{refId} AND embedding_profile = #{embeddingProfile}")
    int deleteBySourceTypeAndRefIdAndProfile(@Param("sourceType") String sourceType, @Param("refId") long refId,
                                             @Param("embeddingProfile") String embeddingProfile);

    /** Remove all embeddings for a meeting (used when meeting is deleted). */
    @Delete("DELETE FROM interpretation_embedding WHERE meeting_id = #{meetingId}")
    int deleteByMeetingId(@Param("meetingId") long meetingId);

    /** Remove a single result embedding (used when individual result row is deleted). */
    @Delete("DELETE FROM interpretation_embedding WHERE result_id = #{resultId}")
    int deleteByResultId(@Param("resultId") long resultId);

    // ── Rebuild candidates ───────────────────────────────────────────────

    /** Sessions that have a meeting summary but no embedding for it yet. */
    List<EmbedCandidate> findSessionsWithSummaryWithoutEmbedding(@Param("limit") int limit,
                                                                 @Param("embeddingProfile") String embeddingProfile);

    /** Speaker summary rows without an embedding. */
    List<EmbedCandidate> findSpeakerSummariesWithoutEmbedding(@Param("limit") int limit,
                                                              @Param("embeddingProfile") String embeddingProfile);

    /** Pre-meeting files without a file_summary embedding. */
    List<EmbedCandidate> findFileSummariesWithoutEmbedding(@Param("limit") int limit,
                                                           @Param("embeddingProfile") String embeddingProfile);

    /** Pre-meeting files whose content has not been embedded at all. */
    List<EmbedCandidate> findFileContentsWithoutEmbedding(@Param("limit") int limit,
                                                          @Param("embeddingProfile") String embeddingProfile);

    /** Action items without an embedding. */
    List<EmbedCandidate> findActionItemsWithoutEmbedding(@Param("limit") int limit,
                                                         @Param("embeddingProfile") String embeddingProfile);

    // ── Existing ─────────────────────────────────────────────────────────

    /** Dynamic filter query — implemented in InterpretationEmbeddingMapper.xml */
    List<InterpretationEmbedding> findByUserId(
            @Param("userId") long userId,
            @Param("filterMeetingId") Long filterMeetingId,
            @Param("filterSpeakerName") String filterSpeakerName,
            @Param("filterSince") String filterSince,
            @Param("embeddingProfile") String embeddingProfile,
            @Param("candidateLimit") int candidateLimit);

    /** Returns rows in interpretation_result that have no embedding yet */
    List<EmbedCandidate> findResultsWithoutEmbedding(@Param("limit") int limit,
                                                     @Param("embeddingProfile") String embeddingProfile);
}
