package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.config.OpenAiProperties;
import com.si.backend.integration.LlmIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
