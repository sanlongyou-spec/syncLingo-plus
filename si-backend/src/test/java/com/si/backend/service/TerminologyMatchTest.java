package com.si.backend.service;

import com.si.backend.entity.Terminology;
import com.si.backend.mapper.TerminologyMapper;
import com.si.backend.service.TerminologyService.TerminologyProtection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 术语匹配回归测试：防止"无单词边界子串替换 + 单字符脏词条"导致 coffee→coTojokTojokee 这类损坏。
 */
class TerminologyMatchTest {

    private static final long UID = 1L;

    private Terminology term(String zh, String id, String en) {
        Terminology t = new Terminology();
        t.setEnabled(true);
        t.setUserId(UID);
        t.setTermZh(zh);
        t.setTermId(id);
        t.setTermEn(en);
        return t;
    }

    private TerminologyService serviceWith(List<Terminology> terms) {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        when(mapper.findEnabled(anyLong())).thenReturn(terms);
        return new TerminologyService(mapper);
    }

    @Test
    void singleCharLatinTermDoesNotCorruptWord() {
        // 脏词条：英文单字母 "f" → 印尼 "Tojok" / 中文 "铁钎"
        TerminologyService svc = serviceWith(List.of(term("铁钎", "Tojok", "f")));
        String src = "Because I don't drink coffee.";

        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "en", "id");
        // coffee 内的 f 不应被替换为占位符
        assertEquals(src, p.getProtectedText(), "单字母术语不应命中单词内部");
        assertTrue(p.getTargetTermByPlaceholder().isEmpty(), "不应产生占位符");

        // 译文也不应被追加 (Tojok)
        String translated = "Karena saya tidak minum kopi.";
        String after = svc.applyAfterTranslate(src, translated, "en", "id", p, UID);
        assertEquals(translated, after, "不应追加脏术语译文");
        assertFalse(after.contains("Tojok"));
    }

    @Test
    void multiCharLatinTermMatchesOnWordBoundary() {
        TerminologyService svc = serviceWith(List.of(term("卡塔西亚", "Kartesia", "Cartesia")));
        String src = "We use Cartesia today";

        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "en", "id");
        assertFalse(p.getProtectedText().contains("Cartesia"), "整词术语应被占位符保护");
        assertEquals(1, p.getTargetTermByPlaceholder().size());

        // 模拟翻译保留占位符后，应还原成目标术语
        String after = svc.applyAfterTranslate(src, p.getProtectedText(), "en", "id", p, UID);
        assertTrue(after.contains("Kartesia"), "占位符应还原为目标术语");
    }

    @Test
    void latinTermDoesNotMatchInsideAnotherWord() {
        // "AI" 不应命中 "raining" 内部
        TerminologyService svc = serviceWith(List.of(term("人工智能", "kecerdasan", "AI")));
        String src = "It is raining now";

        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "en", "id");
        assertEquals(src, p.getProtectedText(), "AI 不应命中 raining 内部");
        assertTrue(p.getTargetTermByPlaceholder().isEmpty());
    }
}
