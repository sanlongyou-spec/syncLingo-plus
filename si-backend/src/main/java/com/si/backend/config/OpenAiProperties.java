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

    private int compressionMinTextLength = 80;

    private String compressionModel = "gpt-5-nano";

    private double compressionZhToIdTargetRatio = 0.75;

    private boolean compressionZhToEnEnabled = true;

    private double compressionZhToEnTargetRatio = 0.85;

    private String summaryModel = "gpt-5-mini";

    private String documentSummaryModel = "gpt-5";

    private long compressionMaxOutputTokens = 512L;

    private long summaryMaxOutputTokens = 1200L;

    private long documentSummaryMaxOutputTokens = 4000L;

    private String embeddingModel = "text-embedding-3-small";

    /**
     * Optional separate endpoint for embeddings. OpenRouter has no /embeddings API, so when
     * chat runs on OpenRouter, embeddings must use a different provider. Blank = reuse baseUrl.
     */
    private String embeddingBaseUrl;

    /** API key for the embedding provider. Blank = reuse apiKey. */
    private String embeddingApiKey;

    private int embeddingTopK = 20;

    private float embeddingMinScore = 0.3f;

    public String effectiveEmbeddingBaseUrl() {
        return (embeddingBaseUrl != null && !embeddingBaseUrl.isBlank()) ? embeddingBaseUrl : baseUrl;
    }

    public String effectiveEmbeddingApiKey() {
        return (embeddingApiKey != null && !embeddingApiKey.isBlank()) ? embeddingApiKey : apiKey;
    }

    // ── RAG quality (P0): query expansion + reranking. Default off — flip on to enable. ──

    /** Rewrite/expand the question into several queries before retrieval (multi-query recall). */
    private boolean ragQueryExpansionEnabled = false;

    /** How many extra reformulations to generate (besides the original question). */
    private int ragQueryExpansionCount = 2;

    /** Rerank recalled chunks with the LLM and keep the most relevant ones. */
    private boolean ragRerankEnabled = false;

    /** Model used for query expansion / reranking. Blank = reuse summaryModel. */
    private String ragHelperModel;

    /** How many chunks to keep after reranking (fed into the answer context). */
    private int ragRerankTopK = 12;

    public String effectiveRagHelperModel() {
        return (ragHelperModel != null && !ragHelperModel.isBlank()) ? ragHelperModel : summaryModel;
    }
}
