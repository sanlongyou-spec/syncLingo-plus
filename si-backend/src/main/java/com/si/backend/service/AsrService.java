package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.integration.AzureAsrIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASR 业务服务，管理 Azure 语音识别会话生命周期与回调注册。
 * 封装 ASR 业务逻辑，提供会话级别的识别接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrService {

    private final AzureAsrIntegration asrIntegration;
    private final AsrHotwordService asrHotwordService;

    private final Map<String, AsrSessionContext> activeSessions = new ConcurrentHashMap<>();

    /** 每个会话最近 final 分段的去重缓存: sessionId → (speakerId:textHash → emitTimeMs) */
    private final Map<String, Map<String, Long>> recentFinals = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 12_000;

    /**
     * 启动 ASR 连续识别。
     *
     * @param sessionId  会话 ID
     * @param sourceLang 源语言（可传 "auto" 或 null 触发自动检测）
     * @param onRecognizing 实时识别回调（isFinal=false）
     * @param onRecognized 最终识别回调（isFinal=true）
     * @param onError     错误回调
     */
    public void startRecognition(
            String sessionId,
            Long userId,
            String sourceLang,
            String selectedHotwordIds,
            String enabledLanguages,
            AsrCallback onRecognizing,
            AsrCallback onRecognized,
            AsrErrorCallback onError
    ) {
        log.info("[AsrService] startRecognition start, sessionId={}, userId={}, sourceLang={}", sessionId, userId, sourceLang);

        if (activeSessions.containsKey(sessionId)) {
            log.warn("[AsrService] session already exists, closing old one, sessionId={}", sessionId);
            stopRecognition(sessionId);
        }

        AzureAsrIntegration.AsrSession asrSession;
        try {
            String hotwordLanguage = "auto".equalsIgnoreCase(sourceLang) ? null : sourceLang;
            var selectedHotwords = asrHotwordService.filterSelected(
                    asrHotwordService.listActive(userId, hotwordLanguage),
                    selectedHotwordIds
            );
            // 去重：trim + 大小写不敏感，跨类别/跨语言过滤相同词汇，保留首次出现的原始写法
            java.util.Set<String> seenPhrases = new java.util.HashSet<>();
            var hotwords = selectedHotwords.stream()
                    .map(com.si.backend.entity.AsrHotword::getPhrase)
                    .filter(phrase -> phrase != null && !phrase.isBlank())
                    .map(String::trim)
                    .filter(phrase -> seenPhrases.add(phrase.toLowerCase()))
                    .toList();
            log.info("[AsrService] loading hotwords, sessionId={}, userId={}, count={}", sessionId, userId, hotwords.size());
            asrHotwordService.markUsed(userId, selectedHotwords.stream()
                    .map(com.si.backend.entity.AsrHotword::getId)
                    .filter(java.util.Objects::nonNull)
                    .toList());
            asrSession = asrIntegration.createSession(sessionId, sourceLang, hotwords, enabledLanguages);
        } catch (Exception e) {
            log.error("[AsrService] createSession failed, sessionId={}", sessionId, e);
            onError.onError("ASR 会话创建失败: " + e.getMessage());
            return;
        }

        AsrSessionContext context = new AsrSessionContext(sessionId, asrSession);
        activeSessions.put(sessionId, context);

        recentFinals.put(sessionId, new ConcurrentHashMap<>());
        asrSession.setCallback(new AzureAsrIntegration.RecognizerCallback() {
            @Override
            public void onRecognizing(String text, String language, String speakerId, boolean isFinal) {
                if (isFinal) {
                    if (text == null || text.isBlank()) return;
                    String dedupKey = (speakerId == null ? "" : speakerId) + ":" + text.hashCode() + ":" + text.length();
                    Map<String, Long> sessionDedup = recentFinals.get(sessionId);
                    if (sessionDedup != null) {
                        long now = System.currentTimeMillis();
                        Long prevMs = sessionDedup.get(dedupKey);
                        if (prevMs != null && now - prevMs < DEDUP_WINDOW_MS) {
                            log.warn("[AsrService] duplicate final segment suppressed, sessionId={}, speakerId={}, textLen={}",
                                    sessionId, speakerId, text.length());
                            return;
                        }
                        sessionDedup.put(dedupKey, now);
                    }
                    log.info("[AsrService] ASR recognized, sessionId={}, speakerId={}, textLen={}, lang={}",
                            sessionId, speakerId, text.length(), language);
                    onRecognized.onResult(text, language, speakerId);
                } else {
                    log.trace("[AsrService] ASR recognizing, sessionId={}, speakerId={}, textLen={}, lang={}",
                            sessionId, speakerId, text != null ? text.length() : 0, language);
                    onRecognizing.onResult(text, language, speakerId);
                }
            }

            @Override
            public void onError(String errorMessage) {
                log.error("[AsrService] ASR error, sessionId={}, error={}", sessionId, errorMessage);
                onError.onError(errorMessage);
            }
        });

        try {
            asrSession.startContinuous();
        } catch (Exception e) {
            log.error("[AsrService] startContinuous failed, sessionId={}", sessionId, e);
            onError.onError("ASR 启动失败: " + e.getMessage());
            stopRecognition(sessionId);
            return;
        }
        log.info("[AsrService] startRecognition end, sessionId={}", sessionId);
    }

    /**
     * 推送 PCM 音频帧到指定会话。
     *
     * @param sessionId 会话 ID
     * @param pcmFrame  PCM 音频数据
     */
    public void pushAudio(String sessionId, byte[] pcmFrame) {
        log.debug("[AsrService] pushAudio, sessionId={}, bytes={}", sessionId, pcmFrame.length);
        asrIntegration.pushAudio(sessionId, pcmFrame);
    }

    /**
     * 停止 ASR 连续识别并关闭会话。
     *
     * @param sessionId 会话 ID
     */
    public void stopRecognition(String sessionId) {
        log.info("[AsrService] stopRecognition start, sessionId={}", sessionId);
        asrIntegration.closeSession(sessionId);
        activeSessions.remove(sessionId);
        recentFinals.remove(sessionId);
        log.info("[AsrService] stopRecognition end, sessionId={}", sessionId);
    }

    /**
     * 检查指定会话是否处于活跃状态。
     *
     * @param sessionId 会话 ID
     * @return 是否活跃
     */
    public boolean isSessionActive(String sessionId) {
        return activeSessions.containsKey(sessionId);
    }

    /**
     * 获取活跃会话数量。
     *
     * @return 会话数
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    private static class AsrSessionContext {
        final String sessionId;
        final AzureAsrIntegration.AsrSession asrSession;

        AsrSessionContext(String sessionId, AzureAsrIntegration.AsrSession asrSession) {
            this.sessionId = sessionId;
            this.asrSession = asrSession;
        }
    }

    @FunctionalInterface
    public interface AsrCallback {
        void onResult(String text, String detectedLanguage, String speakerId);
    }

    @FunctionalInterface
    public interface AsrErrorCallback {
        void onError(String errorMessage);
    }
}
