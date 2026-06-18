package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.config.OpenAiProperties;
import com.si.backend.entity.Terminology;
import com.si.backend.integration.GoogleTranslateIntegration;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.TerminologyMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationTerminologyProtectionTest {

    private static final long UID = 1L;

    @Test
    void compressionReceivesPlaceholderAndFinalTextRestoresTargetTerm() throws Exception {
        GoogleTranslateIntegration translator = mock(GoogleTranslateIntegration.class);
        LlmIntegration llmIntegration = mock(LlmIntegration.class);
        TerminologyMapper terminologyMapper = mock(TerminologyMapper.class);
        TerminologyService terminologyService = new TerminologyService(terminologyMapper);
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setCompressionEnabled(true);
        openAiProperties.setCompressionMinTextLength(1);
        openAiProperties.setCompressionZhToIdTargetRatio(0.6);

        when(terminologyMapper.findEnabled(UID)).thenReturn(List.of(term("卡塔西亚", "Kartesia", "Cartesia")));
        when(translator.translate(anyString(), eq(Constants.LANG_ZH_CN), eq(Constants.LANG_ID_SHORT), eq(UID)))
                .thenReturn("Hari ini kita membahas __SI_TERM_0__ dan rencana pembayaran jangka panjang.");
        when(llmIntegration.compressIndonesian(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class));

        TranslationService service = new TranslationService(
                translator,
                llmIntegration,
                openAiProperties,
                terminologyService
        );

        String result = service.translate(
                "今天我们讨论卡塔西亚以及长期付款安排",
                Constants.LANG_ZH_CN,
                Constants.LANG_ID_SHORT,
                UID,
                true
        );

        ArgumentCaptor<String> compressionInput = ArgumentCaptor.forClass(String.class);
        verify(llmIntegration).compressIndonesian(compressionInput.capture());
        assertTrue(compressionInput.getValue().contains("__SI_TERM_0__"), compressionInput.getValue());
        assertFalse(compressionInput.getValue().contains("Kartesia"), compressionInput.getValue());
        assertTrue(result.contains("Kartesia"), result);
        assertFalse(result.contains("SI_TERM"), result);
    }

    private Terminology term(String zh, String id, String en) {
        Terminology terminology = new Terminology();
        terminology.setUserId(UID);
        terminology.setTermZh(zh);
        terminology.setTermId(id);
        terminology.setTermEn(en);
        terminology.setEnabled(true);
        return terminology;
    }
}
