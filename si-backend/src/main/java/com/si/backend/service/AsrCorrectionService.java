package com.si.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.entity.AsrCorrection;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.AsrCorrectionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ASR 错词库服务：持久累积、跨会议复用的"听错→正确"记录。
 *
 * <p>能力：① 加载/匹配 ACTIVE 错词，供实时纠错翻译作参考；② 会后用"文档×ASR"挖掘错词并自动入库
 * (按命中频次/置信度从 OBSERVING 自动升级 ACTIVE)。无需人工逐条审核。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrCorrectionService {

    /** 命中达到该次数自动升为 ACTIVE(防一次性偶发错词立刻生效污染) */
    private static final int PROMOTE_HIT_COUNT = 2;
    /** 置信度达到该值即直接 ACTIVE */
    private static final double PROMOTE_CONFIDENCE = 0.9;
    /** 低于该置信度的挖掘结果直接丢弃(防脏) */
    private static final double MIN_MINE_CONFIDENCE = 0.55;
    /** 单句最多注入的错词参考条数 */
    private static final int MAX_MATCH_HINTS = 20;
    private static final int MIN_VARIANT_LEN = 2;

    private final AsrCorrectionMapper asrCorrectionMapper;
    private final LlmIntegration llmIntegration;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initTable() {
        log.info("[AsrCorrectionService] initTable start");
        asrCorrectionMapper.createTableIfNotExists();
        log.info("[AsrCorrectionService] initTable end");
    }

    public List<AsrCorrection> listActive(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return asrCorrectionMapper.findActive(userId);
    }

    public List<AsrCorrection> listAll(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return asrCorrectionMapper.findAll(userId);
    }

    /**
     * 找出本句文本里命中的 ACTIVE 错词，返回 变体→正确 的对照(供 LLM 作"疑似听错"参考)。
     * 拉丁词按单词边界匹配；含 CJK 的按子串匹配。
     */
    public LinkedHashMap<String, String> matchInText(Long userId, String text) {
        LinkedHashMap<String, String> hints = new LinkedHashMap<>();
        if (userId == null || text == null || text.isBlank()) {
            return hints;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (AsrCorrection correction : listActive(userId)) {
            String variant = correction.getVariant();
            String canonical = correction.getCanonical();
            if (variant == null || variant.length() < MIN_VARIANT_LEN || canonical == null || canonical.isBlank()) {
                continue;
            }
            String v = variant.toLowerCase(Locale.ROOT);
            boolean hit = isLatin(v)
                    ? Pattern.compile("\\b" + Pattern.quote(v) + "\\b", Pattern.UNICODE_CHARACTER_CLASS).matcher(lower).find()
                    : text.contains(variant);
            if (hit) {
                hints.putIfAbsent(variant, canonical);
                if (hints.size() >= MAX_MATCH_HINTS) {
                    break;
                }
            }
        }
        return hints;
    }

    private static boolean isLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }

    /** 手动新增/累加一条错词(归一化 variant 为小写键)。 */
    public void record(Long userId, String variant, String canonical, String srcLang, double confidence, String source) {
        if (userId == null || variant == null || variant.isBlank() || canonical == null || canonical.isBlank()) {
            return;
        }
        String key = variant.trim().toLowerCase(Locale.ROOT);
        String status = confidence >= PROMOTE_CONFIDENCE ? "ACTIVE" : "OBSERVING";
        asrCorrectionMapper.upsert(userId, key, canonical.trim(), srcLang, "GLOBAL", confidence, 1, status,
                source != null ? source : "MANUAL", PROMOTE_HIT_COUNT, PROMOTE_CONFIDENCE);
    }

    /**
     * 会后：用"会议转写 × 参考文件"挖掘 ASR 错词并自动入库，返回入库条数。
     * 低于置信度阈值的丢弃；其余 upsert(已存在则命中次数+1 并按阈值自动升级)。
     */
    public int mineAndStore(Long userId, String transcript, String documentText) {
        if (userId == null || transcript == null || transcript.isBlank()
                || documentText == null || documentText.isBlank()) {
            return 0;
        }
        long start = System.currentTimeMillis();
        log.info("[AsrCorrectionService] mineAndStore start, userId={}, transcriptLen={}, docLen={}",
                userId, transcript.length(), documentText.length());
        String json;
        try {
            json = llmIntegration.mineAsrCorrectionsJson(transcript, documentText);
        } catch (Exception e) {
            log.warn("[AsrCorrectionService] mineAndStore llm failed, userId={}, reason={}", userId, e.getMessage());
            return 0;
        }
        int stored = 0;
        int skippedLowConf = 0;
        try {
            JsonNode root = objectMapper.readTree(stripJsonFence(json));
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String variant = node.path("variant").asText("").trim();
                    String canonical = node.path("canonical").asText("").trim();
                    double confidence = node.path("confidence").asDouble(0);
                    if (variant.length() < MIN_VARIANT_LEN || canonical.isBlank()) {
                        continue;
                    }
                    if (confidence < MIN_MINE_CONFIDENCE) {
                        skippedLowConf++;
                        continue;
                    }
                    record(userId, variant, canonical, null, confidence, "DOC_MINING");
                    stored++;
                }
            }
        } catch (Exception e) {
            log.warn("[AsrCorrectionService] mineAndStore parse failed, userId={}, reason={}", userId, e.getMessage());
            return stored;
        }
        log.info("[AsrCorrectionService] mineAndStore end, userId={}, stored={}, skippedLowConf={}, costMs={}",
                userId, stored, skippedLowConf, System.currentTimeMillis() - start);
        return stored;
    }

    /** 去掉 LLM 偶尔包裹的 ```json ... ``` 围栏，取出纯 JSON。 */
    private String stripJsonFence(String text) {
        if (text == null) {
            return "[]";
        }
        String t = text.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return "[]";
    }
}
