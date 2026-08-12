package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.InterpretationResult;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.vo.InterpretationResultItemVo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterpretationResultServiceTest {

    private final InterpretationResultMapper resultMapper = mock(InterpretationResultMapper.class);
    private final InterpretationEmbeddingMapper embeddingMapper = mock(InterpretationEmbeddingMapper.class);
    private final InterpretationSessionMapper sessionMapper = mock(InterpretationSessionMapper.class);
    private final LlmIntegration llmIntegration = mock(LlmIntegration.class);
    private final OpenAiProperties openAiProperties = mock(OpenAiProperties.class);
    private final InterpretationResultService service = new InterpretationResultService(
            resultMapper,
            embeddingMapper,
            sessionMapper,
            llmIntegration,
            openAiProperties
    );

    @Test
    void listBySessionIdAfterIdUsesIncrementalCursorAndCapsLimit() {
        InterpretationResult row = row(11L, "session-a", "source", "translated", "zh-CN", "en-US");
        when(resultMapper.findBySessionIdAfterId("session-a", 10L, 500)).thenReturn(List.of(row));

        List<InterpretationResultItemVo> results = service.listBySessionIdAfterId("session-a", 10L, 2_000);

        verify(resultMapper).findBySessionIdAfterId("session-a", 10L, 500);
        assertEquals(1, results.size());
        assertEquals(11L, results.get(0).getId());
        assertEquals("source", results.get(0).getSourceText());
        assertEquals("translated", results.get(0).getTranslatedText());
        assertEquals("zh-CN", results.get(0).getSourceLang());
        assertEquals("en-US", results.get(0).getTargetLang());
    }

    @Test
    void listBySessionIdAfterIdDefaultsInvalidCursorAndLimit() {
        when(resultMapper.findBySessionIdAfterId("session-b", 0L, 200)).thenReturn(List.of());

        List<InterpretationResultItemVo> results = service.listBySessionIdAfterId("session-b", -9L, 0);

        verify(resultMapper).findBySessionIdAfterId("session-b", 0L, 200);
        assertEquals(0, results.size());
    }

    @Test
    void listBySessionIdStillUsesFullSessionQueryForHistoryCompatibility() {
        InterpretationResult row = row(3L, "session-history", "all", "full", "id-ID", "zh-CN");
        when(resultMapper.findBySessionId("session-history")).thenReturn(List.of(row));

        List<InterpretationResultItemVo> results = service.listBySessionId("session-history");

        verify(resultMapper).findBySessionId("session-history");
        assertEquals(1, results.size());
        assertEquals(3L, results.get(0).getId());
        assertEquals("all", results.get(0).getSourceText());
    }

    private static InterpretationResult row(
            Long id,
            String sessionId,
            String sourceText,
            String translatedText,
            String sourceLang,
            String targetLang
    ) {
        InterpretationResult result = new InterpretationResult();
        result.setId(id);
        result.setSessionId(sessionId);
        result.setSourceText(sourceText);
        result.setTranslatedText(translatedText);
        result.setSourceLang(sourceLang);
        result.setTargetLang(targetLang);
        result.setCreateTime(LocalDateTime.of(2026, 8, 12, 10, 0));
        return result;
    }
}
