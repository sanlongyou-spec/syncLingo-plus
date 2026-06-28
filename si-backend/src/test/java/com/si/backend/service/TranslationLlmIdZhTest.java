package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.integration.GoogleTranslateIntegration;
import com.si.backend.integration.LlmIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 印尼语→中文 LLM 纠错翻译路由测试:
 * 开启时 id→zh 走 LLM;LLM 失败/超时回退 Google;关闭时仍走 Google。
 */
class TranslationLlmIdZhTest {

    private GoogleTranslateIntegration translator;
    private LlmIntegration llmIntegration;
    private OpenAiProperties openAiProperties;
    private TerminologyService terminologyService;
    private TranslationService translationService;

    @BeforeEach
    void setUp() {
        translator = mock(GoogleTranslateIntegration.class);
        llmIntegration = mock(LlmIntegration.class);
        openAiProperties = mock(OpenAiProperties.class);
        terminologyService = mock(TerminologyService.class);
        AsrCorrectionService asrCorrectionService = mock(AsrCorrectionService.class);
        MeetingKnowledgeService meetingKnowledgeService = mock(MeetingKnowledgeService.class);
        translationService = new TranslationService(
                translator, llmIntegration, openAiProperties, terminologyService,
                asrCorrectionService, meetingKnowledgeService);

        // 术语层透传(动态术语表为空 + Google 回退路径都需要)
        when(terminologyService.applyBeforeTranslate(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> TerminologyService.TerminologyProtection.empty(inv.getArgument(2)));
        when(terminologyService.applyAfterTranslate(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(terminologyService.protectTargetTermsForRewrite(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(openAiProperties.isCompressionEnabled()).thenReturn(false);
    }

    @Test
    void idToZhUsesLlmWhenEnabled() throws IOException {
        when(openAiProperties.isIdZhLlmTranslateEnabled()).thenReturn(true);
        when(llmIntegration.correctAndTranslateIndonesianToChinese(any(), any(), any(), any()))
                .thenReturn("纠错后的中文");

        String result = translationService.translate(
                "ya itu defisit dominannya rata rata k", "id-ID", "zh", 1L, false, "上文");

        assertEquals("纠错后的中文", result);
        verify(translator, never()).translate(any(), any(), any(), anyLong());
    }

    @Test
    void idToZhFallsBackToGoogleWhenLlmFails() throws IOException {
        when(openAiProperties.isIdZhLlmTranslateEnabled()).thenReturn(true);
        when(llmIntegration.correctAndTranslateIndonesianToChinese(any(), any(), any(), any()))
                .thenThrow(new IOException("llm timeout"));
        when(translator.translate(any(), any(), any(), anyLong())).thenReturn("谷歌中文");

        String result = translationService.translate(
                "ya itu defisit dominannya rata rata k", "id-ID", "zh", 1L, false, null);

        assertEquals("谷歌中文", result);
        verify(translator).translate(any(), any(), any(), anyLong());
    }

    @Test
    void idToZhUsesGoogleWhenLlmDisabled() throws IOException {
        when(openAiProperties.isIdZhLlmTranslateEnabled()).thenReturn(false);
        when(translator.translate(any(), any(), any(), anyLong())).thenReturn("谷歌中文");

        String result = translationService.translate("apa kabar", "id-ID", "zh", 1L, false, "上文");

        assertEquals("谷歌中文", result);
        verify(llmIntegration, never()).correctAndTranslateIndonesianToChinese(any(), any(), any(), any());
    }

    @Test
    void chineseToIndonesianNeverUsesIdZhLlm() throws IOException {
        when(openAiProperties.isIdZhLlmTranslateEnabled()).thenReturn(true);
        when(translator.translate(any(), any(), any(), anyLong())).thenReturn("apa kabar");

        String result = translationService.translate("你好", "zh", "id", 1L, false, null);

        assertEquals("apa kabar", result);
        verify(llmIntegration, never()).correctAndTranslateIndonesianToChinese(any(), any(), any(), any());
    }
}
