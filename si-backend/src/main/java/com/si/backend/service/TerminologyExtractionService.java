package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.entity.Terminology;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.util.TextChunks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts bilingual terminology pairs from uploaded meeting materials.
 * The workflow is best-effort: failed chunks are logged and do not block upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminologyExtractionService {

    private static final int CHUNK_CHARS = 8000;
    private static final int MAX_CHUNKS = 8;
    private static final int LOG_SAMPLE_LIMIT = 20;

    private final LlmIntegration llmIntegration;
    private final TerminologyService terminologyService;
    private final ObjectMapper objectMapper;

    public int extractAndSaveFromText(Long userId, String text) {
        if (userId == null || text == null || text.isBlank()) {
            return 0;
        }
        log.info("[TerminologyExtractionService] start, userId={}, textLen={}", userId, text.length());
        List<String> chunks = TextChunks.split(text, CHUNK_CHARS, MAX_CHUNKS);
        List<Terminology> candidates = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            try {
                List<Terminology> parsed = parsePairs(llmIntegration.extractTerminologyPairsJson(chunks.get(i)));
                // 第二步:让 LLM 逐条校验"是否为正确互译",剔除语义错(等离子体=Plasma、乔布斯=Joshua、LSU 对齐错等)。
                List<Terminology> confirmed = verify(parsed, chunks.get(i));
                candidates.addAll(confirmed);
                log.info("[TerminologyExtractionService] chunk extracted, userId={}, chunk={}/{}, parsed={}, confirmed={}, terms={}",
                        userId, i + 1, chunks.size(), parsed.size(), confirmed.size(), summarizeTerms(confirmed));
            } catch (Exception e) {
                log.warn("[TerminologyExtractionService] chunk extract failed, userId={}, chunk={}/{}, reason={}",
                        userId, i + 1, chunks.size(), e.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            log.info("[TerminologyExtractionService] end, userId={}, chunks={}, candidates=0, created=0", userId, chunks.size());
            return 0;
        }
        int created = terminologyService.addExtractedTerms(userId, candidates);
        log.info("[TerminologyExtractionService] end, userId={}, chunks={}, candidates={}, created={}, terms={}",
                userId, chunks.size(), candidates.size(), created, summarizeTerms(candidates));
        return created;
    }

    /**
     * 让 LLM 逐条校验候选对是否为正确互译,只保留确认正确的。
     * 校验失败(异常/解析不出)时返回原候选,避免误删——退化为"仅抽取"的旧行为。
     */
    private List<Terminology> verify(List<Terminology> parsed, String sourceChunk) {
        if (parsed.isEmpty()) {
            return parsed;
        }
        try {
            String candidatesJson = toCandidatesJson(parsed);
            String verifiedJson = llmIntegration.verifyTerminologyPairsJson(candidatesJson, sourceChunk);
            // 信任校验结果:返回有效 JSON(即便全部被否决=空)就照单全收,宁缺毋滥。
            // 只有解析抛异常(模型没按格式返回)才退化为保留原候选,避免因校验环节故障误删。
            return parsePairs(verifiedJson);
        } catch (Exception e) {
            log.warn("[TerminologyExtractionService] verify failed, keep unverified, reason={}", e.getMessage());
            return parsed;
        }
    }

    /** 把候选术语对序列化成给校验提示词用的紧凑 JSON 数组。 */
    private String toCandidatesJson(List<Terminology> terms) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        for (Terminology t : terms) {
            com.fasterxml.jackson.databind.node.ObjectNode o = arr.addObject();
            o.put("zh", t.getTermZh());
            o.put("id", t.getTermId());
            o.put("en", t.getTermEn() == null ? "" : t.getTermEn());
            o.put("category", t.getCategory() == null ? "" : t.getCategory());
        }
        return arr.toString();
    }

    private List<Terminology> parsePairs(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String cleaned = cleanJson(json);
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            if (!root.isArray()) {
                return List.of();
            }
            List<Terminology> result = new ArrayList<>();
            for (JsonNode node : root) {
                addParsedTerm(result, node);
            }
            return result;
        } catch (IOException e) {
            List<Terminology> salvaged = salvageCompleteTermObjects(cleaned);
            if (!salvaged.isEmpty()) {
                log.warn("[TerminologyExtractionService] repaired partial terminology JSON, recovered={}", salvaged.size());
                return salvaged;
            }
            throw e;
        } catch (Exception e) {
            throw new IOException("parse failed: " + e.getMessage(), e);
        }
    }

    private List<Terminology> salvageCompleteTermObjects(String json) {
        List<Terminology> result = new ArrayList<>();
        for (String objectJson : completeObjectJsons(json)) {
            try {
                addParsedTerm(result, objectMapper.readTree(objectJson));
            } catch (Exception ignored) {
                // Keep any later complete object even if this fragment is malformed.
            }
        }
        return result;
    }

    private void addParsedTerm(List<Terminology> result, JsonNode node) {
        String zh = text(node, "zh");
        String id = text(node, "id");
        String en = text(node, "en");
        if (zh.isBlank() || id.isBlank()) {
            return;
        }
        if (isLikelyJunkTerm(zh, id)) {
            return; // 通用单位/符号/纯数字等噪声不入强制术语表
        }
        Terminology terminology = new Terminology();
        terminology.setTermZh(zh);
        terminology.setTermId(id);
        terminology.setTermEn(en.isBlank() ? null : en);
        terminology.setCategory(text(node, "category"));
        result.add(terminology);
    }

    /** 通用单位 / 量纲词 / 符号:当强制术语纯属噪声,自动术语入库前过滤掉(手动 Excel 不走此过滤)。 */
    private static final java.util.Set<String> JUNK_TERMS = java.util.Set.of(
            // 印尼/英文单位与符号
            "%", "％", "ppm", "ha", "kg", "ton", "rp", "m", "cm", "mm", "km", "l", "ml",
            "hk", "kg/pokok", "kg/pkk", "ha/hari", "ha/day", "hk/ha", "dosis kg/pkk", "kg/ha",
            // 通用中文量纲/统计词(非专业术语)
            "百分比", "公顷", "公斤", "吨", "印尼盾", "单位", "面积", "种植面积", "单价",
            "数量", "金额", "总价", "公里", "米", "升"
    );

    /** 判断是否为应过滤的噪声术语对(两侧任一命中通用词,或印尼侧为单字符/纯数字符号)。 */
    static boolean isLikelyJunkTerm(String zh, String id) {
        if (zh == null || id == null) {
            return true;
        }
        String z = zh.trim().toLowerCase();
        String i = id.trim().toLowerCase();
        if (JUNK_TERMS.contains(z) || JUNK_TERMS.contains(i)) {
            return true;
        }
        if (i.length() <= 1) {
            return true; // 单字符(如 "%"、单字母)
        }
        // 印尼/中文侧为纯数字或纯标点符号(含全角百分号)
        return i.matches("[\\d\\p{Punct}％%]+") || z.matches("[\\d\\p{Punct}％%]+");
    }

    private List<String> completeObjectJsons(String json) {
        List<String> objects = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return objects;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int objectStart = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }
        return objects;
    }

    private String cleanJson(String json) {
        return json.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();
    }

    private String summarizeTerms(List<Terminology> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        return terms.stream()
                .limit(LOG_SAMPLE_LIMIT)
                .map(t -> t.getTermZh() + "|" + t.getTermId() + "|" + (t.getTermEn() != null ? t.getTermEn() : ""))
                .collect(Collectors.joining(", "));
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
