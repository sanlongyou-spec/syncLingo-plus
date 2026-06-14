package com.si.backend.service;

import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.integration.MeetingBotIntegration;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import com.si.backend.service.UserPreferenceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpeakerSummaryServiceTest {

    @Test
    void detectsCommonUtf8Mojibake() {
        String mojibake = "ä¸­æ–‡æ‘˜è¦å‡ºçŽ°äº†ä¹±ç ";

        assertTrue(SpeakerSummaryService.isUnusableSummary(mojibake));
    }

    @Test
    void acceptsNormalChineseAndIndonesianSummary() {
        assertFalse(SpeakerSummaryService.isUnusableSummary(
                "杰铭介绍了项目建设范围、预算安排和预计完成时间。"));
        assertFalse(SpeakerSummaryService.isUnusableSummary(
                "Proyek ditargetkan selesai pada Oktober 2025 dengan fokus pada pemisahan dan penyimpanan."));
    }

    @Test
    void detectsReplacementCharactersAndRefusal() {
        assertTrue(SpeakerSummaryService.isUnusableSummary("摘要内容��无法读取"));
        assertTrue(SpeakerSummaryService.isUnusableSummary("I'm sorry, but I cannot assist with that."));
    }

    @Test
    void persistsManualSpeakerNameAndSummaryCorrection() {
        SpeakerSummaryRecordMapper mapper = mock(SpeakerSummaryRecordMapper.class);
        ContentEmbeddingService embeddingService = mock(ContentEmbeddingService.class);
        SpeakerSummaryService service = new SpeakerSummaryService(
                mock(LlmIntegration.class),
                mapper,
                embeddingService,
                mock(InterpretationSessionMapper.class),
                mock(MeetingBotIntegration.class),
                mock(UserPreferenceService.class)
        );
        SpeakerSummaryRecord record = SpeakerSummaryRecord.builder()
                .id(7L)
                .sessionId("session-1")
                .speakerName("Guest-4")
                .title("项目进展")
                .summary("旧摘要")
                .build();
        when(mapper.findById(7L)).thenReturn(record);

        SpeakerSummaryRecord updated = service.update(7L, "杰铭 ", " 修正后的摘要 ");

        assertEquals("杰铭", updated.getSpeakerName());
        assertEquals("修正后的摘要", updated.getSummary());
        verify(mapper).updateEditableFields(record);
        verify(embeddingService).asyncEmbedSpeakerSummary(
                7L, "session-1", "杰铭", "项目进展", "修正后的摘要");
    }

    @Test
    void refreshesPersistedMojibakeFromOriginalTranscript() throws Exception {
        LlmIntegration llmIntegration = mock(LlmIntegration.class);
        SpeakerSummaryRecordMapper mapper = mock(SpeakerSummaryRecordMapper.class);
        ContentEmbeddingService embeddingService = mock(ContentEmbeddingService.class);
        SpeakerSummaryService service = new SpeakerSummaryService(
                llmIntegration, mapper, embeddingService,
                mock(InterpretationSessionMapper.class),
                mock(MeetingBotIntegration.class),
                mock(UserPreferenceService.class)
        );
        SpeakerSummaryRecord record = SpeakerSummaryRecord.builder()
                .id(8L)
                .sessionId("session-2")
                .speakerName("Guest-4")
                .textSnippet("原始发言文本")
                .summary("ä¸­æ–‡æ‘˜è¦å‡ºçŽ°äº†ä¹±ç ")
                .build();
        when(mapper.findBySessionId("session-2")).thenReturn(List.of(record));
        when(llmIntegration.summarizeSpeakerSegment("Guest-4", "原始发言文本", null))
                .thenReturn("重新生成的正常中文摘要");

        List<SpeakerSummaryRecord> records = service.getBySession("session-2");

        assertEquals("重新生成的正常中文摘要", records.get(0).getSummary());
        verify(mapper).updateSummary(record);
        verify(embeddingService).asyncEmbedSpeakerSummary(
                8L, "session-2", "Guest-4", null, "重新生成的正常中文摘要");
    }
}
