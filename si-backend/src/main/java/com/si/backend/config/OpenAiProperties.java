package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAI API configuration for LLM compression and meeting summary generation.
 */
@Data
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;

    private String baseUrl = "https://api.openai.com/v1";

    private String referer;

    private String title;

    private boolean compressionEnabled = true;

    private int compressionMinTextLength = 20;

    // Real-time per-segment compression — kept on a fast, non-reasoning model.
    private String compressionModel = "anthropic/claude-haiku-4.5";

    private double compressionZhToIdTargetRatio = 0.60;

    private boolean compressionZhToEnEnabled = true;

    private double compressionZhToEnTargetRatio = 0.70;

    // ── 印尼语→中文 LLM 纠错翻译(ASR 后处理 + 专业翻译) ──────────────────
    // 印尼语 ASR 把专业词/缩写听错(pupuk→kupu、boron→buron、pH→PHK 等),普通翻译会照错直翻。
    // 开启后,id→zh 改走 LLM:先按棕榈种植园施肥/缺素语境纠错 ASR 文本,再翻成自然中文。
    // 失败/超时自动回退到原 Google 翻译路径,绝不阻断同传。
    private boolean idZhLlmTranslateEnabled = true;

    /** 纠错翻译用的快模型(非推理),与实时压缩同档,控延迟 */
    private String idZhLlmTranslateModel = "anthropic/claude-haiku-4.5";

    /** 单次纠错翻译的最大输出 token */
    private long idZhLlmTranslateMaxOutputTokens = 600L;

    /** 实时预算:超时即回退 Google,避免拖慢同传 */
    private int idZhLlmTranslateTimeoutMs = 4000;

    /** 滑动上下文窗口字符数:把最近若干印尼语原文作为上下文给 LLM 消歧 */
    private int idZhLlmTranslateContextChars = 600;

    /** id→zh 时让 LLM 在同一次调用里做轻度口译式精简(去口头语/重复,不丢事实/数字/专名) */
    private boolean idZhLlmTranslateConcise = true;

    // ── 方案2:LLM 实时分句(让 LLM 在句子边界切,异步,不阻塞 ASR) ──────────
    /** 开启后印尼语改由 LLM 异步判断"已说完的整句"再切;关闭则回到 wtpsplit 小模型/Azure 分句。
     *  实测 LLM 边界分句效果不佳(切点错乱),默认关闭,生产用 wtpsplit;保留开关以便后续调优。 */
    private boolean idZhLlmSegmentEnabled = false;
    /** 分句用的快模型 */
    private String idZhLlmSegmentModel = "anthropic/claude-haiku-4.5";
    /** 分句调用超时(ms) */
    private int idZhLlmSegmentTimeoutMs = 4000;
    /** 未提交文本达到该字符数才触发一次分句调用(过短不调,省成本) */
    private int idZhLlmSegmentMinChars = 40;
    /** 距上次触发,未提交文本至少再增长这么多字符才再次调用(节流) */
    private int idZhLlmSegmentRefireChars = 30;

    // Summaries / Q&A / RAG helper — DeepSeek V4 (good Chinese, low cost).
    private String summaryModel = "deepseek/deepseek-v4-pro";

    // Background structured extraction must avoid reasoning models eating the JSON output budget.
    private String extractionModel = "anthropic/claude-haiku-4.5";

    private boolean extractionDisableReasoning = true;

    private String documentSummaryModel = "deepseek/deepseek-v4-pro";

    private long compressionMaxOutputTokens = 512L;

    private long summaryMaxOutputTokens = 1200L;

    private long documentSummaryMaxOutputTokens = 4000L;

    private String embeddingModel = "openai/text-embedding-3-small";

    private String embeddingProfile = "default";

    /**
     * Optional separate endpoint for embeddings. OpenRouter has no /embeddings API, so when
     * chat runs on OpenRouter, embeddings must use a different provider. Blank = reuse baseUrl.
     */
    private String embeddingBaseUrl;

    /** API key for the embedding provider. Blank = reuse apiKey. */
    private String embeddingApiKey;

    private int embeddingTopK = 20;

    private float embeddingMinScore = 0.3f;

    private int embeddingCandidateLimit = 2000;

    public String effectiveEmbeddingBaseUrl() {
        return (embeddingBaseUrl != null && !embeddingBaseUrl.isBlank()) ? embeddingBaseUrl : baseUrl;
    }

    public String effectiveEmbeddingApiKey() {
        return (embeddingApiKey != null && !embeddingApiKey.isBlank()) ? embeddingApiKey : apiKey;
    }

    // ── RAG quality (P0): query expansion + reranking. Default off — flip on to enable. ──

    /** Rewrite/expand the question into several queries before retrieval (multi-query recall). */
    private boolean ragQueryExpansionEnabled = true;

    /** How many extra reformulations to generate (besides the original question). */
    private int ragQueryExpansionCount = 2;

    /** Rerank recalled chunks with the LLM and keep the most relevant ones. */
    private boolean ragRerankEnabled = true;

    /** Model used for query expansion / reranking. Blank = reuse summaryModel. */
    private String ragHelperModel;

    /** How many chunks to keep after reranking (fed into the answer context). */
    private int ragRerankTopK = 12;

    /** Per expanded query recall limit before merge/rerank. */
    private int ragRecallPerQueryTopK = 40;

    /** Cap the total expanded/decomposed query count per user question. */
    private int ragMaxQueries = 9;

    /** Short timeout for helper LLM calls; failures fall back to the original retrieval path. */
    private int ragHelperTimeoutSeconds = 20;

    public String effectiveRagHelperModel() {
        return (ragHelperModel != null && !ragHelperModel.isBlank()) ? ragHelperModel : summaryModel;
    }

    public String effectiveExtractionModel() {
        if (extractionModel != null && !extractionModel.isBlank()) {
            return extractionModel;
        }
        return compressionModel;
    }
}
