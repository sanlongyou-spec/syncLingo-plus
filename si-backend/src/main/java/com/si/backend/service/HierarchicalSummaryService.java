package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * P2-7 hierarchical summaries (RAPTOR-lite): roll the per-meeting summaries of a user up into a
 * single cross-meeting "overview" node (主要议题 / 整体趋势 / 共性风险), embedded as the aggregation
 * root. Global/aggregate questions ("整体趋势是什么" / "主要议题有哪些") can then hit this node
 * instead of scattered top-k chunks. Built offline via an admin endpoint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchicalSummaryService {

    @Value("${rag.hierarchical.enabled:true}")
    private boolean enabled;
    @Value("${rag.hierarchical.max-meetings:30}")
    private int maxMeetings;

    private static final String OVERVIEW_SYSTEM_PROMPT =
            "你是会议群体概览助手。给定同一用户的多场会议摘要，汇总成一份【跨会议概览】，包含：\n"
            + "1. 主要议题（跨会议反复出现或重要的主题）\n"
            + "2. 整体趋势与变化（按时间或主题归纳的走向）\n"
            + "3. 共性问题与风险\n"
            + "分点、保留关键数字，只基于提供的摘要，不要编造。用与摘要相同的语言。";

    private final InterpretationSessionMapper sessionMapper;
    private final LlmIntegration llmIntegration;
    private final ContentEmbeddingService contentEmbeddingService;
    private final OpenAiProperties openAiProperties;

    /** Build (or rebuild) the cross-meeting overview for a user. Returns the number of meeting
     *  summaries aggregated (0 = skipped). Fail-soft: never throws. */
    public int rebuildForUser(Long userId) {
        if (!enabled || userId == null) return 0;
        try {
            List<InterpretationSession> sessions = sessionMapper.findByUserId(userId);
            List<String> leaves = new ArrayList<>();
            Long linkageMeetingId = null;
            for (InterpretationSession s : sessions) {
                String summary = s.getMeetingSummary();
                if (summary == null || summary.isBlank()) continue;
                String title = s.getTitle() == null || s.getTitle().isBlank() ? "会议" : s.getTitle();
                leaves.add("【" + title + "】\n" + summary.trim());
                if (linkageMeetingId == null && s.getMeetingId() != null) {
                    linkageMeetingId = s.getMeetingId();
                }
                if (leaves.size() >= maxMeetings) break;
            }
            // Need >=2 summaries to aggregate, and a meeting to link the node to (for user-scoped retrieval).
            if (leaves.size() < 2 || linkageMeetingId == null) {
                log.info("[HierarchicalSummaryService] rebuildForUser skipped, userId={}, summaries={}",
                        userId, leaves.size());
                return 0;
            }
            String joined = String.join("\n\n---\n\n", leaves);
            String overview = llmIntegration.complete(openAiProperties.getDocumentSummaryModel(),
                    OVERVIEW_SYSTEM_PROMPT, joined, 1200L);
            if (overview == null || overview.isBlank()) return 0;
            contentEmbeddingService.asyncEmbedCrossSummary(userId, linkageMeetingId, overview);
            log.info("[HierarchicalSummaryService] rebuildForUser done, userId={}, aggregated={}", userId, leaves.size());
            return leaves.size();
        } catch (Exception e) {
            log.warn("[HierarchicalSummaryService] rebuildForUser failed, userId={}: {}", userId, e.getMessage());
            return 0;
        }
    }
}
