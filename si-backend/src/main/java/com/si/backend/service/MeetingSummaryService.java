package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.vo.InterpretationResultItemVo;
import com.si.backend.vo.MeetingSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会议纪要服务：会议结束后自动生成，结果持久化到 meeting_summary 字段。
 * <p>
 * GET  /api/summary/{sessionId}  — 返回已缓存纪要，无则实时生成并缓存
 * POST /api/summary/{sessionId}  — 强制重新生成并覆盖缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingSummaryService {

    private final InterpretationResultService resultService;
    private final LlmIntegration llmIntegration;
    private final InterpretationSessionService sessionService;

    /**
     * 获取纪要（优先返回 DB 缓存，无缓存则实时生成并持久化）。
     */
    public MeetingSummaryVo getSummary(String sessionId) {
        log.info("[MeetingSummaryService] getSummary start, sessionId={}", sessionId);
        InterpretationSession session = sessionService.getSession(sessionId).orElse(null);
        if (session != null
                && session.getMeetingSummary() != null
                && !session.getMeetingSummary().isBlank()) {
            log.info("[MeetingSummaryService] getSummary hit cache, sessionId={}", sessionId);
            return MeetingSummaryVo.builder()
                    .sessionId(sessionId)
                    .title(session.getTitle())
                    .summary(session.getMeetingSummary())
                    .recordCount(null)
                    .build();
        }
        return generateAndSave(sessionId);
    }

    /**
     * 强制重新生成纪要并覆盖缓存。
     */
    public MeetingSummaryVo regenerateSummary(String sessionId, String customRequirements) {
        log.info("[MeetingSummaryService] regenerateSummary start, sessionId={}, hasCustomRequirements={}",
                sessionId, customRequirements != null && !customRequirements.isBlank());
        return generateAndSave(sessionId, customRequirements);
    }

    /**
     * 会议停止后由 InterpretationFacade 异步调用；错误只记录日志，不抛出。
     */
    public void generateAndSaveAsync(String sessionId) {
        log.info("[MeetingSummaryService] generateAndSaveAsync start, sessionId={}", sessionId);
        try {
            generateAndSave(sessionId);
            log.info("[MeetingSummaryService] generateAndSaveAsync done, sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[MeetingSummaryService] generateAndSaveAsync failed, sessionId={} — {}", sessionId, e.getMessage());
        }
    }

    // ── private ──────────────────────────────────────────────────────────

    private MeetingSummaryVo generateAndSave(String sessionId) {
        return generateAndSave(sessionId, null);
    }

    private MeetingSummaryVo generateAndSave(String sessionId, String customRequirements) {
        InterpretationSession session = sessionService.getSession(sessionId).orElse(null);
        List<InterpretationResultItemVo> results = resultService.listBySessionId(sessionId);
        if (results.isEmpty()) {
            log.warn("[MeetingSummaryService] generateAndSave empty results, sessionId={}", sessionId);
            throw BizException.of(ErrorCode.NOT_FOUND, "该会话没有可生成纪要的同传记录");
        }

        String meetingText = results.stream()
                .map(r -> String.format("[%s→%s] %s => %s",
                        r.getSourceLang(), r.getTargetLang(),
                        r.getSourceText(), r.getTranslatedText()))
                .collect(Collectors.joining("\n"));

        try {
            String summary = llmIntegration.summarizeMeeting(meetingText, customRequirements);
            sessionService.addLlmTokens(sessionId, estimateTokens(meetingText), estimateTokens(summary));
            sessionService.saveMeetingSummary(sessionId, summary);
            log.info("[MeetingSummaryService] generateAndSave done, sessionId={}, resultCount={}, summaryLen={}",
                    sessionId, results.size(), summary.length());
            return MeetingSummaryVo.builder()
                    .sessionId(sessionId)
                    .title(session != null ? session.getTitle() : null)
                    .summary(summary)
                    .recordCount(results.size())
                    .build();
        } catch (IOException e) {
            log.error("[MeetingSummaryService] generateAndSave LLM failed, sessionId={}", sessionId, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "会议纪要生成失败: " + e.getMessage());
        }
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0L;
        return Math.max(1L, Math.round(text.length() / 2.0));
    }
}
