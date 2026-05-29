package com.si.backend.service;

import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import com.si.backend.vo.SpeakerSummaryVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpeakerSummaryService {

    private static final long REFUSAL_RETRY_BASE_DELAY_MS = 2_000L;
    private static final long REFUSAL_RETRY_MAX_DELAY_MS = 10_000L;
    private static final String TITLE_PREFIX = "\u6807\u9898";

    private final LlmIntegration llmIntegration;
    private final SpeakerSummaryRecordMapper mapper;
    private final ContentEmbeddingService contentEmbeddingService;

    @PostConstruct
    public void initTable() {
        mapper.createTableIfNotExists();
        addColumnIfMissing("title", mapper::addTitleColumnIfNotExists);
    }

    private void addColumnIfMissing(String column, Runnable alter) {
        try {
            alter.run();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.debug("[SpeakerSummaryService] column {} already exists", column);
            } else {
                log.warn("[SpeakerSummaryService] failed to add column {}", column, e);
            }
        }
    }

    private static boolean isRefusal(String response) {
        if (response == null || response.isBlank()) return false;
        String lower = response.toLowerCase();
        return lower.contains("i'm sorry") || lower.contains("i am sorry")
                || lower.contains("cannot assist") || lower.contains("can't assist")
                || lower.contains("unable to assist") || lower.contains("i apologize")
                || lower.contains("i can't help") || lower.contains("i cannot help")
                || lower.contains("sorry, but i") || lower.contains("sorry, i cannot");
    }

    private static boolean isUnusableSummary(String response) {
        return response == null || response.isBlank() || isRefusal(response);
    }

    public SpeakerSummaryVo summarize(String speakerName, String text, String sessionId, String speakerId) {
        return summarize(speakerName, text, sessionId, speakerId, null);
    }

    public SpeakerSummaryVo summarize(String speakerName, String text, String sessionId, String speakerId, String requirements) {
        log.info("[SpeakerSummaryService] summarize start, speaker={}, textLen={}", speakerName, text.length());
        try {
            String raw = summarizeSpeakerUntilAccepted(speakerName, text, requirements);
            String[] parts = parseTitleAndContent(raw);
            String title = parts[0];
            String summary = parts[1];
            log.info("[SpeakerSummaryService] summarize done, speaker={}, title={}, summaryLen={}", speakerName, title, summary.length());
            try {
                SpeakerSummaryRecord record = SpeakerSummaryRecord.builder()
                        .sessionId(sessionId)
                        .speakerId(speakerId)
                        .speakerName(speakerName)
                        .title(title)
                        .textSnippet(text.length() > 2000 ? text.substring(0, 2000) : text)
                        .summary(summary)
                        .build();
                mapper.insert(record);
                if (record.getId() != null) {
                    contentEmbeddingService.asyncEmbedSpeakerSummary(
                            record.getId(), sessionId, speakerName, title, summary);
                }
            } catch (Exception e) {
                log.warn("[SpeakerSummaryService] failed to persist summary, sessionId={}", sessionId, e);
            }
            return SpeakerSummaryVo.builder()
                    .speakerId(speakerId)
                    .speakerName(speakerName)
                    .title(title)
                    .summary(summary)
                    .build();
        } catch (Exception e) {
            log.error("[SpeakerSummaryService] summarize failed, speaker={}", speakerName, e);
            throw new RuntimeException("发言摘要生成失败: " + e.getMessage(), e);
        }
    }

    private static String[] parseTitleAndContent(String response) {
        if (response == null || response.isBlank()) return new String[]{null, ""};
        String trimmed = response.trim();
        int newline = trimmed.indexOf('\n');
        if (newline > 0) {
            String firstLine = trimmed.substring(0, newline).trim();
            if (firstLine.startsWith(TITLE_PREFIX + "\uFF1A") || firstLine.startsWith(TITLE_PREFIX + ":")) {
                String title = firstLine.replaceFirst("^" + TITLE_PREFIX + "[\\uFF1A:]\\s*", "").trim();
                String content = trimmed.substring(newline).trim();
                return new String[]{title.isEmpty() ? null : title, content};
            }
        }
        return new String[]{null, trimmed};
    }

    private String summarizeSpeakerUntilAccepted(String speakerName, String text) throws Exception {
        return summarizeSpeakerUntilAccepted(speakerName, text, null);
    }

    private String summarizeSpeakerUntilAccepted(String speakerName, String text, String requirements) throws Exception {
        int attempt = 1;
        String raw = llmIntegration.summarizeSpeakerSegment(speakerName, text, requirements);
        while (isUnusableSummary(raw)) {
            log.warn("[SpeakerSummaryService] unusable LLM speaker summary detected, retrying, speaker={}, attempt={}",
                    speakerName, attempt);
            sleepBeforeRetry(attempt);
            attempt++;
            raw = requirements != null && !requirements.isBlank()
                    ? llmIntegration.summarizeSpeakerSegment(speakerName, text, requirements)
                    : llmIntegration.summarizeSpeakerSegmentRetry(speakerName, text);
        }
        return raw;
    }

    private void sleepBeforeRetry(int attempt) {
        long delayMs = Math.min(REFUSAL_RETRY_MAX_DELAY_MS, REFUSAL_RETRY_BASE_DELAY_MS * Math.max(1, attempt));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Speaker summary retry interrupted", e);
        }
    }

    public SpeakerSummaryVo regenerate(Long id, String requirements) {
        SpeakerSummaryRecord record = mapper.findById(id);
        if (record == null) {
            throw new RuntimeException("发言摘要记录不存在: " + id);
        }
        String textSnippet = record.getTextSnippet();
        if (textSnippet == null || textSnippet.isBlank()) {
            throw new RuntimeException("原始文本缺失，无法重新生成");
        }
        String speakerName = record.getSpeakerName() != null && !record.getSpeakerName().isBlank()
                ? record.getSpeakerName()
                : (record.getSpeakerId() != null ? record.getSpeakerId() : "未知发言人");
        try {
            log.info("[SpeakerSummaryService] regenerate, id={}, speaker={}, hasRequirements={}", id, speakerName, requirements != null && !requirements.isBlank());
            String raw = llmIntegration.summarizeSpeakerSegment(speakerName, textSnippet, requirements);
            String[] parts = parseTitleAndContent(raw);
            record.setTitle(parts[0]);
            record.setSummary(parts[1]);
            mapper.updateSummary(record);
            return SpeakerSummaryVo.builder()
                    .speakerId(record.getSpeakerId())
                    .speakerName(record.getSpeakerName())
                    .title(parts[0])
                    .summary(parts[1])
                    .build();
        } catch (Exception e) {
            log.error("[SpeakerSummaryService] regenerate failed, id={}", id, e);
            throw new RuntimeException("发言摘要重新生成失败: " + e.getMessage(), e);
        }
    }

    public List<SpeakerSummaryRecord> getBySession(String sessionId) {
        List<SpeakerSummaryRecord> records = mapper.findBySessionId(sessionId);
        records.forEach(this::refreshUnusableRecord);
        return records;
    }

    private void refreshUnusableRecord(SpeakerSummaryRecord record) {
        if (record == null || !isUnusableSummary(record.getSummary())) {
            return;
        }
        if (record.getTextSnippet() == null || record.getTextSnippet().isBlank()) {
            log.warn("[SpeakerSummaryService] skip unusable summary refresh, missing textSnippet, id={}", record.getId());
            return;
        }
        String speakerName = record.getSpeakerName() != null && !record.getSpeakerName().isBlank()
                ? record.getSpeakerName()
                : record.getSpeakerId();
        try {
            log.warn("[SpeakerSummaryService] persisted speaker summary is unusable, regenerating, id={}, sessionId={}",
                    record.getId(), record.getSessionId());
            String raw = summarizeSpeakerUntilAccepted(speakerName, record.getTextSnippet());
            String[] parts = parseTitleAndContent(raw);
            record.setTitle(parts[0]);
            record.setSummary(parts[1]);
            mapper.updateSummary(record);
        } catch (Exception e) {
            log.warn("[SpeakerSummaryService] persisted speaker summary refresh failed, id={}", record.getId(), e);
        }
    }
}
