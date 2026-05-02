package com.si.backend.service;

import com.si.backend.entity.InterpretationSession;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final Map<String, InterpretationSession> activeSessions = new ConcurrentHashMap<>();

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
            String voiceId
    ) {
        log.info("[InterpretationSessionService] startSession start, sessionId={}, userId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, userId, sourceLang, targetLang, voiceId);

        InterpretationSession session = new InterpretationSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setSourceLang(sourceLang);
        session.setTargetLang(targetLang);
        session.setVoiceId(voiceId);
        session.setStatus(Constants.SESSION_STATUS_RUNNING);
        session.setStartTime(LocalDateTime.now());

        sessionMapper.insert(session);
        activeSessions.put(sessionId, session);
        log.info("[InterpretationSessionService] startSession end, sessionId={}, status={}",
                sessionId, session.getStatus());
        return session;
    }

    /**
     * 停止同传会话，更新状态并从内存移除。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    public void stopSession(String sessionId) {
        log.info("[InterpretationSessionService] stopSession start, sessionId={}", sessionId);
        InterpretationSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setStatus(Constants.SESSION_STATUS_STOPPED);
            session.setEndTime(LocalDateTime.now());
            sessionMapper.updateStatus(sessionId, Constants.SESSION_STATUS_STOPPED, session.getEndTime());
            activeSessions.remove(sessionId);
            log.info("[InterpretationSessionService] stopSession end, sessionId={}, status={}",
                    sessionId, session.getStatus());
        } else {
            log.warn("[InterpretationSessionService] stopSession skip, session not found, sessionId={}", sessionId);
        }
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
