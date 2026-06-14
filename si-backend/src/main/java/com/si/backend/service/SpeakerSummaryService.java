package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.integration.MeetingBotIntegration;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import com.si.backend.service.UserPreferenceService;
import com.si.backend.vo.SpeakerSummaryVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpeakerSummaryService {

    private static final long REFUSAL_RETRY_BASE_DELAY_MS = 2_000L;
    private static final long REFUSAL_RETRY_MAX_DELAY_MS = 10_000L;
    private static final int SUMMARY_MAX_ATTEMPTS = 3;
    private static final int MOJIBAKE_MIN_MARKERS = 4;
    private static final double MOJIBAKE_MARKER_RATIO = 0.08;
    private static final String MOJIBAKE_MARKERS = "ÃÂäåæçèéïð¤½¼¿º¦§©¢€™Œœž";
    /**
     * Safety cap on a single speaker's accumulated text (keeps the most recent). High enough to
     * effectively hold a whole meeting's worth of one person's speech — Claude's context is ~200k
     * tokens, so this only guards against pathological runaway, it does not truncate real meetings.
     */
    private static final int MAX_SPEAKER_TEXT_CHARS = 100000;
    private static final String TITLE_PREFIX = "\u6807\u9898";

    private static final long NOTIFICATION_RETRY_DELAY_S = 10L;

    private final LlmIntegration llmIntegration;
    private final SpeakerSummaryRecordMapper mapper;
    private final ContentEmbeddingService contentEmbeddingService;
    private final InterpretationSessionMapper interpretationSessionMapper;
    private final MeetingBotIntegration meetingBotIntegration;
    private final UserPreferenceService userPreferenceService;

    private final ScheduledExecutorService notifyRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "notify-retry");
                t.setDaemon(true);
                return t;
            });

    /** sessionId → 已发送过通知的 speakerName 集合，保证每人只发一次 */
    private final Map<String, java.util.Set<String>> sessionSentSpeakers = new java.util.concurrent.ConcurrentHashMap<>();

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

    static boolean isUnusableSummary(String response) {
        return response == null || response.isBlank() || isRefusal(response) || isMojibake(response);
    }

    private static boolean isMojibake(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        int visibleChars = 0;
        int markerCount = 0;
        for (int codePoint : response.codePoints().toArray()) {
            if (!Character.isWhitespace(codePoint)) {
                visibleChars++;
            }
            if (codePoint == 0xFFFD || MOJIBAKE_MARKERS.indexOf(codePoint) >= 0) {
                markerCount++;
            }
        }
        if (response.indexOf('\uFFFD') >= 0) {
            return true;
        }
        return markerCount >= MOJIBAKE_MIN_MARKERS
                && markerCount / (double) Math.max(1, visibleChars) >= MOJIBAKE_MARKER_RATIO;
    }

    public SpeakerSummaryVo summarize(String speakerName, String text, String sessionId, String speakerId) {
        return summarize(speakerName, text, sessionId, speakerId, null);
    }

    public SpeakerSummaryVo summarize(String speakerName, String text, String sessionId, String speakerId, String requirements) {
        log.info("[SpeakerSummaryService] summarize start, speaker={}, textLen={}", speakerName, text.length());
        try {
            // One summary per person per session: accumulate this speaker's full text across the
            // meeting and (re)generate a single summary, triggered on each confirmed speaker change.
            SpeakerSummaryRecord existing = null;
            try {
                existing = mapper.findBySessionIdAndSpeakerName(sessionId, speakerName);
            } catch (Exception e) {
                log.warn("[SpeakerSummaryService] lookup existing summary failed, sessionId={}, speaker={}: {}",
                        sessionId, speakerName, e.getMessage());
            }
            String cumulativeText = text;
            if (existing != null && existing.getTextSnippet() != null && !existing.getTextSnippet().isBlank()) {
                cumulativeText = (existing.getTextSnippet() + " " + text).trim();
            }
            if (cumulativeText.length() > MAX_SPEAKER_TEXT_CHARS) {
                cumulativeText = cumulativeText.substring(cumulativeText.length() - MAX_SPEAKER_TEXT_CHARS);
            }

            String raw = summarizeSpeakerUntilAccepted(speakerName, cumulativeText, requirements);
            String[] parts = parseTitleAndContent(raw);
            String title = parts[0];
            String summary = parts[1];
            log.info("[SpeakerSummaryService] summarize done, speaker={}, title={}, cumulativeLen={}, mode={}",
                    speakerName, title, cumulativeText.length(), existing != null ? "update" : "insert");
            Long recordId = null;
            try {
                if (existing != null) {
                    existing.setTitle(title);
                    existing.setSummary(summary);
                    existing.setTextSnippet(cumulativeText);
                    if (existing.getSpeakerId() == null && speakerId != null) {
                        existing.setSpeakerId(speakerId);
                    }
                    mapper.updateSummaryAndSnippet(existing);
                    recordId = existing.getId();
                } else {
                    SpeakerSummaryRecord record = SpeakerSummaryRecord.builder()
                            .sessionId(sessionId)
                            .speakerId(speakerId)
                            .speakerName(speakerName)
                            .title(title)
                            .textSnippet(cumulativeText)
                            .summary(summary)
                            .build();
                    mapper.insert(record);
                    recordId = record.getId();
                }
                if (recordId != null) {
                    // asyncEmbedSpeakerSummary upserts by record id, so re-embedding on update is safe.
                    contentEmbeddingService.asyncEmbedSpeakerSummary(
                            recordId, sessionId, speakerName, title, summary);
                }
            } catch (Exception e) {
                log.warn("[SpeakerSummaryService] failed to persist summary, sessionId={}", sessionId, e);
            }
            autoSendToSessionOwner(sessionId, speakerName, title, summary);
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

    private String summarizeSpeakerUntilAccepted(String speakerName, String text, String requirements) throws Exception {
        String raw = null;
        Exception lastException = null;
        for (int attempt = 1; attempt <= SUMMARY_MAX_ATTEMPTS; attempt++) {
            try {
                raw = (attempt == 1 || (requirements != null && !requirements.isBlank()))
                        ? llmIntegration.summarizeSpeakerSegment(speakerName, text, requirements)
                        : llmIntegration.summarizeSpeakerSegmentRetry(speakerName, text);
                if (!isUnusableSummary(raw)) return raw;
                log.warn("[SpeakerSummaryService] unusable LLM speaker summary detected, retrying, speaker={}, attempt={}, mojibake={}",
                        speakerName, attempt, isMojibake(raw));
            } catch (Exception e) {
                lastException = e;
                log.warn("[SpeakerSummaryService] LLM call failed, retrying, speaker={}, attempt={}: {}",
                        speakerName, attempt, e.getMessage());
            }
            if (attempt < SUMMARY_MAX_ATTEMPTS) sleepBeforeRetry(attempt);
        }
        if (raw != null && !isUnusableSummary(raw)) return raw;
        throw lastException != null ? lastException : new IllegalStateException("发言摘要连续生成异常，请稍后重试");
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
            String raw = summarizeSpeakerUntilAccepted(speakerName, textSnippet, requirements);
            String[] parts = parseTitleAndContent(raw);
            record.setTitle(parts[0]);
            record.setSummary(parts[1]);
            mapper.updateSummary(record);
            contentEmbeddingService.asyncEmbedSpeakerSummary(
                    record.getId(), record.getSessionId(), speakerName, parts[0], parts[1]);
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
        for (SpeakerSummaryRecord record : records) {
            if (isUnusableSummary(record.getSummary())
                    && record.getTextSnippet() != null
                    && !record.getTextSnippet().isBlank()) {
                repairMojibake(record);
            }
        }
        return records;
    }

    private void repairMojibake(SpeakerSummaryRecord record) {
        String speakerName = record.getSpeakerName() != null && !record.getSpeakerName().isBlank()
                ? record.getSpeakerName()
                : (record.getSpeakerId() != null ? record.getSpeakerId() : "未知发言人");
        try {
            log.warn("[SpeakerSummaryService] mojibake detected, repairing in-place, id={}, speaker={}",
                    record.getId(), speakerName);
            String raw = llmIntegration.summarizeSpeakerSegment(speakerName, record.getTextSnippet(), null);
            if (!isUnusableSummary(raw)) {
                record.setSummary(raw);
                mapper.updateSummary(record);
                contentEmbeddingService.asyncEmbedSpeakerSummary(
                        record.getId(), record.getSessionId(), speakerName, record.getTitle(), raw);
                log.info("[SpeakerSummaryService] mojibake repaired, id={}", record.getId());
            }
        } catch (Exception e) {
            log.warn("[SpeakerSummaryService] mojibake repair failed, id={}", record.getId(), e);
        }
    }

    public SpeakerSummaryRecord update(Long id, String speakerName, String summary) {
        log.info("[SpeakerSummaryService] update start, id={}, speaker={}", id, speakerName);
        SpeakerSummaryRecord record = mapper.findById(id);
        if (record == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "发言摘要记录不存在: " + id);
        }
        record.setSpeakerName(speakerName.trim());
        record.setSummary(summary.trim());
        mapper.updateEditableFields(record);
        contentEmbeddingService.asyncEmbedSpeakerSummary(
                record.getId(), record.getSessionId(), record.getSpeakerName(), record.getTitle(), record.getSummary());
        log.info("[SpeakerSummaryService] update end, id={}, speaker={}", id, record.getSpeakerName());
        return record;
    }

    /**
     * 摘要生成后自动发送给会话创建者配置的收件人（存储在 user preference 表）。
     * 发送失败不影响摘要保存结果，仅记录 warn 日志。
     */
    private void autoSendToSessionOwner(String sessionId, String speakerName, String title, String summary) {
        try {
            java.util.Set<String> sent = sessionSentSpeakers
                    .computeIfAbsent(sessionId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
            if (!sent.add(speakerName)) {
                log.debug("[SpeakerSummaryService] auto-send skipped, already sent for speaker, sessionId={}, speaker={}", sessionId, speakerName);
                return;
            }
            InterpretationSession session = interpretationSessionMapper.findBySessionId(sessionId);
            if (session == null || session.getUserId() == null) return;
            List<String> recipients = userPreferenceService.getSummaryRecipients(session.getUserId());
            if (recipients.isEmpty()) {
                log.debug("[SpeakerSummaryService] auto-send skipped, no configured recipients, sessionId={}", sessionId);
                return;
            }
            String content = buildAutoSendContent(speakerName, title, summary);
            trySendWithRetry(content, recipients, sessionId, speakerName, false);
        } catch (Exception e) {
            log.warn("[SpeakerSummaryService] auto-send failed, sessionId={}, speaker={}: {}", sessionId, speakerName, e.getMessage());
        }
    }

    private void trySendWithRetry(String content, List<String> recipients,
                                   String sessionId, String speakerName, boolean isRetry) {
        try {
            meetingBotIntegration.sendNotification(content, recipients);
            log.info("[SpeakerSummaryService] auto-send done, sessionId={}, speaker={}, recipientCount={}, retry={}",
                    sessionId, speakerName, recipients.size(), isRetry);
        } catch (Exception e) {
            if (isRetry) {
                log.warn("[SpeakerSummaryService] auto-send retry also failed, sessionId={}, speaker={}: {}",
                        sessionId, speakerName, e.getMessage());
            } else {
                log.warn("[SpeakerSummaryService] auto-send failed, will retry in {}s, sessionId={}, speaker={}: {}",
                        NOTIFICATION_RETRY_DELAY_S, sessionId, speakerName, e.getMessage());
                notifyRetryExecutor.schedule(
                        () -> trySendWithRetry(content, recipients, sessionId, speakerName, true),
                        NOTIFICATION_RETRY_DELAY_S, TimeUnit.SECONDS);
            }
        }
    }

    private static String buildAutoSendContent(String speakerName, String title, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("【发言摘要】").append(speakerName);
        if (title != null && !title.isBlank()) {
            sb.append("\n标题：").append(title);
        }
        sb.append("\n\n").append(summary != null ? summary.trim() : "");
        return sb.toString();
    }

}
