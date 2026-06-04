package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.mapper.InterpretationSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-5 structured extraction: pull decisions / risks / metrics / topics out of a meeting transcript
 * with one LLM call, and embed each item as its own retrievable object (via {@link ContentEmbeddingService}).
 *
 * <p>This makes questions like "有哪些风险" / "一共几个决策" hit clean, single-topic items instead of
 * fishing them out of raw transcript chunks (which is where cross-meeting contamination came from).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingInsightService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ordered map of JSON key -> embedding source_type. */
    private static final Map<String, String> CATEGORIES = Map.of(
            "decisions", ContentEmbeddingService.TYPE_DECISION,
            "risks", ContentEmbeddingService.TYPE_RISK,
            "metrics", ContentEmbeddingService.TYPE_METRIC,
            "topics", ContentEmbeddingService.TYPE_TOPIC);

    private static final String INSIGHT_SYSTEM_PROMPT =
            "你是会议要点结构化抽取助手。从会议转写中抽取四类要点，只抽取转写中确实出现的内容，不要编造：\n"
            + "- decisions 决策/结论：明确做出的决定或得出的结论\n"
            + "- risks 风险：提到的风险、隐患、未达标或问题\n"
            + "- metrics 关键指标：带数字的关键数据（涨跌幅、数量、比例、金额等），尽量保留数字与对象\n"
            + "- topics 主题：讨论的主要议题/方向\n"
            + "每条简短独立、使用与转写相同的语言。只输出 JSON："
            + "{\"decisions\":[],\"risks\":[],\"metrics\":[],\"topics\":[]}，没有的类别给空数组，不要解释。";

    @Value("${rag.insight.enabled:true}")
    private boolean insightEnabled;
    @Value("${rag.insight.max-per-category:12}")
    private int maxPerCategory;

    private final InterpretationResultMapper resultMapper;
    private final InterpretationSessionMapper sessionMapper;
    private final LlmIntegration llmIntegration;
    private final ContentEmbeddingService contentEmbeddingService;
    private final OpenAiProperties openAiProperties;

    /** Extract structured insights for a session and embed them. Fail-soft: never throws. */
    public void extractAndEmbed(String sessionId, Long meetingId) {
        if (!insightEnabled || sessionId == null || sessionId.isBlank()) return;
        try {
            var results = resultMapper.findBySessionId(sessionId);
            if (results.isEmpty()) return;

            StringBuilder transcript = new StringBuilder();
            for (var r : results) {
                if (r.getSpeakerName() != null && !r.getSpeakerName().isBlank()) {
                    transcript.append(r.getSpeakerName()).append(": ");
                }
                transcript.append(r.getSourceText()).append('\n');
            }

            InterpretationSession session = sessionMapper.findBySessionId(sessionId);
            if (session == null) return;
            Long sessionPk = session.getId();
            String title = session.getTitle();
            LocalDate date = session.getStartTime() != null ? session.getStartTime().toLocalDate() : null;
            Long effMeetingId = meetingId != null ? meetingId : session.getMeetingId();

            String raw = llmIntegration.complete(openAiProperties.getDocumentSummaryModel(),
                    INSIGHT_SYSTEM_PROMPT, transcript.toString(), 900L);
            Map<String, List<String>> insights = parseInsights(raw, maxPerCategory);

            int total = 0;
            for (Map.Entry<String, String> cat : CATEGORIES.entrySet()) {
                List<String> items = insights.getOrDefault(cat.getKey(), List.of());
                if (!items.isEmpty()) {
                    contentEmbeddingService.asyncEmbedInsights(
                            cat.getValue(), sessionId, sessionPk, effMeetingId, title, date, items);
                    total += items.size();
                }
            }
            log.info("[MeetingInsightService] extractAndEmbed done, sessionId={}, total={}", sessionId, total);
        } catch (Exception e) {
            log.warn("[MeetingInsightService] extractAndEmbed failed, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Parse the LLM JSON into category -> list of items. Tolerant of code fences, missing keys, and
     * non-array values; each list is trimmed and capped at {@code maxPerCategory}.
     */
    static Map<String, List<String>> parseInsights(String raw, int maxPerCategory) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        String json = stripFences(raw).trim();
        try {
            JsonNode root = MAPPER.readTree(json);
            for (String key : CATEGORIES.keySet()) {
                JsonNode arr = root.get(key);
                if (arr == null || !arr.isArray()) continue;
                List<String> items = new ArrayList<>();
                for (JsonNode n : arr) {
                    String v = n.isTextual() ? n.asText() : n.toString();
                    if (v != null && !v.isBlank() && !"无".equals(v.trim())) {
                        items.add(v.trim());
                    }
                    if (items.size() >= maxPerCategory) break;
                }
                if (!items.isEmpty()) out.put(key, items);
            }
        } catch (Exception e) {
            log.warn("[MeetingInsightService] parseInsights failed: {}", e.getMessage());
        }
        return out;
    }

    private static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
