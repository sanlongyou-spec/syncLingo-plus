package com.si.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Runs best-effort knowledge extraction for uploaded meeting materials.
 * Failures are logged and never block the upload flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingMaterialExtractionService {

    private static final String SOURCE_MEETING_AGENDA = "MEETING_AGENDA";
    private static final String SOURCE_MEETING_FILE = "MEETING_FILE";

    private final HotwordExtractionService hotwordExtractionService;
    private final MeetingKnowledgeService meetingKnowledgeService;
    private final TerminologyExtractionService terminologyExtractionService;
    private final AsrHotwordService asrHotwordService;

    public void enqueueFromPreMeetingFile(
            Long userId,
            String fileId,
            String fileName,
            String text,
            PreMeetingService.MeetingEntities entities) {
        // 会前独立上传(还未绑定会议)无 meetingId：只抽热词/实体(账号级)，知识包/术语等绑定会议后再抽。
        enqueue(userId, null, SOURCE_MEETING_AGENDA, fileId, fileName, text, entities);
    }

    public void enqueueFromMeetingFile(
            Long userId,
            Long meetingId,
            Long fileId,
            String fileName,
            String text) {
        String sourceRef = "meetingId=" + meetingId + ",fileId=" + fileId;
        enqueue(userId, meetingId, SOURCE_MEETING_FILE, sourceRef, fileName, text, null);
    }

    private void enqueue(
            Long userId,
            Long meetingId,
            String sourceType,
            String sourceRef,
            String fileName,
            String text,
            PreMeetingService.MeetingEntities entities) {
        String extractionText = buildExtractionText(fileName, text);
        if (userId == null || extractionText.isBlank()) {
            log.info("[MeetingMaterialExtractionService] skip, userId={}, sourceType={}, sourceRef={}, reason=empty",
                    userId, sourceType, sourceRef);
            return;
        }
        CompletableFuture.runAsync(() -> extractNow(userId, meetingId, sourceType, sourceRef, extractionText, entities));
    }

    void extractNow(
            Long userId,
            Long meetingId,
            String sourceType,
            String sourceRef,
            String extractionText,
            PreMeetingService.MeetingEntities entities) {
        long start = System.currentTimeMillis();
        log.info("[MeetingMaterialExtractionService] extract start, userId={}, meetingId={}, sourceType={}, sourceRef={}, textLen={}",
                userId, meetingId, sourceType, sourceRef, extractionText.length());
        // 知识包与自动术语按会议隔离：只有已知 meetingId(会议报告上传 / 通知绑定会议)时才抽取，避免跨会议串用。
        if (meetingId != null) {
            runStep("knowledge", sourceType, sourceRef,
                    () -> meetingKnowledgeService.generateAndSaveFromText(meetingId, extractionText));
            runStep("terminology", sourceType, sourceRef,
                    () -> terminologyExtractionService.extractAndSaveFromText(userId, meetingId, extractionText));
        } else {
            log.info("[MeetingMaterialExtractionService] skip knowledge/terminology (no meetingId, unbound pre-meeting), sourceRef={}",
                    sourceRef);
        }
        runStep("hotwords", sourceType, sourceRef,
                () -> hotwordExtractionService.extractAndSaveFromText(extractionText, userId));
        if (entities != null) {
            runStep("meetingEntities", sourceType, sourceRef,
                    () -> asrHotwordService.saveMeetingEntities(
                            userId, entities.participantNames(), entities.venue(), sourceType));
        }
        log.info("[MeetingMaterialExtractionService] extract end, userId={}, sourceType={}, sourceRef={}, costMs={}",
                userId, sourceType, sourceRef, System.currentTimeMillis() - start);
    }

    private void runStep(String step, String sourceType, String sourceRef, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[MeetingMaterialExtractionService] step failed, step={}, sourceType={}, sourceRef={}, reason={}",
                    step, sourceType, sourceRef, e.getMessage(), e);
        }
    }

    private String buildExtractionText(String fileName, String text) {
        String normalizedName = fileName == null ? "" : fileName.trim();
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedName.isBlank()) {
            return normalizedText;
        }
        if (normalizedText.isBlank()) {
            return normalizedName;
        }
        return normalizedName + "\n" + normalizedText;
    }
}
