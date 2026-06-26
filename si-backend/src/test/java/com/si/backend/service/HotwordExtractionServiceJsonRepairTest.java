package com.si.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.entity.AsrHotword;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.AsrHotwordMapper;
import com.si.backend.mapper.InterpretationRecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotwordExtractionServiceJsonRepairTest {

    @Test
    void extractFromTextSavesCompleteObjectsWhenJsonIsTruncated() throws Exception {
        LlmIntegration llmIntegration = mock(LlmIntegration.class);
        AsrHotwordService hotwordService = mock(AsrHotwordService.class);
        AsrHotwordMapper hotwordMapper = mock(AsrHotwordMapper.class);
        HotwordExtractionService service = new HotwordExtractionService(
                llmIntegration,
                mock(InterpretationRecordMapper.class),
                hotwordService,
                hotwordMapper,
                new ObjectMapper());
        when(llmIntegration.extractHotwordsJson(anyString())).thenReturn("""
                [{"phrase":"Pupuk Boron","category":"term","language":"id-ID"},{"phrase":"Broken"
                """);
        when(hotwordMapper.countByUserIdPhraseAndLanguage(eq(4L), anyString(), eq(""))).thenReturn(0);
        when(hotwordService.create(eq(4L), any(AsrHotword.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        List<AsrHotword> saved = service.extractAndSaveFromText("meeting material", 4L);

        assertEquals(1, saved.size());
        assertEquals("Pupuk Boron", saved.get(0).getPhrase());
        assertEquals("", saved.get(0).getLanguage());
        ArgumentCaptor<AsrHotword> hotwordCaptor = ArgumentCaptor.forClass(AsrHotword.class);
        verify(hotwordService).create(eq(4L), hotwordCaptor.capture());
        assertEquals("AUTO_EXTRACTED", hotwordCaptor.getValue().getSourceType());
    }
}
