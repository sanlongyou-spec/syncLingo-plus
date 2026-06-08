package com.si.backend.service;

import com.si.backend.config.CostRatesProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.common.Constants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同传会话服务，管理会话生命周期与状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterpretationSessionService {

    private final InterpretationSessionMapper sessionMapper;
    private final CostRatesProperties costRatesProperties;
    private final ContentEmbeddingService contentEmbeddingService;
    private final Map<String, InterpretationSession> activeSessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void initColumns() {
        log.info("[InterpretationSessionService] initColumns start");
        addColumnIfMissing("asr_audio_ms", sessionMapper::addAsrAudioMsColumnIfNotExists);
        addColumnIfMissing("translate_chars", sessionMapper::addTranslateCharsColumnIfNotExists);
        addColumnIfMissing("tts_chars", sessionMapper::addTtsCharsColumnIfNotExists);
        addColumnIfMissing("llm_input_tokens", sessionMapper::addLlmInputTokensColumnIfNotExists);
        addColumnIfMissing("llm_output_tokens", sessionMapper::addLlmOutputTokensColumnIfNotExists);
        addColumnIfMissing("llm_summary_input_tokens", sessionMapper::addLlmSummaryInputTokensColumnIfNotExists);
        addColumnIfMissing("llm_summary_output_tokens", sessionMapper::addLlmSummaryOutputTokensColumnIfNotExists);
        addColumnIfMissing("title", sessionMapper::addTitleColumnIfNotExists);
        addColumnIfMissing("deleted", sessionMapper::addDeletedColumnIfNotExists);
        addColumnIfMissing("hotword_ids", sessionMapper::addHotwordIdsColumnIfNotExists);
        addColumnIfMissing("enabled_languages", sessionMapper::addEnabledLanguagesColumnIfNotExists);
        addColumnIfMissing("meeting_summary", sessionMapper::addMeetingSummaryColumnIfNotExists);
        addColumnIfMissing("meeting_id", sessionMapper::addMeetingIdColumnIfNotExists);
        log.info("[InterpretationSessionService] initColumns end");
    }

    private void addColumnIfMissing(String columnName, Runnable ddlAction) {
        try {
            ddlAction.run();
            log.info("[InterpretationSessionService] column added, column={}", columnName);
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.info("[InterpretationSessionService] column already exists, column={}", columnName);
                return;
            }
            throw e;
        }
    }

    /**
     * 启动同传会话，创建会话记录并存入数据库。
     *
     * @param sessionId  会话 ID
     * @param userId     用户 ID
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @param voiceId    音色 ID
     * @return 会话实体
     */
    @Transactional
    public InterpretationSession startSession(
            String sessionId,
            Long userId,
            String sourceLang,
            String targetLang,
            String title,
            String voiceId,
            List<Long> hotwordIds,
            List<String> enabledLanguages,
            Long meetingId
    ) {
        log.info("[InterpretationSessionService] startSession start, sessionId={}, userId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, userId, sourceLang, targetLang, voiceId);

        InterpretationSession session = new InterpretationSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setSourceLang(sourceLang);
        session.setTargetLang(targetLang);
        session.setVoiceId(voiceId);
        session.setHotwordIds(joinHotwordIds(hotwordIds));
        session.setEnabledLanguages(joinLanguages(enabledLanguages));
        session.setTitle(normalizeTitle(title));
        session.setMeetingId(meetingId);
        session.setStatus(Constants.SESSION_STATUS_RUNNING);
        session.setDeleted(false);
        session.setStartTime(LocalDateTime.now());
        session.setAsrAudioMs(0L);
        session.setTranslateChars(0L);
        session.setTtsChars(0L);
        session.setLlmInputTokens(0L);
        session.setLlmOutputTokens(0L);
        session.setLlmSummaryInputTokens(0L);
        session.setLlmSummaryOutputTokens(0L);

        sessionMapper.insert(session);
        activeSessions.put(sessionId, session);
        log.info("[InterpretationSessionService] startSession end, sessionId={}, status={}",
                sessionId, session.getStatus());
        return session;
    }

    /**
     * 停止同传会话，更新状态并从内存移除。
     * Returns a map that may contain a {@code budgetWarning} string if session or monthly costs
     * exceed configured thresholds.
     */
    @Transactional
    public Map<String, Object> stopSession(String sessionId) {
        log.info("[InterpretationSessionService] stopSession start, sessionId={}", sessionId);
        InterpretationSession session = activeSessions.remove(sessionId);
        LocalDateTime endTime = LocalDateTime.now();
        if (session != null) {
            session.setStatus(Constants.SESSION_STATUS_STOPPED);
            session.setEndTime(endTime);
            sessionMapper.updateStatus(sessionId, Constants.SESSION_STATUS_STOPPED, endTime);
            log.info("[InterpretationSessionService] stopSession end, sessionId={}", sessionId);
        } else {
            InterpretationSession dbSession = sessionMapper.findBySessionId(sessionId);
            if (dbSession == null) {
                log.warn("[InterpretationSessionService] stopSession skip, session not found, sessionId={}", sessionId);
                return Map.of();
            }
            if (Constants.SESSION_STATUS_STOPPED.equals(dbSession.getStatus())) {
                log.info("[InterpretationSessionService] stopSession skip, already stopped, sessionId={}", sessionId);
                return Map.of();
            }
            sessionMapper.updateStatus(sessionId, Constants.SESSION_STATUS_STOPPED, endTime);
            session = dbSession;
            log.info("[InterpretationSessionService] stopSession end (from db), sessionId={}", sessionId);
        }
        return buildStopResult(session);
    }

    private Map<String, Object> buildStopResult(InterpretationSession session) {
        CostRatesProperties.Budget budget = costRatesProperties.getBudget();
        CostRatesProperties.Rates rates = costRatesProperties.getRates();
        double sessionCost = calcSessionCost(session, rates);
        Map<String, Object> result = new HashMap<>();
        result.put("sessionCostUsd", sessionCost);

        if (budget.getSessionUsd() > 0 && sessionCost > budget.getSessionUsd()) {
            result.put("budgetWarning", String.format(
                    "本次会话费用 $%.4f 已超出单次预算 $%.2f", sessionCost, budget.getSessionUsd()));
        }

        if (budget.getMonthlyUsd() > 0 && session.getUserId() != null) {
            String yearMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            Map<String, Object> monthly = sessionMapper.currentMonthSummaryByUser(session.getUserId(), yearMonth);
            if (monthly != null) {
                double monthlyCost = calcCostFromUsageMap(monthly, rates);
                result.put("monthlyCostUsd", monthlyCost);
                if (monthlyCost > budget.getMonthlyUsd()) {
                    result.put("budgetWarning", String.format(
                            "本月累计费用 $%.4f 已超出月度预算 $%.2f", monthlyCost, budget.getMonthlyUsd()));
                }
            }
        }
        return result;
    }

    private double calcSessionCost(InterpretationSession s, CostRatesProperties.Rates r) {
        long asrMs = s.getAsrAudioMs() != null ? s.getAsrAudioMs() : 0L;
        long tc    = s.getTranslateChars() != null ? s.getTranslateChars() : 0L;
        long tts   = s.getTtsChars() != null ? s.getTtsChars() : 0L;
        long llmIn = s.getLlmInputTokens() != null ? s.getLlmInputTokens() : 0L;
        long llmOut = s.getLlmOutputTokens() != null ? s.getLlmOutputTokens() : 0L;
        long sumIn = s.getLlmSummaryInputTokens() != null ? s.getLlmSummaryInputTokens() : 0L;
        long sumOut = s.getLlmSummaryOutputTokens() != null ? s.getLlmSummaryOutputTokens() : 0L;
        return asrMs  * r.getAsrPerHourUsd() / 3_600_000.0
             + tc     * r.getTransPerMillionCharsUsd() / 1_000_000.0
             + tts    * r.getTtsPerMillionCharsUsd() / 1_000_000.0
             + llmIn  * r.getLlmInPerMillionTokensUsd() / 1_000_000.0
             + llmOut * r.getLlmOutPerMillionTokensUsd() / 1_000_000.0
             + sumIn  * r.getSummaryLlmInPerMillionTokensUsd() / 1_000_000.0
             + sumOut * r.getSummaryLlmOutPerMillionTokensUsd() / 1_000_000.0;
    }

    private double calcCostFromUsageMap(Map<String, Object> row, CostRatesProperties.Rates r) {
        long asrMs  = toLong(row.get("totalAsrMs"));
        long tc     = toLong(row.get("totalTransChars"));
        long tts    = toLong(row.get("totalTtsChars"));
        long llmIn  = toLong(row.get("totalLlmIn"));
        long llmOut = toLong(row.get("totalLlmOut"));
        long sumIn  = toLong(row.get("totalSummaryLlmIn"));
        long sumOut = toLong(row.get("totalSummaryLlmOut"));
        return asrMs  * r.getAsrPerHourUsd() / 3_600_000.0
             + tc     * r.getTransPerMillionCharsUsd() / 1_000_000.0
             + tts    * r.getTtsPerMillionCharsUsd() / 1_000_000.0
             + llmIn  * r.getLlmInPerMillionTokensUsd() / 1_000_000.0
             + llmOut * r.getLlmOutPerMillionTokensUsd() / 1_000_000.0
             + sumIn  * r.getSummaryLlmInPerMillionTokensUsd() / 1_000_000.0
             + sumOut * r.getSummaryLlmOutPerMillionTokensUsd() / 1_000_000.0;
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return 0L; }
    }

    private String joinHotwordIds(List<Long> hotwordIds) {
        if (hotwordIds == null || hotwordIds.isEmpty()) {
            return null;
        }
        return hotwordIds.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse(null);
    }

    private String normalizeTitle(String title) {
        String normalizedTitle = title == null || title.isBlank() ? Constants.SESSION_DEFAULT_TITLE : title.trim();
        if (normalizedTitle.length() > 128) {
            return normalizedTitle.substring(0, 128);
        }
        return normalizedTitle;
    }

    private String joinLanguages(List<String> enabledLanguages) {
        if (enabledLanguages == null || enabledLanguages.isEmpty()) {
            return null;
        }
        return String.join(",", enabledLanguages);
    }

    /**
     * 获取会话（优先从内存，未找到则查数据库）。
     *
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    public Optional<InterpretationSession> getSession(String sessionId) {
        log.debug("[InterpretationSessionService] getSession, sessionId={}", sessionId);
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            log.debug("[InterpretationSessionService] getSession found in memory, sessionId={}", sessionId);
            return Optional.of(session);
        }
        InterpretationSession dbSession = sessionMapper.findBySessionId(sessionId);
        log.debug("[InterpretationSessionService] getSession end, sessionId={}, found={}", sessionId, dbSession != null);
        return Optional.ofNullable(dbSession);
    }

    /**
     * 判断会话是否处于运行状态。
     *
     * @param sessionId 会话 ID
     * @return 是否运行中
     */
    public boolean isSessionActive(String sessionId) {
        InterpretationSession session = activeSessions.get(sessionId);
        boolean active = session != null && Constants.SESSION_STATUS_RUNNING.equals(session.getStatus());
        log.debug("[InterpretationSessionService] isSessionActive, sessionId={}, active={}", sessionId, active);
        return active;
    }

    /**
     * 获取用户的所有会话列表。
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    public List<InterpretationSession> getUserSessions(Long userId) {
        log.info("[InterpretationSessionService] getUserSessions, userId={}", userId);
        List<InterpretationSession> sessions = sessionMapper.findByUserId(userId);
        log.info("[InterpretationSessionService] getUserSessions end, userId={}, count={}", userId, sessions.size());
        return sessions;
    }

    public List<InterpretationSession> findByMeetingId(Long meetingId) {
        return sessionMapper.findByMeetingId(meetingId);
    }

    public Optional<InterpretationSession> getActiveSessionForUser(Long userId) {
        InterpretationSession active = activeSessions.values().stream()
                .filter(s -> userId.equals(s.getUserId())
                        && Constants.SESSION_STATUS_RUNNING.equals(s.getStatus()))
                .findFirst()
                .orElse(null);
        if (active != null) {
            return Optional.of(active);
        }
        return Optional.ofNullable(sessionMapper.findActiveByUserId(userId));
    }

    public List<InterpretationSession> searchUserSessions(Long userId, String keyword) {
        log.info("[InterpretationSessionService] searchUserSessions start, userId={}, keyword={}", userId, keyword);
        List<InterpretationSession> sessions = sessionMapper.searchByUserId(userId, keyword);
        log.info("[InterpretationSessionService] searchUserSessions end, userId={}, count={}", userId, sessions.size());
        return sessions;
    }

    @Transactional
    public boolean updateTitle(String sessionId, Long userId, String title) {
        log.info("[InterpretationSessionService] updateTitle start, sessionId={}, userId={}", sessionId, userId);
        String normalizedTitle = normalizeTitle(title);
        int updated = sessionMapper.updateTitle(sessionId, userId, normalizedTitle);
        InterpretationSession active = activeSessions.get(sessionId);
        if (active != null && userId.equals(active.getUserId())) {
            active.setTitle(normalizedTitle);
        }
        log.info("[InterpretationSessionService] updateTitle end, sessionId={}, updated={}", sessionId, updated);
        return updated > 0;
    }

    @Transactional
    public boolean deleteSession(String sessionId, Long userId) {
        log.info("[InterpretationSessionService] deleteSession start, sessionId={}, userId={}", sessionId, userId);
        int updated = sessionMapper.softDelete(sessionId, userId);
        activeSessions.remove(sessionId);
        if (updated > 0) {
            contentEmbeddingService.deleteBySessionId(sessionId);
        }
        log.info("[InterpretationSessionService] deleteSession end, sessionId={}, updated={}", sessionId, updated);
        return updated > 0;
    }

    public void addAsrAudioMs(String sessionId, long delta) {
        log.debug("[InterpretationSessionService] addAsrAudioMs, sessionId={}, delta={}", sessionId, delta);
        sessionMapper.addAsrAudioMs(sessionId, Math.max(delta, 0));
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setAsrAudioMs((session.getAsrAudioMs() != null ? session.getAsrAudioMs() : 0) + Math.max(delta, 0));
        }
    }

    public void addTranslateChars(String sessionId, long delta) {
        log.debug("[InterpretationSessionService] addTranslateChars, sessionId={}, delta={}", sessionId, delta);
        sessionMapper.addTranslateChars(sessionId, Math.max(delta, 0));
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setTranslateChars((session.getTranslateChars() != null ? session.getTranslateChars() : 0) + Math.max(delta, 0));
        }
    }

    public void addTtsChars(String sessionId, long delta) {
        log.debug("[InterpretationSessionService] addTtsChars, sessionId={}, delta={}", sessionId, delta);
        sessionMapper.addTtsChars(sessionId, Math.max(delta, 0));
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setTtsChars((session.getTtsChars() != null ? session.getTtsChars() : 0) + Math.max(delta, 0));
        }
    }

    public void saveMeetingSummary(String sessionId, String summary) {
        log.info("[InterpretationSessionService] saveMeetingSummary, sessionId={}", sessionId);
        sessionMapper.updateMeetingSummary(sessionId, summary);
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setMeetingSummary(summary);
        }
    }

    public void addLlmTokens(String sessionId, long inputDelta, long outputDelta) {
        log.debug("[InterpretationSessionService] addLlmTokens, sessionId={}, inputDelta={}, outputDelta={}",
                sessionId, inputDelta, outputDelta);
        sessionMapper.addLlmTokens(sessionId, Math.max(inputDelta, 0), Math.max(outputDelta, 0));
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setLlmInputTokens((session.getLlmInputTokens() != null ? session.getLlmInputTokens() : 0) + Math.max(inputDelta, 0));
            session.setLlmOutputTokens((session.getLlmOutputTokens() != null ? session.getLlmOutputTokens() : 0) + Math.max(outputDelta, 0));
        }
    }

    /** Summary/document LLM tokens (DeepSeek) — billed separately from realtime compression (Haiku). */
    public void addSummaryLlmTokens(String sessionId, long inputDelta, long outputDelta) {
        log.debug("[InterpretationSessionService] addSummaryLlmTokens, sessionId={}, inputDelta={}, outputDelta={}",
                sessionId, inputDelta, outputDelta);
        sessionMapper.addSummaryLlmTokens(sessionId, Math.max(inputDelta, 0), Math.max(outputDelta, 0));
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setLlmSummaryInputTokens((session.getLlmSummaryInputTokens() != null ? session.getLlmSummaryInputTokens() : 0) + Math.max(inputDelta, 0));
            session.setLlmSummaryOutputTokens((session.getLlmSummaryOutputTokens() != null ? session.getLlmSummaryOutputTokens() : 0) + Math.max(outputDelta, 0));
        }
    }

    /**
     * 获取会话完整历史（含识别文本、译文等）。
     *
     * @param sessionId 会话 ID
     * @return 会话实体，若不存在返回 null
     */
    public InterpretationSession getSessionHistory(String sessionId) {
        log.info("[InterpretationSessionService] getSessionHistory start, sessionId={}", sessionId);
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            log.debug("[InterpretationSessionService] getSessionHistory found in memory, sessionId={}", sessionId);
            log.info("[InterpretationSessionService] getSessionHistory end, sessionId={}, found={}", sessionId, true);
            return session;
        }
        InterpretationSession dbSession = sessionMapper.findBySessionId(sessionId);
        log.info("[InterpretationSessionService] getSessionHistory end, sessionId={}, found={}", sessionId, dbSession != null);
        return dbSession;
    }
}
