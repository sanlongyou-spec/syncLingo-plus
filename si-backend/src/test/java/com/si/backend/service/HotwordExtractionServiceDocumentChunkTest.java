package com.si.backend.service;

import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.AsrHotwordMapper;
import com.si.backend.mapper.InterpretationRecordMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotwordExtractionServiceDocumentChunkTest {

    @Test
    void extractFromTextCoversWholeLargeDocument() throws Exception {
        LlmIntegration llmIntegration = mock(LlmIntegration.class);
        HotwordExtractionService service = new HotwordExtractionService(
                llmIntegration,
                mock(InterpretationRecordMapper.class),
                mock(AsrHotwordService.class),
                mock(AsrHotwordMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(llmIntegration.extractHotwordsJson(anyString())).thenReturn("[]");

        service.extractAndSaveFromText("a".repeat(61_995), 4L);

        verify(llmIntegration, times(16)).extractHotwordsJson(anyString());
        assertEquals(16, Math.ceil(61_995 / 4000.0));
    }
}
