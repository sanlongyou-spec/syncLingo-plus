package com.si.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MeetingMaterialExtractionServiceTest {

    private final HotwordExtractionService hotwordExtractionService = mock(HotwordExtractionService.class);
    private final MeetingKnowledgeService meetingKnowledgeService = mock(MeetingKnowledgeService.class);
    private final TerminologyExtractionService terminologyExtractionService = mock(TerminologyExtractionService.class);
    private final AsrHotwordService asrHotwordService = mock(AsrHotwordService.class);
    private final MeetingMaterialExtractionService service = new MeetingMaterialExtractionService(
            hotwordExtractionService,
            meetingKnowledgeService,
            terminologyExtractionService,
            asrHotwordService);

    @Test
    void extractNowRunsEveryExtractorWithMeetingScope() {
        String extractionText = "report.pdf\nRudi report content";
        PreMeetingService.MeetingEntities entities =
                new PreMeetingService.MeetingEntities(List.of("Rudi"), "Main Room");

        service.extractNow(4L, 53L, "MEETING_FILE", "meetingId=53,fileId=77", extractionText, entities);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(hotwordExtractionService).extractAndSaveFromText(textCaptor.capture(), eq(4L));
        assertTrue(textCaptor.getValue().contains("report.pdf"));
        assertTrue(textCaptor.getValue().contains("Rudi report content"));
        // 知识包按会议(meetingId)归属；术语按 (userId, meetingId) 归属
        verify(meetingKnowledgeService).generateAndSaveFromText(53L, extractionText);
        verify(terminologyExtractionService).extractAndSaveFromText(4L, 53L, extractionText);
        verify(asrHotwordService).saveMeetingEntities(
                4L, List.of("Rudi"), "Main Room", "MEETING_FILE");
    }

    @Test
    void extractNowContinuesWhenOneStepFails() {
        doThrow(new RuntimeException("llm down"))
                .when(hotwordExtractionService).extractAndSaveFromText(any(), eq(4L));

        service.extractNow(4L, 53L, "MEETING_FILE", "meetingId=53,fileId=77", "report text", null);

        verify(meetingKnowledgeService).generateAndSaveFromText(53L, "report text");
        verify(terminologyExtractionService).extractAndSaveFromText(4L, 53L, "report text");
    }

    @Test
    void extractNowSkipsKnowledgeAndTerminologyWhenNoMeetingId() {
        // 会前独立上传(未绑定会议)无 meetingId：只跑热词，不抽知识包/术语，避免无会议归属串用。
        service.extractNow(4L, null, "MEETING_AGENDA", "file-1", "agenda text content", null);

        verify(hotwordExtractionService).extractAndSaveFromText(any(), eq(4L));
        verify(meetingKnowledgeService, never()).generateAndSaveFromText(any(), any());
        verify(terminologyExtractionService, never()).extractAndSaveFromText(any(), any(), any());
    }
}
