package com.si.backend.service;

import com.si.backend.integration.AzureAsrIntegration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsrServiceOverlapTest {

    @Test
    void removesAdjacentOverlapBeforeSendingFinalSegmentsDownstream() {
        AzureAsrIntegration integration = mock(AzureAsrIntegration.class);
        AsrHotwordService hotwordService = mock(AsrHotwordService.class);
        AzureAsrIntegration.AsrSession asrSession = mock(AzureAsrIntegration.AsrSession.class);
        AsrService service = new AsrService(integration, hotwordService);
        String sessionId = "overlap-test";

        when(hotwordService.listActive(1L, null)).thenReturn(List.of());
        when(hotwordService.filterSelected(anyList(), isNull())).thenReturn(List.of());
        when(integration.createSession(eq(sessionId), eq("auto"), anyList(), isNull())).thenReturn(asrSession);

        List<String> finalSegments = new ArrayList<>();
        service.startRecognition(
                sessionId,
                1L,
                "auto",
                null,
                null,
                (text, language, speakerId) -> {
                },
                (text, language, speakerId) -> finalSegments.add(text),
                error -> {
                }
        );

        ArgumentCaptor<AzureAsrIntegration.RecognizerCallback> callbackCaptor =
                ArgumentCaptor.forClass(AzureAsrIntegration.RecognizerCallback.class);
        verify(asrSession).setCallback(callbackCaptor.capture());
        AzureAsrIntegration.RecognizerCallback callback = callbackCaptor.getValue();

        callback.onRecognizing("感谢易总的报告，生产厂具有盈利潜力。", "zh-CN", "Guest-1", true);
        callback.onRecognizing("感谢易总的报告，生产厂具有盈利潜力。关键点在于提升开机率。", "zh-CN", "Guest-2", true);
        callback.onRecognizing("感谢易总的报告，生产厂具有盈利潜力。关键点在于提升开机率。", "zh-CN", "Guest-2", true);

        assertEquals(List.of(
                "感谢易总的报告，生产厂具有盈利潜力。",
                "关键点在于提升开机率。"
        ), finalSegments);
    }
}
