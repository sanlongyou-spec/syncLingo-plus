package com.si.backend.service;

import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.MeetingActionItem;
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
 * Async embedding service for all non-transcription meeting content.
 * Also provides synchronous delete helpers called from deletion hooks.
 *
 * Content types:
 *   meeting_summary  — AI-generated meeting minutes
 *   speaker_summary  — per-speaker summary
 *   file_summary     — LLM summary of an uploaded file
 *   file_content     — raw extracted text of an uploaded file (chunked)
 *   action_item      — action item extracted from a session
 *
 * Each embed* method is fire-and-forget. Errors are logged, never rethrown.
 * Delete methods are synchronous so callers know when embeddings are gone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentEmbeddingService {

    public static final String TYPE_MEETING_SUMMARY = "meeting_summary";
    public static final String TYPE_SPEAKER_SUMMARY = "speaker_summary";
    public static final String TYPE_FILE_SUMMARY    = "file_summary";
    public static final String TYPE_FILE_CONTENT    = "file_content";
    public static final String TYPE_ACTION_ITEM     = "action_item";

    private static final int FILE_CONTENT_CHUNK_SIZE = 500;

    private final LlmIntegration llmIntegration;
    private final InterpretationEmbeddingMapper embeddingMapper;
    private final InterpretationSessionMapper sessionMapper;

    // ── Embed triggers ────────────────────────────────────────────────────

    /** After meeting summary saved/regenerated. source_id = ref_id = session PK. */
    public void asyncEmbedMeetingSummary(String sessionId, Long sessionPk, String summaryText) {
        if (blank(summaryText)) return;
        CompletableFuture.runAsync(() -> {
            try {
                InterpretationSession s = sessionMapper.findBySessionId(sessionId);
                upsert(TYPE_MEETING_SUMMARY, sessionPk, sessionPk,
                        sessionId,
                        s != null ? s.getMeetingId() : null,
                        s != null ? s.getTitle() : null,
                        dateOf(s),
                        null, summaryText, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedMeetingSummary failed, sessionId={}: {}", sessionId, e.getMessage());
            }
        });
    }

    /** After speaker summary inserted/regenerated. source_id = ref_id = speaker_summary.id. */
    public void asyncEmbedSpeakerSummary(Long summaryId, String sessionId,
                                         String speakerName, String title, String summaryText) {
        if (blank(summaryText)) return;
        CompletableFuture.runAsync(() -> {
            try {
                InterpretationSession s = sessionMapper.findBySessionId(sessionId);
                String combined = (blank(title) ? "" : title + "\n") + summaryText;
                upsert(TYPE_SPEAKER_SUMMARY, summaryId, summaryId,
                        sessionId,
                        s != null ? s.getMeetingId() : null,
                        s != null ? s.getTitle() : null,
                        dateOf(s),
                        speakerName, combined, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedSpeakerSummary failed, summaryId={}: {}", summaryId, e.getMessage());
            }
        });
    }

    /** After file summary saved. source_id = ref_id = fileId. */
    public void asyncEmbedFileSummary(Long fileId, Long meetingId, String fileName, String summaryText) {
        if (blank(summaryText)) return;
        CompletableFuture.runAsync(() -> {
            try {
                SessionContext ctx = resolveSession(meetingId);
                upsert(TYPE_FILE_SUMMARY, fileId, fileId,
                        ctx.sessionId, meetingId, ctx.sessionTitle, ctx.sessionDate,
                        null, (blank(fileName) ? "" : fileName + "\n") + summaryText, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedFileSummary failed, fileId={}: {}", fileId, e.getMessage());
            }
        });
    }

    /** After file uploaded. Chunks long text. source_id = fileId*1000+chunk, ref_id = fileId. */
    public void asyncEmbedFileContent(Long fileId, Long meetingId, String fileName, String fileContent) {
        if (blank(fileContent)) return;
        CompletableFuture.runAsync(() -> {
            try {
                SessionContext ctx = resolveSession(meetingId);
                List<String> chunks = chunk(fileContent, FILE_CONTENT_CHUNK_SIZE);
                for (int i = 0; i < chunks.size(); i++) {
                    long sourceId = fileId * 1000L + i;
                    String chunkText = (i == 0 && !blank(fileName) ? fileName + "\n" : "") + chunks.get(i);
                    upsert(TYPE_FILE_CONTENT, sourceId, fileId,
                            ctx.sessionId, meetingId, ctx.sessionTitle, ctx.sessionDate,
                            null, chunkText, null);
                }
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedFileContent failed, fileId={}: {}", fileId, e.getMessage());
            }
        });
    }

    /** After action item saved/updated. source_id = ref_id = action_item.id. */
    public void asyncEmbedActionItem(MeetingActionItem item) {
        if (item == null || blank(item.getContent())) return;
        CompletableFuture.runAsync(() -> {
            try {
                InterpretationSession s = sessionMapper.findBySessionId(item.getSessionId());
                String text = formatActionItem(item);
                upsert(TYPE_ACTION_ITEM, item.getId(), item.getId(),
                        item.getSessionId(),
                        item.getMeetingId() != null ? item.getMeetingId() : (s != null ? s.getMeetingId() : null),
                        s != null ? s.getTitle() : null,
                        dateOf(s),
                        item.getAssignee(), text, null);
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] asyncEmbedActionItem failed, itemId={}: {}", item.getId(), e.getMessage());
            }
        });
    }

    // ── Synchronous deletes ───────────────────────────────────────────────

    /** Delete all embeddings for a session (call before/after soft-delete). */
    public void deleteBySessionId(String sessionId) {
        if (blank(sessionId)) return;
        try {
            int rows = embeddingMapper.deleteBySessionId(sessionId);
            log.info("[ContentEmbeddingService] deleteBySessionId, sessionId={}, rows={}", sessionId, rows);
        } catch (Exception e) {
            log.warn("[ContentEmbeddingService] deleteBySessionId failed, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /** Delete embeddings for a specific content type + entity (e.g., file, action item). */
    public void deleteByTypeAndRefId(String sourceType, Long refId) {
        if (blank(sourceType) || refId == null) return;
        try {
            int rows = embeddingMapper.deleteBySourceTypeAndRefId(sourceType, refId);
            log.info("[ContentEmbeddingService] deleteByTypeAndRefId, type={}, refId={}, rows={}", sourceType, refId, rows);
        } catch (Exception e) {
            log.warn("[ContentEmbeddingService] deleteByTypeAndRefId failed, type={}, refId={}: {}", sourceType, refId, e.getMessage());
        }
    }

    /** Delete all embeddings linked to a meeting (call when meeting is deleted). */
    public void deleteByMeetingId(Long meetingId) {
        if (meetingId == null) return;
        try {
            int rows = embeddingMapper.deleteByMeetingId(meetingId);
            log.info("[ContentEmbeddingService] deleteByMeetingId, meetingId={}, rows={}", meetingId, rows);
        } catch (Exception e) {
            log.warn("[ContentEmbeddingService] deleteByMeetingId failed, meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    // ── Synchronous batch rebuild (for admin endpoint) ────────────────────

    /**
     * Embed a batch of unembedded content across all non-result types.
     * Returns the total number of embeddings created.
     */
    public int rebuildBatch(int batchPerType) {
        int total = 0;
        total += rebuildMeetingSummaries(batchPerType);
        total += rebuildSpeakerSummaries(batchPerType);
        total += rebuildFileSummaries(batchPerType);
        total += rebuildFileContents(batchPerType);
        total += rebuildActionItems(batchPerType);
        return total;
    }

    private int rebuildMeetingSummaries(int limit) {
        var candidates = embeddingMapper.findSessionsWithSummaryWithoutEmbedding(limit);
        int count = 0;
        for (var c : candidates) {
            try {
                InterpretationSession s = sessionMapper.findBySessionId(c.getSessionId());
                if (s == null || s.getId() == null) continue;
                upsert(TYPE_MEETING_SUMMARY, s.getId(), s.getId(),
                        c.getSessionId(), c.getMeetingId(), c.getSessionTitle(), dateOf(s),
                        null, c.getSourceText(), null);
                count++;
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] rebuildMeetingSummary failed: {}", e.getMessage());
            }
        }
        log.info("[ContentEmbeddingService] rebuildMeetingSummaries done, created={}", count);
        return count;
    }

    private int rebuildSpeakerSummaries(int limit) {
        var candidates = embeddingMapper.findSpeakerSummariesWithoutEmbedding(limit);
        int count = 0;
        for (var c : candidates) {
            try {
                InterpretationSession s = sessionMapper.findBySessionId(c.getSessionId());
                String combined = (blank(c.getSessionTitle()) ? "" : c.getSessionTitle() + "\n") + c.getSourceText();
                upsert(TYPE_SPEAKER_SUMMARY, c.getSourceId(), c.getSourceId(),
                        c.getSessionId(), c.getMeetingId(),
                        s != null ? s.getTitle() : null, dateOf(s),
                        c.getSpeakerName(), combined, null);
                count++;
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] rebuildSpeakerSummary failed: {}", e.getMessage());
            }
        }
        log.info("[ContentEmbeddingService] rebuildSpeakerSummaries done, created={}", count);
        return count;
    }

    private int rebuildFileSummaries(int limit) {
        var candidates = embeddingMapper.findFileSummariesWithoutEmbedding(limit);
        int count = 0;
        for (var c : candidates) {
            try {
                SessionContext ctx = resolveSession(c.getMeetingId());
                String text = (blank(c.getSessionTitle()) ? "" : c.getSessionTitle() + "\n") + c.getSourceText();
                upsert(TYPE_FILE_SUMMARY, c.getSourceId(), c.getSourceId(),
                        ctx.sessionId(), c.getMeetingId(), ctx.sessionTitle(), ctx.sessionDate(),
                        null, text, null);
                count++;
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] rebuildFileSummary failed: {}", e.getMessage());
            }
        }
        log.info("[ContentEmbeddingService] rebuildFileSummaries done, created={}", count);
        return count;
    }

    private int rebuildFileContents(int limit) {
        var candidates = embeddingMapper.findFileContentsWithoutEmbedding(limit);
        int count = 0;
        for (var c : candidates) {
            try {
                SessionContext ctx = resolveSession(c.getMeetingId());
                List<String> chunks = chunk(c.getSourceText(), FILE_CONTENT_CHUNK_SIZE);
                Long fileId = c.getSourceId();
                for (int i = 0; i < chunks.size(); i++) {
                    long sourceId = fileId * 1000L + i;
                    String chunkText = (i == 0 && !blank(c.getSessionTitle()) ? c.getSessionTitle() + "\n" : "") + chunks.get(i);
                    upsert(TYPE_FILE_CONTENT, sourceId, fileId,
                            ctx.sessionId(), c.getMeetingId(), ctx.sessionTitle(), ctx.sessionDate(),
                            null, chunkText, null);
                    count++;
                }
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] rebuildFileContent failed: {}", e.getMessage());
            }
        }
        log.info("[ContentEmbeddingService] rebuildFileContents done, created={}", count);
        return count;
    }

    private int rebuildActionItems(int limit) {
        var candidates = embeddingMapper.findActionItemsWithoutEmbedding(limit);
        int count = 0;
        for (var c : candidates) {
            try {
                InterpretationSession s = sessionMapper.findBySessionId(c.getSessionId());
                StringBuilder text = new StringBuilder();
                if (!blank(c.getSpeakerName())) text.append("【").append(c.getSpeakerName()).append("】");
                text.append(c.getSourceText());
                upsert(TYPE_ACTION_ITEM, c.getSourceId(), c.getSourceId(),
                        c.getSessionId(), c.getMeetingId(),
                        s != null ? s.getTitle() : null, dateOf(s),
                        c.getSpeakerName(), text.toString(), null);
                count++;
            } catch (Exception e) {
                log.warn("[ContentEmbeddingService] rebuildActionItem failed: {}", e.getMessage());
            }
        }
        log.info("[ContentEmbeddingService] rebuildActionItems done, created={}", count);
        return count;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void upsert(String sourceType, Long sourceId, Long refId,
                        String sessionId, Long meetingId,
                        String sessionTitle, LocalDate sessionDate,
                        String speakerName, String text, String translatedText) throws Exception {
        if (blank(text)) return;
        float[] vec = llmIntegration.embed(text);
        if (vec.length == 0) return;

        InterpretationEmbedding emb = new InterpretationEmbedding();
        emb.setSourceType(sourceType);
        emb.setSourceId(sourceId);
        emb.setRefId(refId);
        emb.setSessionId(sessionId != null ? sessionId : "");
        emb.setMeetingId(meetingId);
        emb.setSessionTitle(sessionTitle);
        emb.setSessionDate(sessionDate);
        emb.setSpeakerName(speakerName);
        emb.setChunkText(text);
        emb.setTranslatedText(translatedText);
        emb.setEmbedding(VectorSearchService.toBytes(vec));
        embeddingMapper.upsertContent(emb);
        log.debug("[ContentEmbeddingService] upserted type={}, sourceId={}", sourceType, sourceId);
    }

    private SessionContext resolveSession(Long meetingId) {
        if (meetingId == null) return new SessionContext(null, null, null);
        List<InterpretationSession> sessions = sessionMapper.findByMeetingId(meetingId);
        if (sessions.isEmpty()) return new SessionContext(null, null, null);
        InterpretationSession s = sessions.get(0);
        return new SessionContext(s.getSessionId(), s.getTitle(), dateOf(s));
    }

    private static LocalDate dateOf(InterpretationSession s) {
        return s != null && s.getStartTime() != null ? s.getStartTime().toLocalDate() : null;
    }

    private static String formatActionItem(MeetingActionItem item) {
        StringBuilder sb = new StringBuilder();
        if (!blank(item.getAssignee())) sb.append("【").append(item.getAssignee()).append("】");
        sb.append(item.getContent());
        if (!blank(item.getDeadline())) sb.append("（期限：").append(item.getDeadline()).append("）");
        return sb.toString();
    }

    private static List<String> chunk(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return chunks;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private record SessionContext(String sessionId, String sessionTitle, LocalDate sessionDate) {}
}
