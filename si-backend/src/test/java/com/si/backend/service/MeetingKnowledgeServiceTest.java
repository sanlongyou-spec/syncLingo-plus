package com.si.backend.service;

import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.MeetingKnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会议知识包服务测试:注入截断、从文件文本蒸馏并保存、空输入不调用。
 */
class MeetingKnowledgeServiceTest {

    private MeetingKnowledgeMapper mapper;
    private LlmIntegration llmIntegration;
    private MeetingKnowledgeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MeetingKnowledgeMapper.class);
        llmIntegration = mock(LlmIntegration.class);
        service = new MeetingKnowledgeService(mapper, llmIntegration);
    }

    @Test
    void getForInjectTruncatesLongContent() {
        when(mapper.findContent(anyLong())).thenReturn("中".repeat(5000));
        String injected = service.getForInject(1L);
        assertEquals(4000, injected.length(), "注入应截断到 4000 字");
    }

    @Test
    void getForInjectNullWhenEmpty() {
        when(mapper.findContent(anyLong())).thenReturn(null);
        assertNull(service.getForInject(1L));
        assertNull(service.getForInject(null));
    }

    @Test
    void generateAndSaveStoresPack() throws IOException {
        when(llmIntegration.extractMeetingKnowledgePack(any())).thenReturn("  聚龙(Julong)=聚龙\n主题:卫星  ");
        service.generateAndSaveFromText(1L, "会议文件内容...");
        verify(mapper).upsert(eq(1L), eq("聚龙(Julong)=聚龙\n主题:卫星"));
    }

    @Test
    void generateAndSaveSkipsBlankInput() throws IOException {
        service.generateAndSaveFromText(1L, "  ");
        service.generateAndSaveFromText(null, "x");
        verify(llmIntegration, never()).extractMeetingKnowledgePack(any());
        verify(mapper, never()).upsert(anyLong(), any());
    }

    @Test
    void generateAndSaveSwallowsLlmFailure() throws IOException {
        when(llmIntegration.extractMeetingKnowledgePack(any())).thenThrow(new IOException("llm down"));
        service.generateAndSaveFromText(1L, "content");   // 不抛异常
        verify(mapper, never()).upsert(anyLong(), any());
        assertTrue(true);
    }
}
