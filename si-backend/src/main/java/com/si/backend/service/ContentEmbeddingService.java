package com.si.backend.service;

import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import com.si.backend.mapper.InterpretationSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async embedding service for non-transcription meeting content:
 * meeting summaries, speaker summaries, pre-meeting file summaries and raw file text.
 *
 * Each public method is fire-and-forget (CompletableFuture.runAsync). Errors are
 * only logged so they never break the caller's main flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentEmbeddingService {

    private static final String TYPE_MEETING_SUMMARY  = "meeting_summary";
    private static final String TYPE_SPEAKER_SUMMARY  = "speaker_summary";
    private static final String TYPE_FILE_SUMMARY     = "file_summary";
    private static final String TYPE_FILE_CONTENT     = "file_content";

    private static final int FILE_CONTENT_CHUNK_SIZE = 500;

    private final LlmIntegration llmIntegration;
    private final InterpretationEmbeddingMapper embeddingMapper;
    private final InterpretationSessionMapper sessionMapper;

    // ── Public async triggers ────────────────────────────────────────────

    /** Called after a meeting summary is generated and saved. source_id = session PK (Long). */
    public void asyncEmbedMeetingSummary(String sessionId, Long sessionPk, String summaryText) {
        if (isBlank(summaryText)) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (embeddingMapper.countBySourceTypeAndId(TYPE_MEETING_SUMMARY, sessionPk) > 0) return;
                InterpretationSession s = sessionMapper.findBySessionId(sessionId);
                embedSingle(TYPE_MEETING_SUMMARY, sessionPk, sessionId,
                        s != null ? s.getMeetingId() : null,
                        s != null ? s.getTitle() : null,
                        s != null && s.getStartTime() != null ? s.getStartTime().toLocalDate() : null,
                        null, summaryText, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedMeetingSummary failed, sessionId={}: {}", sessionId, e.getMessage());
            }
        });
    }

    /** Called after a speaker summary is inserted. source_id = speaker_summary.id. */
    public void asyncEmbedSpeakerSummary(Long summaryId, String sessionId,
                                         String speakerName, String title, String summaryText) {
        if (isBlank(summaryText)) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (embeddingMapper.countBySourceTypeAndId(TYPE_SPEAKER_SUMMARY, summaryId) > 0) return;
                InterpretationSession s = sessionMapper.findBySessionId(sessionId);
                String combined = (isBlank(title) ? "" : title + "\n") + summaryText;
                embedSingle(TYPE_SPEAKER_SUMMARY, summaryId, sessionId,
                        s != null ? s.getMeetingId() : null,
                        s != null ? s.getTitle() : null,
                        s != null && s.getStartTime() != null ? s.getStartTime().toLocalDate() : null,
                        speakerName, combined, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedSpeakerSummary failed, summaryId={}: {}", summaryId, e.getMessage());
            }
        });
    }

    /** Called after a file summary is saved. source_id = pre_meeting_file_persistent.id. */
    public void asyncEmbedFileSummary(Long fileId, Long meetingId, String fileName, String summaryText) {
        if (isBlank(summaryText)) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (embeddingMapper.countBySourceTypeAndId(TYPE_FILE_SUMMARY, fileId) > 0) return;
                SessionContext ctx = resolveSessionForMeeting(meetingId);
                embedSingle(TYPE_FILE_SUMMARY, fileId, ctx.sessionId, meetingId,
                        ctx.sessionTitle, ctx.sessionDate,
                        null, fileName + "\n" + summaryText, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedFileSummary failed, fileId={}: {}", fileId, e.getMessage());
            }
        });
    }

    /** Called after a file is uploaded. Chunks long text. source_id = fileId * 1000 + chunkIndex. */
    public void asyncEmbedFileContent(Long fileId, Long meetingId, String fileName, String fileContent) {
        if (isBlank(fileContent)) return;
        CompletableFuture.runAsync(() -> {
            try {
                SessionContext ctx = resolveSessionForMeeting(meetingId);
                List<String> chunks = chunk(fileContent, FILE_CONTENT_CHUNK_SIZE);
                for (int i = 0; i < chunks.size(); i++) {
                    long chunkSourceId = fileId * 1000L + i;
                    if (embeddingMapper.countBySourceTypeAndId(TYPE_FILE_CONTENT, chunkSourceId) > 0) continue;
                    String chunkText = (i == 0 ? fileName + "\n" : "") + chunks.get(i);
                    embedSingle(TYPE_FILE_CONTENT, chunkSourceId, ctx.sessionId, meetingId,
                            ctx.sessionTitle, ctx.sessionDate,
                            null, chunkText, null);
                }
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedFileContent failed, fileId={}: {}", fileId, e.getMessage());
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void embedSingle(String sourceType, Long sourceId, String sessionId,
                             Long meetingId, String sessionTitle, LocalDate sessionDate,
                             String speakerName, String text, String translatedText) throws Exception {
        if (isBlank(text)) return;
        float[] vec = llmIntegration.embed(text);
        if (vec.length == 0) return;

        InterpretationEmbedding emb = new InterpretationEmbedding();
        emb.setSourceType(sourceType);
        emb.setSourceId(sourceId);
        emb.setSessionId(sessionId != null ? sessionId : "");
        emb.setMeetingId(meetingId);
        emb.setSessionTitle(sessionTitle);
        emb.setSessionDate(sessionDate);
        emb.setSpeakerName(speakerName);
        emb.setChunkText(text);
        emb.setTranslatedText(translatedText);
        emb.setEmbedding(VectorSearchService.toBytes(vec));
        embeddingMapper.insertContent(emb);
        log.debug("[ContentEmbeddingService] embedded type={}, sourceId={}, dims={}", sourceType, sourceId, vec.length);
    }

    private SessionContext resolveSessionForMeeting(Long meetingId) {
        if (meetingId == null) return new SessionContext(null, null, null);
        List<InterpretationSession> sessions = sessionMapper.findByMeetingId(meetingId);
        if (sessions.isEmpty()) return new SessionContext(null, null, null);
        InterpretationSession s = sessions.get(0);
        return new SessionContext(
                s.getSessionId(),
                s.getTitle(),
                s.getStartTime() != null ? s.getStartTime().toLocalDate() : null);
    }

    private static List<String> chunk(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return chunks;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record SessionContext(String sessionId, String sessionTitle, LocalDate sessionDate) {}
}
