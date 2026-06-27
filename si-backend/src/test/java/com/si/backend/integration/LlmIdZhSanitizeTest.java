package com.si.backend.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * id→zh LLM 译文清洗与元话语拦截:确保 LLM 的"解释/拒绝/说明"不会进入译文/TTS。
 */
class LlmIdZhSanitizeTest {

    @Test
    void stripsUncertaintyParenthetical() {
        assertEquals("星链连接", LlmIntegration.sanitizeIdZhTranslation("星链连接（疑似识别错误）"));
        assertEquals("钾含量低", LlmIntegration.sanitizeIdZhTranslation("钾含量低(疑似识别错误)"));
    }

    @Test
    void cleanTranslationIsNotMeta() {
        assertFalse(LlmIntegration.looksLikeMetaCommentary("本次会议聚焦公司未来的三大战略体系。"));
        assertFalse(LlmIntegration.looksLikeMetaCommentary("卫星通信"));
    }

    @Test
    void refusalAndNotesAreMeta() {
        assertTrue(LlmIntegration.looksLikeMetaCommentary(
                "无法判断确切含义。这个片段可能是人名、地名或不完整的句子。"));
        assertTrue(LlmIntegration.looksLikeMetaCommentary(
                "我注意到当前句的前文背景与农业会议背景不符，但我仍按您的要求翻译"));
        assertTrue(LlmIntegration.looksLikeMetaCommentary(
                "这是一个完整的未来工业平台系统。**说明**：当前句逻辑不完整。"));
        assertTrue(LlmIntegration.looksLikeMetaCommentary(
                "这句话在输入中存在明显的ASR识别错误，无法确定原意。根据上文，可能的原句应为类似Dosis yang..."));
        assertTrue(LlmIntegration.looksLikeMetaCommentary(
                "咨询词汇上下文后，该词无法确定含义。"));
        assertTrue(LlmIntegration.looksLikeMetaCommentary(""));
        assertTrue(LlmIntegration.looksLikeMetaCommentary("   "));
    }
}
