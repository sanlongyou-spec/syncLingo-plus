package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.integration.GoogleTranslateIntegration;
import com.si.backend.integration.LlmIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 印尼语数字格式归一化测试：印尼语源文本翻译前应把印尼式数字（「.」千分位、「,」小数点）
 * 改写为通用写法；中/英源文本（「,」是千分位）绝不被改写。
 */
class TranslationNumberNormalizationTest {

    private GoogleTranslateIntegration translator;
    private TerminologyService terminologyService;
    private OpenAiProperties openAiProperties;
    private TranslationService translationService;

    @BeforeEach
    void setUp() {
        translator = mock(GoogleTranslateIntegration.class);
        LlmIntegration llmIntegration = mock(LlmIntegration.class);
        openAiProperties = mock(OpenAiProperties.class);
        terminologyService = mock(TerminologyService.class);
        translationService = new TranslationService(
                translator, llmIntegration, openAiProperties, terminologyService);

        // 术语层透传：保护=原样、还原=原样，便于断言纯数字归一化效果
        when(terminologyService.applyBeforeTranslate(any(), any(), any(), any()))
                .thenAnswer(inv -> TerminologyService.TerminologyProtection.empty(inv.getArgument(1)));
        when(terminologyService.applyAfterTranslate(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(terminologyService.protectTargetTermsForRewrite(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        // MT 透传：直接回显送进来的文本，便于断言归一化后的最终文本
        when(translator.translate(any(), any(), any(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(openAiProperties.isCompressionEnabled()).thenReturn(false);
    }

    /** 捕获实际送入术语层(即归一化之后)的文本 */
    private String normalizedTextFor(String input, String sourceLang) {
        translationService.translate(input, sourceLang, "zh", 1L);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(terminologyService).applyBeforeTranslate(eq(1L), captor.capture(), eq(sourceLang), eq("zh"));
        return captor.getValue();
    }

    @Test
    void indonesianGroupedAndDecimalAreNormalized() {
        String out = normalizedTextFor("tercatat 60.390,80 ton dengan dosis 0,05kg per pokok", "id");
        assertEquals("tercatat 60390.80 ton dengan dosis 0.05kg per pokok", out);
    }

    @Test
    void indonesianThousandsWithoutDecimal() {
        assertEquals("nilai 1000 dan 70503 ton", normalizedTextFor("nilai 1.000 dan 70.503 ton", "id"));
    }

    @Test
    void indonesianYearAndTimeUntouched() {
        // 年份 2026 无千分位点、时间 18:00 用冒号 → 均不应被改写
        assertEquals("tahun 2026 pukul 18:00 dosis 1.5", normalizedTextFor("tahun 2026 pukul 18:00 dosis 1,5", "id"));
    }

    @Test
    void chineseSourceCommaIsThousandsAndKept() {
        // 中文源：「1,000」是千分位，绝不能被改成「1.000」
        assertEquals("产量 1,000 吨，增长 0,05", normalizedTextFor("产量 1,000 吨，增长 0,05", "zh"));
    }

    @Test
    void englishSourceCommaIsThousandsAndKept() {
        assertEquals("about 1,234 tons", normalizedTextFor("about 1,234 tons", "en"));
    }
}
