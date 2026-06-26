package com.si.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.dto.HotwordSuggestion;
import com.si.backend.entity.AsrHotword;
import com.si.backend.entity.InterpretationRecord;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.AsrHotwordMapper;
import com.si.backend.mapper.InterpretationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extracts named entities and domain terms from session transcripts via LLM,
 * then saves them as ASR hotwords to improve recognition accuracy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotwordExtractionService {

    private static final int MAX_RECORDS = 60;
    private static final int MAX_TEXT_CHARS = 4000;
    private static final int LOG_SAMPLE_LIMIT = 20;
    /** 会议材料热词抽取的最大分块数(覆盖全文用,控成本) */

    private final LlmIntegration llmIntegration;
    private final InterpretationRecordMapper recordMapper;
    private final AsrHotwordService hotwordService;
    private final AsrHotwordMapper hotwordMapper;
    private final ObjectMapper objectMapper;

    /**
     * Preview suggestions for a session without saving anything.
     * Each suggestion is annotated with {@code exists=true} if the phrase is already a hotword.
     */
    public List<HotwordSuggestion> previewFromSession(String sessionId, Long userId) {
        log.info("[HotwordExtractionService] previewFromSession start, sessionId={}, userId={}", sessionId, userId);
        String text = buildTranscript(sessionId);
        if (text.isBlank()) {
            log.info("[HotwordExtractionService] previewFromSession - no transcript, sessionId={}", sessionId);
            return List.of();
        }
        List<HotwordSuggestion> suggestions = extractSuggestions(text);
        suggestions.forEach(s -> s.setExists(
                hotwordMapper.countByUserIdPhraseAndLanguage(userId, s.getPhrase(), s.getLanguage() != null ? s.getLanguage() : "") > 0
        ));
        log.info("[HotwordExtractionService] previewFromSession end, sessionId={}, count={}", sessionId, suggestions.size());
        return suggestions;
    }

    /**
     * Extracts hotwords from a session and saves only phrases not yet in the user's hotword list.
     * Intended for async background execution after session stop.
     */
    public List<AsrHotword> extractAndSaveFromSession(String sessionId, Long userId) {
        log.info("[HotwordExtractionService] extractAndSaveFromSession start, sessionId={}, userId={}", sessionId, userId);
        String text = buildTranscript(sessionId);
        if (text.isBlank()) {
            log.info("[HotwordExtractionService] extractAndSaveFromSession - no transcript, sessionId={}", sessionId);
            return List.of();
        }
        List<HotwordSuggestion> suggestions = extractSuggestions(text);
        List<AsrHotword> saved = suggestions.stream()
                .filter(s -> s.getPhrase() != null && !s.getPhrase().isBlank())
                .filter(s -> hotwordMapper.countByUserIdPhraseAndLanguage(
                        userId, s.getPhrase(), s.getLanguage() != null ? s.getLanguage() : "") == 0)
                .map(s -> buildHotword(s, "AUTO_EXTRACTED"))
                .map(hw -> hotwordService.create(userId, hw))
                .toList();
        log.info("[HotwordExtractionService] extractAndSaveFromSession end, sessionId={}, extracted={}, saved={}",
                sessionId, suggestions.size(), saved.size());
        return saved;
    }

    /**
     * Extracts hotwords from arbitrary text (e.g. uploaded meeting materials) and saves new ones.
     * Intended for async background execution after file upload.
     */
    public List<AsrHotword> extractAndSaveFromText(String text, Long userId) {
        if (text == null || text.isBlank() || userId == null) return List.of();
        log.info("[HotwordExtractionService] extractAndSaveFromText start, userId={}, textLen={}", userId, text.length());
        int requiredChunks = Math.max(1, (text.strip().length() + MAX_TEXT_CHARS - 1) / MAX_TEXT_CHARS);
        // 覆盖全文：按实际文本长度分块抽取，不再只取前 4000 字或固定前 N 块。
        List<String> chunks = com.si.backend.util.TextChunks.split(text, MAX_TEXT_CHARS, requiredChunks);
        // 跨块按 phrase(trim+小写) 去重,合并所有块的抽取结果
        Map<String, HotwordSuggestion> uniqueByPhrase = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            for (HotwordSuggestion s : extractSuggestions(chunks.get(i), i + 1, chunks.size())) {
                if (s.getPhrase() == null || s.getPhrase().isBlank()) continue;
                uniqueByPhrase.putIfAbsent(s.getPhrase().trim().toLowerCase(), s);
            }
        }
        List<AsrHotword> saved = uniqueByPhrase.values().stream()
                // 文件抽取的热词入库时语言置空(=全局),让三种语言识别路径都生效
                .filter(s -> hotwordMapper.countByUserIdPhraseAndLanguage(userId, s.getPhrase().trim(), "") == 0)
                .map(s -> buildGlobalHotword(s, "AUTO_EXTRACTED"))
                .map(hw -> hotwordService.create(userId, hw))
                .toList();
        log.info("[HotwordExtractionService] extractAndSaveFromText end, userId={}, chunks={}, extracted={}, saved={}, phrases={}",
                userId, chunks.size(), uniqueByPhrase.size(), saved.size(), summarizeSavedHotwords(saved));
        return saved;
    }

    /**
     * Saves a user-confirmed selection of suggestions.
     * Skips phrases that already exist as hotwords.
     */
    public List<AsrHotword> confirmSuggestions(Long userId, List<HotwordSuggestion> selected) {
        log.info("[HotwordExtractionService] confirmSuggestions start, userId={}, count={}", userId, selected != null ? selected.size() : 0);
        if (selected == null || selected.isEmpty()) return List.of();
        List<AsrHotword> saved = selected.stream()
                .filter(s -> s.getPhrase() != null && !s.getPhrase().isBlank())
                .filter(s -> hotwordMapper.countByUserIdPhraseAndLanguage(
                        userId, s.getPhrase(), s.getLanguage() != null ? s.getLanguage() : "") == 0)
                .map(s -> buildHotword(s, "AUTO_EXTRACTED"))
                .map(hw -> hotwordService.create(userId, hw))
                .toList();
        log.info("[HotwordExtractionService] confirmSuggestions end, userId={}, saved={}", userId, saved.size());
        return saved;
    }

    private String buildTranscript(String sessionId) {
        List<InterpretationRecord> records = recordMapper.findBySessionId(sessionId);
        if (records.size() > MAX_RECORDS) {
            records = records.subList(records.size() - MAX_RECORDS, records.size());
        }
        String text = records.stream()
                .map(InterpretationRecord::getSourceText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return text.trim();
    }

    private List<HotwordSuggestion> extractSuggestions(String text) {
        return extractSuggestions(text, 1, 1);
    }

    private List<HotwordSuggestion> extractSuggestions(String text, int chunkIndex, int chunkCount) {
        try {
            String json = llmIntegration.extractHotwordsJson(text);
            List<HotwordSuggestion> result = parseSuggestions(json);
            log.info("[HotwordExtractionService] chunk extracted, chunk={}/{}, suggestions={}, phrases={}",
                    chunkIndex, chunkCount, result.size(), summarizeSuggestions(result));
            return result;
        } catch (Exception e) {
            log.warn("[HotwordExtractionService] chunk extract failed, chunk={}/{}, reason={}",
                    chunkIndex, chunkCount, e.getMessage());
            return List.of();
        }
    }

    private List<HotwordSuggestion> parseSuggestions(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String cleaned = cleanJson(json);
        try {
            List<HotwordSuggestion> result = objectMapper.readValue(cleaned, new TypeReference<>() {});
            return result != null ? result : List.of();
        } catch (IOException e) {
            List<HotwordSuggestion> salvaged = salvageCompleteHotwordObjects(cleaned);
            if (!salvaged.isEmpty()) {
                log.warn("[HotwordExtractionService] repaired partial hotword JSON, recovered={}", salvaged.size());
                return salvaged;
            }
            throw e;
        }
    }

    private List<HotwordSuggestion> salvageCompleteHotwordObjects(String json) {
        List<HotwordSuggestion> result = new ArrayList<>();
        for (String objectJson : completeObjectJsons(json)) {
            try {
                result.add(objectMapper.readValue(objectJson, HotwordSuggestion.class));
            } catch (Exception ignored) {
                // Skip malformed object fragments and keep later complete objects if any.
            }
        }
        return result;
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

    private AsrHotword buildHotword(HotwordSuggestion s, String sourceType) {
        AsrHotword hw = new AsrHotword();
        hw.setPhrase(s.getPhrase().trim());
        hw.setCategory(s.getCategory());
        hw.setLanguage(s.getLanguage());
        hw.setWeight(1.0);
        hw.setSourceType(sourceType);
        hw.setEnabled(true);
        return hw;
    }

    /** 与 {@link #buildHotword} 相同,但语言置空(=全局),让该热词对三种语言识别路径都生效。 */
    private AsrHotword buildGlobalHotword(HotwordSuggestion s, String sourceType) {
        AsrHotword hw = buildHotword(s, sourceType);
        hw.setLanguage("");
        return hw;
    }

    private String summarizeSuggestions(List<HotwordSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "";
        }
        return suggestions.stream()
                .limit(LOG_SAMPLE_LIMIT)
                .map(s -> s.getPhrase() + "(" + (s.getLanguage() != null ? s.getLanguage() : "ALL") + ")")
                .collect(Collectors.joining(", "));
    }

    private String summarizeSavedHotwords(List<AsrHotword> hotwords) {
        if (hotwords == null || hotwords.isEmpty()) {
            return "";
        }
        return hotwords.stream()
                .limit(LOG_SAMPLE_LIMIT)
                .map(AsrHotword::getPhrase)
                .collect(Collectors.joining(", "));
    }
}
