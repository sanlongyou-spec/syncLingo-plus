package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.config.OpenAiProperties;
import com.si.backend.dto.PreMeetingChatRequest.ChatTurn;
import com.si.backend.integration.LlmIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG quality helpers (P0): multi-query expansion and LLM reranking.
 *
 * <p>Both are gated by {@code openai.rag-*} flags and fail open — if the LLM call or parsing
 * fails, the original query / order is returned, so retrieval never breaks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEnhancementService {

    private static final int RERANK_SNIPPET_MAX_CHARS = 240;
    private static final int RERANK_MAX_CANDIDATES = 40;

    private final LlmIntegration llmIntegration;
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;

    /** P0-3 history-aware query rewrite (resolve pronouns/ellipsis using recent turns). */
    @Value("${rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;
    @Value("${rag.query-rewrite.history-turns:5}")
    private int rewriteHistoryTurns;
    /** P1-4 multi-hop / comparison query decomposition. */
    @Value("${rag.decompose.enabled:true}")
    private boolean decomposeEnabled;
    @Value("${rag.decompose.max:3}")
    private int decomposeMax;

    private static final String REWRITE_SYSTEM_PROMPT =
            "你是检索查询改写助手。给定最近的对话和用户的最新问题，把最新问题改写成一个【自包含】的检索查询："
            + "补全其中的指代（它/那个/他们/这家等）和省略，使其脱离对话也能独立理解。"
            + "只输出改写后的查询本身，保持与原问题相同的语言，不要解释、不要加引号。";

    private static final String DECOMPOSE_SYSTEM_PROMPT =
            "你是检索问题分解助手。如果用户问题包含多个子问题、需要对比多个对象、或需要多步信息，"
            + "把它拆成若干个可独立检索的子问题；如果本身就是单一简单问题，则原样返回。"
            + "只输出一个 JSON 字符串数组，保持与原问题相同的语言，例如：[\"子问题1\",\"子问题2\"]。";

    private static final String EXPANSION_SYSTEM_PROMPT =
            "你是会议检索查询改写助手。给定用户问题，生成若干个语义等价或互补的检索查询，"
            + "覆盖同义词、上位词、可能的表述方式，帮助向量检索召回更全。"
            + "只输出一个 JSON 字符串数组，不要解释，例如：[\"查询1\",\"查询2\"]。";

    private static final String RERANK_SYSTEM_PROMPT =
            "你是检索结果重排序助手。根据用户问题，从候选片段中挑出最相关的若干条，"
            + "按相关性从高到低排序。只输出被选中片段编号的 JSON 数组（数字），不要解释，例如：[3,0,5]。";

    /**
     * Returns the original question plus a few reformulations (deduplicated). When expansion is
     * disabled or fails, returns just the original question.
     */
    public List<String> expandQueries(String question) {
        List<String> result = new ArrayList<>();
        if (question != null && !question.isBlank()) {
            result.add(question.trim());
        }
        if (!openAiProperties.isRagQueryExpansionEnabled() || question == null || question.isBlank()) {
            return result;
        }
        int want = Math.max(1, openAiProperties.getRagQueryExpansionCount());
        try {
            String user = "问题：" + question + "\n生成 " + want + " 个检索查询。";
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    EXPANSION_SYSTEM_PROMPT, user, 300L);
            for (String q : parseStringArray(raw)) {
                String trimmed = q.trim();
                if (!trimmed.isBlank() && result.stream().noneMatch(trimmed::equalsIgnoreCase)) {
                    result.add(trimmed);
                }
                if (result.size() >= want + 1) break;
            }
            log.info("[RagEnhancementService] expandQueries done, original=1, total={}", result.size());
        } catch (Exception e) {
            log.warn("[RagEnhancementService] expandQueries failed, using original only: {}", e.getMessage());
        }
        return result;
    }

    /**
     * P0-3: rewrite the latest question into a self-contained query using recent chat history
     * (resolve pronouns / ellipsis). Returns the original question when disabled, no history, or on
     * any failure.
     */
    public String rewriteQuery(List<ChatTurn> history, String question) {
        if (!queryRewriteEnabled || question == null || question.isBlank()
                || history == null || history.isEmpty()) {
            return question;
        }
        try {
            StringBuilder sb = new StringBuilder("最近对话：\n");
            int from = Math.max(0, history.size() - rewriteHistoryTurns);
            for (int i = from; i < history.size(); i++) {
                ChatTurn t = history.get(i);
                sb.append("user".equals(t.getRole()) ? "用户：" : "助手：")
                        .append(t.getContent() == null ? "" : t.getContent().trim()).append('\n');
            }
            sb.append("最新问题：").append(question).append("\n改写为自包含查询：");
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    REWRITE_SYSTEM_PROMPT, sb.toString(), 200L);
            String rewritten = stripFences(raw).trim();
            if (rewritten.isBlank()) return question;
            log.info("[RagEnhancementService] rewriteQuery done, len {}->{}", question.length(), rewritten.length());
            return rewritten;
        } catch (Exception e) {
            log.warn("[RagEnhancementService] rewriteQuery failed, using original: {}", e.getMessage());
            return question;
        }
    }

    /**
     * P1-4: decompose a multi-hop / comparison question into independently-retrievable sub-questions.
     * Always includes the original question. Returns just the original when disabled, simple, or on
     * failure.
     */
    public List<String> decompose(String question) {
        List<String> result = new ArrayList<>();
        if (question != null && !question.isBlank()) {
            result.add(question.trim());
        }
        if (!decomposeEnabled || question == null || question.isBlank()) {
            return result;
        }
        try {
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    DECOMPOSE_SYSTEM_PROMPT, "问题：" + question, 300L);
            for (String sub : parseStringArray(raw)) {
                String trimmed = sub.trim();
                if (!trimmed.isBlank() && result.stream().noneMatch(trimmed::equalsIgnoreCase)) {
                    result.add(trimmed);
                }
                if (result.size() >= decomposeMax + 1) break;
            }
            if (result.size() > 1) {
                log.info("[RagEnhancementService] decompose done, sub-questions={}", result.size() - 1);
            }
        } catch (Exception e) {
            log.warn("[RagEnhancementService] decompose failed, using original only: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Reranks recalled chunks by LLM relevance and keeps the top {@code ragRerankTopK}. When rerank
     * is disabled or fails, returns the input unchanged.
     */
    public List<VectorSearchService.SearchResult> rerank(
            String question, List<VectorSearchService.SearchResult> hits) {
        if (!openAiProperties.isRagRerankEnabled() || hits == null || hits.size() <= 1) {
            return hits;
        }
        int topK = Math.max(1, openAiProperties.getRagRerankTopK());
        List<VectorSearchService.SearchResult> candidates =
                hits.size() > RERANK_MAX_CANDIDATES ? hits.subList(0, RERANK_MAX_CANDIDATES) : hits;
        try {
            StringBuilder sb = new StringBuilder("问题：").append(question).append("\n候选片段：\n");
            for (int i = 0; i < candidates.size(); i++) {
                sb.append('[').append(i).append("] ").append(snippetOf(candidates.get(i))).append('\n');
            }
            sb.append("请挑出最相关的最多 ").append(topK).append(" 条，按相关性排序，只输出编号 JSON 数组。");
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    RERANK_SYSTEM_PROMPT, sb.toString(), 200L);

            List<Integer> order = parseIntArray(raw, candidates.size());
            if (order.isEmpty()) {
                return hits;
            }
            List<VectorSearchService.SearchResult> reranked = new ArrayList<>();
            for (int idx : order) {
                reranked.add(candidates.get(idx));
                if (reranked.size() >= topK) break;
            }
            log.info("[RagEnhancementService] rerank done, in={}, out={}", hits.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("[RagEnhancementService] rerank failed, keeping original order: {}", e.getMessage());
            return hits;
        }
    }

    private String snippetOf(VectorSearchService.SearchResult hit) {
        StringBuilder text = new StringBuilder();
        if (hit.speakerName() != null && !hit.speakerName().isBlank()) {
            text.append(hit.speakerName()).append(": ");
        }
        if (hit.sourceText() != null) {
            text.append(hit.sourceText().trim());
        }
        String value = text.toString().replaceAll("\\s+", " ").trim();
        return value.length() <= RERANK_SNIPPET_MAX_CHARS ? value : value.substring(0, RERANK_SNIPPET_MAX_CHARS) + "…";
    }

    private List<String> parseStringArray(String raw) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(stripFences(raw));
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asText("")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<Integer> parseIntArray(String raw, int bound) {
        Set<Integer> out = new LinkedHashSet<>();
        try {
            JsonNode node = objectMapper.readTree(stripFences(raw));
            if (node.isArray()) {
                for (JsonNode n : node) {
                    int v = n.asInt(-1);
                    if (v >= 0 && v < bound) out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    private String stripFences(String raw) {
        if (raw == null) return "[]";
        return raw.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();
    }
}
