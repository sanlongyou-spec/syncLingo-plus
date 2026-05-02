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

    private final Map<String, AsrSessionContext> activeSessions = new ConcurrentHashMap<>();

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
            String sourceLang,
            AsrCallback onRecognizing,
            AsrCallback onRecognized,
            AsrErrorCallback onError
    ) {
        log.info("[AsrService] startRecognition start, sessionId={}, sourceLang={}", sessionId, sourceLang);

        if (activeSessions.containsKey(sessionId)) {
            log.warn("[AsrService] session already exists, closing old one, sessionId={}", sessionId);
            stopRecognition(sessionId);
        }

        AzureAsrIntegration.AsrSession asrSession;
        try {
            asrSession = asrIntegration.createSession(sessionId, sourceLang);
        } catch (Exception e) {
            log.error("[AsrService] createSession failed, sessionId={}", sessionId, e);
            onError.onError("ASR 会话创建失败: " + e.getMessage());
            return;
        }

        AsrSessionContext context = new AsrSessionContext(sessionId, asrSession);
        activeSessions.put(sessionId, context);

        asrSession.setCallback(new AzureAsrIntegration.RecognizerCallback() {
            @Override
            public void onRecognizing(String text, String language, boolean isFinal) {
                log.info("[AsrService] ASR recognizing, sessionId={}, isFinal={}, text={}, lang={}", sessionId, isFinal, text, language);
                if (isFinal) {
                    onRecognized.onResult(text, language);
                } else {
                    onRecognizing.onResult(text, language);
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
        log.trace("[AsrService] pushAudio, sessionId={}, bytes={}", sessionId, pcmFrame.length);
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
        void onResult(String text, String detectedLanguage);
    }

    @FunctionalInterface
    public interface AsrErrorCallback {
        void onError(String errorMessage);
    }
}
