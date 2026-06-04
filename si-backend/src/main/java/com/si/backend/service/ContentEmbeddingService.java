package com.si.backend.service;

import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import com.si.backend.mapper.InterpretationSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** Chunking strategy: "smart" = sentence-aware + overlap (Phase 1); "fixed" = legacy fixed-length. */
    @Value("${rag.chunking.mode:smart}")
    private String chunkingMode;
    /** Target chunk size in chars for smart chunking. */
    @Value("${rag.chunking.target-size:300}")
    private int chunkTargetSize;
    /** Overlap in chars between adjacent smart chunks (~1 sentence). */
    @Value("${rag.chunking.overlap:60}")
    private int chunkOverlap;

    /** Contextual Retrieval (Phase 2B): prepend an LLM-generated situating context to each file chunk
     *  before embedding. Off by default (adds one LLM call per chunk at ingest). */
    @Value("${rag.contextual.enabled:false}")
    private boolean contextualEnabled;
    @Value("${rag.contextual.model:anthropic/claude-haiku-4.5}")
    private String contextualModel;
    /** Max chars of the surrounding document handed to the situate prompt (bounds token cost). */
    private static final int CONTEXTUAL_DOC_MAX = 4000;

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
                List<Chunk> chunks = buildChunks(fileContent);
                for (int i = 0; i < chunks.size(); i++) {
                    long sourceId = fileId * 1000L + i;
                    Chunk ck = chunks.get(i);
                    String chunkText = (i == 0 && !blank(fileName) ? fileName + "\n" : "") + ck.text();
                    upsert(TYPE_FILE_CONTENT, sourceId, fileId,
                            ctx.sessionId, meetingId, ctx.sessionTitle, ctx.sessionDate,
                            null, chunkText, null, ck.start(), situate(fileContent, chunkText));
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
                String fullText = c.getSourceText();
                List<Chunk> chunks = buildChunks(fullText);
                Long fileId = c.getSourceId();
                for (int i = 0; i < chunks.size(); i++) {
                    long sourceId = fileId * 1000L + i;
                    Chunk ck = chunks.get(i);
                    String chunkText = (i == 0 && !blank(c.getSessionTitle()) ? c.getSessionTitle() + "\n" : "") + ck.text();
                    upsert(TYPE_FILE_CONTENT, sourceId, fileId,
                            ctx.sessionId(), c.getMeetingId(), ctx.sessionTitle(), ctx.sessionDate(),
                            null, chunkText, null, ck.start(), situate(fullText, chunkText));
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
        upsert(sourceType, sourceId, refId, sessionId, meetingId, sessionTitle, sessionDate,
                speakerName, text, translatedText, null);
    }

    private void upsert(String sourceType, Long sourceId, Long refId,
                        String sessionId, Long meetingId,
                        String sessionTitle, LocalDate sessionDate,
                        String speakerName, String text, String translatedText,
                        Integer chunkStart) throws Exception {
        upsert(sourceType, sourceId, refId, sessionId, meetingId, sessionTitle, sessionDate,
                speakerName, text, translatedText, chunkStart, null);
    }

    /**
     * @param embedText optional alternate text to embed (Phase 2B contextual retrieval embeds the
     *                  situated text but stores the raw chunk in {@code chunk_text}). Null = embed {@code text}.
     */
    private void upsert(String sourceType, Long sourceId, Long refId,
                        String sessionId, Long meetingId,
                        String sessionTitle, LocalDate sessionDate,
                        String speakerName, String text, String translatedText,
                        Integer chunkStart, String embedText) throws Exception {
        if (blank(text)) return;
        String toEmbed = (embedText != null && !embedText.isBlank()) ? embedText : text;
        float[] vec = llmIntegration.embed(toEmbed);
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
        emb.setChunkStart(chunkStart);
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

    /**
     * Phase 2B Contextual Retrieval: ask an LLM for a one-sentence context that situates this chunk
     * within the whole document, and prepend it to the text we embed (improves dense recall by
     * restoring context lost to chunking). The stored chunk_text stays raw; only the embedded text
     * changes. Disabled by default; any failure falls back to the raw chunk.
     */
    private String situate(String fullDoc, String chunk) {
        if (!contextualEnabled || blank(chunk) || blank(fullDoc)) return chunk;
        try {
            String doc = fullDoc.length() > CONTEXTUAL_DOC_MAX ? fullDoc.substring(0, CONTEXTUAL_DOC_MAX) : fullDoc;
            String system = "你是检索增强助手。请用一句话概括下面这段文字在整篇文档中的语境"
                    + "（属于哪部分、在讲什么），只输出这句简短语境，使用与文段相同的语言，不要加引号或解释。";
            String user = "<文档>\n" + doc + "\n</文档>\n\n<文段>\n" + chunk + "\n</文段>\n\n请给出这段文段的简短语境：";
            String ctx = llmIntegration.complete(contextualModel, system, user, 120);
            if (ctx == null || ctx.isBlank()) return chunk;
            return ctx.trim() + "\n" + chunk;
        } catch (Exception e) {
            log.warn("[ContentEmbeddingService] situate failed (fallback to raw chunk): {}", e.getMessage());
            return chunk;
        }
    }

    /** A chunk plus its start offset in the original source text. */
    record Chunk(String text, int start) {}

    /** Build chunks honoring the configured mode (smart sentence-aware vs legacy fixed-length). */
    List<Chunk> buildChunks(String text) {
        if (text == null || text.isEmpty()) return List.of();
        if ("fixed".equalsIgnoreCase(chunkingMode)) {
            List<Chunk> out = new ArrayList<>();
            for (int i = 0; i < text.length(); i += FILE_CONTENT_CHUNK_SIZE) {
                out.add(new Chunk(text.substring(i, Math.min(i + FILE_CONTENT_CHUNK_SIZE, text.length())), i));
            }
            return out;
        }
        return chunkSmart(text, chunkTargetSize, chunkOverlap);
    }

    /**
     * Sentence-aware chunking with overlap. Packs whole sentences up to {@code targetSize}, and starts
     * the next chunk a little earlier so adjacent chunks overlap by ~{@code overlapChars} (keeps a
     * concept that straddles a boundary whole in at least one chunk). Each chunk carries its start
     * offset in the original text for later Small-to-Big window expansion.
     */
    static List<Chunk> chunkSmart(String text, int targetSize, int overlapChars) {
        List<int[]> sents = splitSentences(text);
        List<Chunk> out = new ArrayList<>();
        int n = sents.size();
        int i = 0;
        while (i < n) {
            int start = sents.get(i)[0];
            int end = sents.get(i)[1];
            int j = i + 1;
            while (j < n && sents.get(j)[1] - start <= targetSize) {
                end = sents.get(j)[1];
                j++;
            }
            out.add(new Chunk(text.substring(start, end), start));
            if (j >= n) break;
            // Step back so the next chunk overlaps the tail of this one. Re-include the last
            // sentence (guaranteed overlap), then keep including earlier ones until the overlapped
            // span reaches ~overlapChars — while always leaving at least one sentence of progress.
            int next = j;
            if (overlapChars > 0) {
                next = j - 1;
                while (next > i + 1 && end - sents.get(next - 1)[0] < overlapChars) {
                    next--;
                }
            }
            i = Math.max(i + 1, next);
        }
        return out;
    }

    /**
     * Split text into contiguous sentence spans [start,end). Breaks on CJK/Latin sentence enders and
     * newlines; a Latin '.' only ends a sentence when it is not inside a decimal number and is
     * followed by whitespace/end. Trailing whitespace is attached to the preceding sentence.
     */
    static List<int[]> splitSentences(String text) {
        List<int[]> sents = new ArrayList<>();
        int n = text.length();
        int start = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean ender = c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n';
            if (!ender && c == '.') {
                boolean prevDigit = i > 0 && Character.isDigit(text.charAt(i - 1));
                boolean nextDigit = i + 1 < n && Character.isDigit(text.charAt(i + 1));
                boolean nextSpaceOrEnd = i + 1 >= n || Character.isWhitespace(text.charAt(i + 1));
                if (!(prevDigit && nextDigit) && nextSpaceOrEnd) ender = true;
            }
            if (ender) {
                int end = i + 1;
                while (end < n && Character.isWhitespace(text.charAt(end))) end++;
                if (end > start) sents.add(new int[]{start, end});
                start = end;
                i = end - 1;
            }
        }
        if (start < n) sents.add(new int[]{start, n});
        return sents;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private record SessionContext(String sessionId, String sessionTitle, LocalDate sessionDate) {}
}
