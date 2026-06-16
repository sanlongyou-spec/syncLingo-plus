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

import java.time.Duration;
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
    /** P2-8 agentic iterative retrieval (retrieve → judge → follow-up). Off by default (extra LLM calls). */
    @Value("${rag.agentic.enabled:false}")
    private boolean agenticEnabled;
    @Value("${rag.agentic.max-steps:3}")
    private int agenticMaxSteps;

    public boolean isAgenticEnabled() { return agenticEnabled; }
    public int getAgenticMaxSteps() { return Math.max(1, agenticMaxSteps); }

    /** Decision from the agentic follow-up step. */
    public record AgenticDecision(boolean enough, String nextQuery) {}

    private static final String AGENTIC_SYSTEM_PROMPT =
            "你是检索规划助手。给定原问题和已检索到的资料，判断资料是否足以【完整】回答原问题。"
            + "若已足够，输出 {\"enough\":true}；若还缺信息，输出 {\"enough\":false,\"next_query\":\"下一步应检索的查询\"}，"
            + "next_query 要针对缺失的那部分、与原问题相同语言。只输出该 JSON，不要解释。";

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
            long start = System.currentTimeMillis();
            String user = "问题：" + question + "\n生成 " + want + " 个检索查询。";
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    EXPANSION_SYSTEM_PROMPT, user, 300L, helperTimeout());
            for (String q : parseStringArray(raw)) {
                String trimmed = q.trim();
                if (!trimmed.isBlank() && result.stream().noneMatch(trimmed::equalsIgnoreCase)) {
                    result.add(trimmed);
                }
                if (result.size() >= want + 1) break;
            }
            log.info("[RagEnhancementService] expandQueries done, original=1, total={}, costMs={}",
                    result.size(), System.currentTimeMillis() - start);
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
            long start = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("最近对话：\n");
            int from = Math.max(0, history.size() - rewriteHistoryTurns);
            for (int i = from; i < history.size(); i++) {
                ChatTurn t = history.get(i);
                sb.append("user".equals(t.getRole()) ? "用户：" : "助手：")
                        .append(t.getContent() == null ? "" : t.getContent().trim()).append('\n');
            }
            sb.append("最新问题：").append(question).append("\n改写为自包含查询：");
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    REWRITE_SYSTEM_PROMPT, sb.toString(), 200L, helperTimeout());
            String rewritten = stripFences(raw).trim();
            if (rewritten.isBlank()) return question;
            log.info("[RagEnhancementService] rewriteQuery done, len {}->{}, costMs={}",
                    question.length(), rewritten.length(), System.currentTimeMillis() - start);
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
            long start = System.currentTimeMillis();
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    DECOMPOSE_SYSTEM_PROMPT, "问题：" + question, 300L, helperTimeout());
            for (String sub : parseStringArray(raw)) {
                String trimmed = sub.trim();
                if (!trimmed.isBlank() && result.stream().noneMatch(trimmed::equalsIgnoreCase)) {
                    result.add(trimmed);
                }
                if (result.size() >= decomposeMax + 1) break;
            }
            if (result.size() > 1) {
                log.info("[RagEnhancementService] decompose done, subQuestions={}, costMs={}",
                        result.size() - 1, System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            log.warn("[RagEnhancementService] decompose failed, using original only: {}", e.getMessage());
        }
        return result;
    }

    /**
     * P2-8: after a retrieval pass, ask the LLM whether the gathered context is enough to answer the
     * original question; if not, propose one follow-up query. Returns "enough" when disabled or on
     * any failure (so the loop terminates safely).
     */
    public AgenticDecision agenticFollowup(String question, String contextSoFar) {
        if (!agenticEnabled || question == null || question.isBlank()) {
            return new AgenticDecision(true, null);
        }
        try {
            long start = System.currentTimeMillis();
            String ctx = contextSoFar == null ? "" : contextSoFar;
            if (ctx.length() > 4000) ctx = ctx.substring(0, 4000);
            String user = "原问题：" + question + "\n已检索到的资料：\n" + ctx;
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    AGENTIC_SYSTEM_PROMPT, user, 200L, helperTimeout());
            AgenticDecision decision = parseAgenticDecision(raw);
            log.info("[RagEnhancementService] agenticFollowup done, enough={}, hasNext={}, costMs={}",
                    decision.enough(), decision.nextQuery() != null, System.currentTimeMillis() - start);
            return decision;
        } catch (Exception e) {
            log.warn("[RagEnhancementService] agenticFollowup failed, treating as enough: {}", e.getMessage());
            return new AgenticDecision(true, null);
        }
    }

    /** Parse {"enough":bool,"next_query":string}. Defaults to "enough" on anything unparseable. */
    static AgenticDecision parseAgenticDecision(String raw) {
        if (raw == null || raw.isBlank()) return new AgenticDecision(true, null);
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                int nl = json.indexOf('\n');
                if (nl > 0) json = json.substring(nl + 1);
                if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
            }
            JsonNode root = new ObjectMapper().readTree(json.trim());
            boolean enough = root.path("enough").asBoolean(true);
            String next = root.hasNonNull("next_query") ? root.get("next_query").asText().trim() : null;
            if (next != null && next.isBlank()) next = null;
            return new AgenticDecision(enough || next == null, next);
        } catch (Exception e) {
            return new AgenticDecision(true, null);
        }
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
            long start = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("问题：").append(question).append("\n候选片段：\n");
            for (int i = 0; i < candidates.size(); i++) {
                sb.append('[').append(i).append("] ").append(snippetOf(candidates.get(i))).append('\n');
            }
            sb.append("请挑出最相关的最多 ").append(topK).append(" 条，按相关性排序，只输出编号 JSON 数组。");
            String raw = llmIntegration.complete(openAiProperties.effectiveRagHelperModel(),
                    RERANK_SYSTEM_PROMPT, sb.toString(), 200L, helperTimeout());

            List<Integer> order = parseIntArray(raw, candidates.size());
            if (order.isEmpty()) {
                return hits;
            }
            List<VectorSearchService.SearchResult> reranked = new ArrayList<>();
            for (int idx : order) {
                reranked.add(candidates.get(idx));
                if (reranked.size() >= topK) break;
            }
            log.info("[RagEnhancementService] rerank done, in={}, candidates={}, out={}, costMs={}",
                    hits.size(), candidates.size(), reranked.size(), System.currentTimeMillis() - start);
            return reranked;
        } catch (Exception e) {
            log.warn("[RagEnhancementService] rerank failed, keeping original order: {}", e.getMessage());
            return hits;
        }
    }

    private Duration helperTimeout() {
        int seconds = openAiProperties.getRagHelperTimeoutSeconds() > 0
                ? openAiProperties.getRagHelperTimeoutSeconds()
                : 20;
        return Duration.ofSeconds(seconds);
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
