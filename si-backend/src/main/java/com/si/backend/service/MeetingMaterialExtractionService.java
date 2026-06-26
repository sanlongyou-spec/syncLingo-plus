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
        enqueue(userId, SOURCE_MEETING_AGENDA, fileId, fileName, text, entities);
    }

    public void enqueueFromMeetingFile(
            Long userId,
            Long meetingId,
            Long fileId,
            String fileName,
            String text) {
        String sourceRef = "meetingId=" + meetingId + ",fileId=" + fileId;
        enqueue(userId, SOURCE_MEETING_FILE, sourceRef, fileName, text, null);
    }

    private void enqueue(
            Long userId,
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
        CompletableFuture.runAsync(() -> extractNow(userId, sourceType, sourceRef, extractionText, entities));
    }

    void extractNow(
            Long userId,
            String sourceType,
            String sourceRef,
            String extractionText,
            PreMeetingService.MeetingEntities entities) {
        long start = System.currentTimeMillis();
        log.info("[MeetingMaterialExtractionService] extract start, userId={}, sourceType={}, sourceRef={}, textLen={}",
                userId, sourceType, sourceRef, extractionText.length());
        runStep("hotwords", sourceType, sourceRef,
                () -> hotwordExtractionService.extractAndSaveFromText(extractionText, userId));
        runStep("knowledge", sourceType, sourceRef,
                () -> meetingKnowledgeService.generateAndSaveFromText(userId, extractionText));
        runStep("terminology", sourceType, sourceRef,
                () -> terminologyExtractionService.extractAndSaveFromText(userId, extractionText));
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
