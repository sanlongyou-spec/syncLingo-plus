package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.entity.Terminology;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.util.TextChunks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从上传的中↔印双语会议材料里自动抽取术语对(中=印[=英]),入术语表(强制级)。
 * 会前上传后异步调用一次,覆盖全文分块抽取;失败不影响上传主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminologyExtractionService {

    /** 每块字符数 */
    private static final int CHUNK_CHARS = 8000;
    /** 最多分块数(控成本) */
    private static final int MAX_CHUNKS = 8;

    private final LlmIntegration llmIntegration;
    private final TerminologyService terminologyService;
    private final ObjectMapper objectMapper;

    /** 从文件文本抽取术语对并入库。返回新建条数。 */
    public int extractAndSaveFromText(Long userId, String text) {
        if (userId == null || text == null || text.isBlank()) {
            return 0;
        }
        log.info("[TerminologyExtractionService] start, userId={}, textLen={}", userId, text.length());
        List<String> chunks = TextChunks.split(text, CHUNK_CHARS, MAX_CHUNKS);
        List<Terminology> candidates = new ArrayList<>();
        for (String chunk : chunks) {
            try {
                candidates.addAll(parsePairs(llmIntegration.extractTerminologyPairsJson(chunk)));
            } catch (Exception e) {
                log.warn("[TerminologyExtractionService] chunk extract failed, userId={}, reason={}", userId, e.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            log.info("[TerminologyExtractionService] end, userId={}, chunks={}, candidates=0, created=0", userId, chunks.size());
            return 0;
        }
        int created = terminologyService.addExtractedTerms(userId, candidates);
        log.info("[TerminologyExtractionService] end, userId={}, chunks={}, candidates={}, created={}",
                userId, chunks.size(), candidates.size(), created);
        return created;
    }

    /** 解析 LLM 返回的 JSON 数组(每元素 {zh,id,en,category})为术语候选;容错 markdown 围栏与脏数据。 */
    private List<Terminology> parsePairs(String json) {
        List<Terminology> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        String cleaned = json.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            if (!root.isArray()) {
                return result;
            }
            for (JsonNode node : root) {
                String zh = text(node, "zh");
                String id = text(node, "id");
                String en = text(node, "en");
                if (zh.isBlank() || id.isBlank()) {
                    continue; // 需中+印对照
                }
                Terminology t = new Terminology();
                t.setTermZh(zh);
                t.setTermId(id);
                t.setTermEn(en.isBlank() ? null : en);
                t.setCategory(text(node, "category"));
                result.add(t);
            }
        } catch (Exception e) {
            log.warn("[TerminologyExtractionService] parse failed: {}", e.getMessage());
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
