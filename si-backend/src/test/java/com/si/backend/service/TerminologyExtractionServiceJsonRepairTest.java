package com.si.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.entity.Terminology;
import com.si.backend.integration.LlmIntegration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminologyExtractionServiceJsonRepairTest {

    @Test
    void extractFromTextSavesCompleteTerminologyWhenJsonIsTruncated() throws Exception {
        LlmIntegration llmIntegration = mock(LlmIntegration.class);
        TerminologyService terminologyService = mock(TerminologyService.class);
        TerminologyExtractionService service = new TerminologyExtractionService(
                llmIntegration,
                terminologyService,
                new ObjectMapper());
        when(llmIntegration.extractTerminologyPairsJson(anyString())).thenReturn("""
                [{"zh":"硼肥","id":"pupuk boron","en":"boron fertilizer","category":"term"},{"zh":"坏"
                """);
        when(terminologyService.addExtractedTerms(eq(4L), anyList())).thenReturn(1);

        int created = service.extractAndSaveFromText(4L, "meeting material");

        assertEquals(1, created);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Terminology>> termsCaptor = ArgumentCaptor.forClass(List.class);
        verify(terminologyService).addExtractedTerms(eq(4L), termsCaptor.capture());
        assertEquals(1, termsCaptor.getValue().size());
        assertEquals("硼肥", termsCaptor.getValue().get(0).getTermZh());
        assertEquals("pupuk boron", termsCaptor.getValue().get(0).getTermId());
        assertEquals("boron fertilizer", termsCaptor.getValue().get(0).getTermEn());
    }
}
