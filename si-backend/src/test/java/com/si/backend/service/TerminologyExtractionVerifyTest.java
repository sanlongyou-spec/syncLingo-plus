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

/**
 * 校验步骤:LLM 把语义错的对(等离子体=Plasma)剔除,只入库它确认正确的(硼肥)。
 */
class TerminologyExtractionVerifyTest {

    @Test
    void verifyDropsSemanticallyWrongPair() throws Exception {
        LlmIntegration llm = mock(LlmIntegration.class);
        TerminologyService terminologyService = mock(TerminologyService.class);
        TerminologyExtractionService service =
                new TerminologyExtractionService(llm, terminologyService, new ObjectMapper());

        // 抽取吐出一对正确 + 一对语义错
        when(llm.extractTerminologyPairsJson(anyString())).thenReturn("""
                [{"zh":"硼肥","id":"pupuk boron","en":"","category":"专业术语"},
                 {"zh":"等离子体","id":"Plasma","en":"","category":"专业术语"}]
                """);
        // 校验只确认正确那条
        when(llm.verifyTerminologyPairsJson(anyString(), anyString())).thenReturn("""
                [{"zh":"硼肥","id":"pupuk boron","en":"","category":"专业术语"}]
                """);
        when(terminologyService.addExtractedTerms(eq(7L), anyList())).thenReturn(1);

        service.extractAndSaveFromText(7L, "会议材料...Plasma...pupuk boron...");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Terminology>> captor = ArgumentCaptor.forClass(List.class);
        verify(terminologyService).addExtractedTerms(eq(7L), captor.capture());
        List<Terminology> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals("硼肥", saved.get(0).getTermZh());
        assertEquals("pupuk boron", saved.get(0).getTermId());
    }
}
