package com.si.backend.service;

import com.si.backend.entity.AsrCorrection;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.AsrCorrectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 错词库服务测试:匹配文本、按置信度定状态、会后挖词入库(含低置信度过滤与围栏剥离)。
 */
class AsrCorrectionServiceTest {

    private AsrCorrectionMapper mapper;
    private LlmIntegration llmIntegration;
    private AsrCorrectionService service;

    private AsrCorrection corr(String variant, String canonical) {
        AsrCorrection c = new AsrCorrection();
        c.setVariant(variant);
        c.setCanonical(canonical);
        c.setStatus("ACTIVE");
        return c;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AsrCorrectionMapper.class);
        llmIntegration = mock(LlmIntegration.class);
        service = new AsrCorrectionService(mapper, llmIntegration);
    }

    @Test
    void matchInTextHitsLatinWordBoundaryAndCjkSubstring() {
        when(mapper.findActive(anyLong())).thenReturn(List.of(
                corr("starling", "星链"), corr("buron", "硼"), corr("董事", "董事长")));

        Map<String, String> hints = service.matchInText(1L, "ada starling dan 董事 di sini");
        assertEquals("星链", hints.get("starling"));
        assertEquals("董事长", hints.get("董事"));
        assertFalse(hints.containsKey("buron"), "未出现的错词不应命中");
    }

    @Test
    void matchInTextRespectsWordBoundaryForLatin() {
        when(mapper.findActive(anyLong())).thenReturn(List.of(corr("starling", "星链")));
        // "starlingx" 不应命中 "starling"
        assertTrue(service.matchInText(1L, "starlingx ada").isEmpty());
    }

    @Test
    void recordSetsActiveWhenConfidenceHigh() {
        service.record(1L, "PacarMen", "董事长", null, 0.95, "DOC_MINING");
        verify(mapper).upsert(eq(1L), eq("pacarmen"), eq("董事长"), any(), eq("GLOBAL"),
                eq(0.95), eq(1), eq("ACTIVE"), eq("DOC_MINING"), anyInt(), anyDouble());
    }

    @Test
    void recordSetsObservingWhenConfidenceLow() {
        service.record(1L, "starling", "星链", null, 0.6, "DOC_MINING");
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsert(anyLong(), any(), any(), any(), any(), anyDouble(), anyInt(),
                status.capture(), any(), anyInt(), anyDouble());
        assertEquals("OBSERVING", status.getValue());
    }

    @Test
    void mineAndStoreFiltersLowConfidenceAndStripsFence() throws IOException {
        when(llmIntegration.mineAsrCorrectionsJson(any(), any())).thenReturn(
                "```json\n[{\"variant\":\"starling\",\"canonical\":\"星链\",\"confidence\":0.95},"
                + "{\"variant\":\"zz\",\"canonical\":\"yy\",\"confidence\":0.2}]\n```");

        int stored = service.mineAndStore(1L, "transcript starling", "doc Starlink");

        assertEquals(1, stored, "低置信度(0.2<0.55)应被丢弃");
        verify(mapper, times(1)).upsert(eq(1L), eq("starling"), eq("星链"), any(), any(),
                eq(0.95), anyInt(), any(), eq("DOC_MINING"), anyInt(), anyDouble());
        verify(mapper, never()).upsert(anyLong(), eq("zz"), any(), any(), any(), anyDouble(), anyInt(), any(), any(), anyInt(), anyDouble());
    }

    @Test
    void mineAndStoreBlankInputsReturnZero() {
        assertEquals(0, service.mineAndStore(1L, "", "doc"));
        assertEquals(0, service.mineAndStore(1L, "t", ""));
    }
}
