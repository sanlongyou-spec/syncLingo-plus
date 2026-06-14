package com.si.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 实时同传过程中跟踪说话人切换并触发发言摘要。
 *
 * 切换确认规则：新说话人候选累积满 MIN_SWITCH_CONFIRM_CHARS 字符后确认切换。
 * 摘要触发规则：前一位说话人本轮发言时长 ≥ MIN_SPEAKING_DURATION_MS（1 分钟）
 *               且文本不少于 MIN_SUMMARY_CHARS 字符。
 *
 * SpeakerSummaryService 内部会将各轮文本累计到数据库，无需在此维护跨轮全量文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeakerTurnService {

    /** 新说话人候选至少积累多少字符才确认切换（防止短句误判） */
    private static final int MIN_SWITCH_CONFIRM_CHARS = 30;
    /** 发言文本字符下限（过短不值得生成摘要） */
    private static final int MIN_SUMMARY_CHARS = 30;
    /** 发言时长下限：至少 1.5 分钟才生成摘要（说话人切换时触发） */
    private static final long MIN_SPEAKING_DURATION_MS = 90_000L;

    private final SpeakerSummaryService speakerSummaryService;
    private final SessionSpeakerNameService sessionSpeakerNameService;

    private static final Executor SUMMARY_EXECUTOR = new ThreadPoolExecutor(
            2, 6, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /** sessionId → 当前已确认的说话人 ID */
    private final ConcurrentHashMap<String, String> prevSpeakerMap = new ConcurrentHashMap<>();
    /** "sessionId\0speakerId" → 本轮已确认文本缓冲 */
    private final ConcurrentHashMap<String, StringBuilder> speakerBuffers = new ConcurrentHashMap<>();
    /** "sessionId\0speakerId" → 本轮开始时间（毫秒），从候选首次出现开始计 */
    private final ConcurrentHashMap<String, Long> speakerTurnStartMs = new ConcurrentHashMap<>();
    /** sessionId → 待确认的候选说话人 */
    private final ConcurrentHashMap<String, PendingCandidate> pendingMap = new ConcurrentHashMap<>();

    /** 候选说话人：speakerId + 累积文本 + 首次出现时间 */
    private record PendingCandidate(String speakerId, StringBuilder buffer, long startMs) {}

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 每次 ASR final recognition 时调用。
     * 必须在同一 sessionId 的 final-recognition 链路中串行调用（无需额外同步）。
     */
    public void processRecognized(String sessionId, String speakerId, String text) {
        if (text == null || text.isBlank()) return;
        String trimmed = text.trim();
        String prevSpeaker = prevSpeakerMap.get(sessionId);

        if (isUnknownSpeaker(speakerId)) {
            // Unknown 归属于上一位已知说话人
            if (prevSpeaker != null && !isUnknownSpeaker(prevSpeaker)) {
                appendBuffer(sessionId, prevSpeaker, trimmed);
            }
            return;
        }

        if (speakerId.equals(prevSpeaker)) {
            // 同一说话人继续 — 把之前的候选 pending 回退给当前说话人（误判修正）
            PendingCandidate pending = pendingMap.remove(sessionId);
            if (pending != null) {
                appendBuffer(sessionId, speakerId, pending.buffer().toString());
            }
            appendBuffer(sessionId, speakerId, trimmed);

        } else {
            PendingCandidate pending = pendingMap.get(sessionId);

            if (pending != null && pending.speakerId().equals(speakerId)) {
                // 同一候选继续积累
                pending.buffer().append(' ').append(trimmed);

                if (pending.buffer().length() >= MIN_SWITCH_CONFIRM_CHARS) {
                    // 确认切换：对前一位说话人检查时长后触发摘要
                    pendingMap.remove(sessionId);
                    if (prevSpeaker != null && !isUnknownSpeaker(prevSpeaker)) {
                        maybeTriggerSummary(sessionId, prevSpeaker, "switch");
                        speakerBuffers.remove(bufferKey(sessionId, prevSpeaker));
                        speakerTurnStartMs.remove(bufferKey(sessionId, prevSpeaker));
                    }
                    // 建立新说话人，本轮开始时间追溯到候选首次出现
                    appendBuffer(sessionId, speakerId, pending.buffer().toString());
                    speakerTurnStartMs.put(bufferKey(sessionId, speakerId), pending.startMs());
                    prevSpeakerMap.put(sessionId, speakerId);
                    log.info("[SpeakerTurnService] speaker switch confirmed, sessionId={}, {}→{}",
                            sessionId, prevSpeaker, speakerId);
                }

            } else {
                // 新候选出现 — 把旧候选 pending 回退给当前已确认说话人（旧的是误判）
                if (pending != null && prevSpeaker != null) {
                    appendBuffer(sessionId, prevSpeaker, pending.buffer().toString());
                }
                pendingMap.put(sessionId,
                        new PendingCandidate(speakerId, new StringBuilder(trimmed), System.currentTimeMillis()));
            }
        }
    }

    /**
     * 会话结束时调用，对剩余说话人触发最终摘要。
     * 需在 cleanupSession 之前调用。
     */
    public void flushSession(String sessionId) {
        PendingCandidate pending = pendingMap.remove(sessionId);
        String prevSpeaker = prevSpeakerMap.get(sessionId);

        if (pending != null) {
            if (pending.buffer().length() >= MIN_SWITCH_CONFIRM_CHARS) {
                // 会话结束时候选话够长，确认切换
                if (prevSpeaker != null && !isUnknownSpeaker(prevSpeaker)) {
                    maybeTriggerSummary(sessionId, prevSpeaker, "flush-switch");
                    speakerBuffers.remove(bufferKey(sessionId, prevSpeaker));
                    speakerTurnStartMs.remove(bufferKey(sessionId, prevSpeaker));
                }
                appendBuffer(sessionId, pending.speakerId(), pending.buffer().toString());
                speakerTurnStartMs.put(bufferKey(sessionId, pending.speakerId()), pending.startMs());
                prevSpeaker = pending.speakerId();
                prevSpeakerMap.put(sessionId, prevSpeaker);
            } else {
                // 太短 — 归回当前说话人
                if (prevSpeaker != null) {
                    appendBuffer(sessionId, prevSpeaker, pending.buffer().toString());
                }
            }
        }

        // 刷出最后一位说话人
        if (prevSpeaker != null && !isUnknownSpeaker(prevSpeaker)) {
            maybeTriggerSummary(sessionId, prevSpeaker, "flush-last");
        }
    }

    /** 清除会话的所有内存状态。flushSession 之后调用。 */
    public void cleanupSession(String sessionId) {
        prevSpeakerMap.remove(sessionId);
        pendingMap.remove(sessionId);
        String prefix = sessionId + "\0";
        speakerBuffers.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        speakerTurnStartMs.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    /**
     * 检查说话人本轮时长和文本量，满足条件才异步触发摘要。
     */
    private void maybeTriggerSummary(String sessionId, String speakerId, String reason) {
        String text = getBuffer(sessionId, speakerId).toString();
        if (text.length() < MIN_SUMMARY_CHARS) {
            log.debug("[SpeakerTurnService] skip summary (too short), sessionId={}, speakerId={}, chars={}, reason={}",
                    sessionId, speakerId, text.length(), reason);
            return;
        }
        Long startMs = speakerTurnStartMs.get(bufferKey(sessionId, speakerId));
        long durationMs = startMs != null ? System.currentTimeMillis() - startMs : 0L;
        if (durationMs < MIN_SPEAKING_DURATION_MS) {
            log.info("[SpeakerTurnService] skip summary (too short duration), sessionId={}, speakerId={}, durationMs={}, reason={}",
                    sessionId, speakerId, durationMs, reason);
            return;
        }
        triggerSummaryAsync(sessionId, speakerId, text, durationMs, reason);
    }

    private void triggerSummaryAsync(String sessionId, String speakerId, String text, long durationMs, String reason) {
        String name = sessionSpeakerNameService.getName(sessionId, speakerId);
        if (name == null || name.isBlank()) name = speakerId;
        final String speakerName = name;
        log.info("[SpeakerTurnService] triggerSummaryAsync, sessionId={}, speakerId={}, speakerName={}, textLen={}, durationMs={}, reason={}",
                sessionId, speakerId, speakerName, text.length(), durationMs, reason);
        CompletableFuture.runAsync(() -> {
            try {
                speakerSummaryService.summarize(speakerName, text, sessionId, speakerId);
                log.info("[SpeakerTurnService] summary done, sessionId={}, speakerId={}", sessionId, speakerId);
            } catch (Exception e) {
                log.warn("[SpeakerTurnService] summary failed, sessionId={}, speakerId={}: {}",
                        sessionId, speakerId, e.getMessage());
            }
        }, SUMMARY_EXECUTOR);
    }

    private static boolean isUnknownSpeaker(String speakerId) {
        if (speakerId == null || speakerId.isBlank()) return true;
        // Azure ConversationTranscriber 使用 "Guest-N" 作为真实说话人 ID（即声纹分组后的 diarization ID）。
        // 只有字面量 "Unknown" 才是未识别说话人，Guest-* 不能视为未知。
        return speakerId.trim().equalsIgnoreCase("unknown");
    }

    private static String bufferKey(String sessionId, String speakerId) {
        return sessionId + "\0" + speakerId;
    }

    private StringBuilder getBuffer(String sessionId, String speakerId) {
        return speakerBuffers.computeIfAbsent(bufferKey(sessionId, speakerId), k -> new StringBuilder());
    }

    private void appendBuffer(String sessionId, String speakerId, String text) {
        StringBuilder buf = getBuffer(sessionId, speakerId);
        if (!buf.isEmpty()) buf.append(' ');
        buf.append(text.trim());
    }
}
