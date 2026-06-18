package com.si.backend.service;

import com.si.backend.entity.Terminology;
import com.si.backend.mapper.TerminologyMapper;
import com.si.backend.service.TerminologyService.TerminologyProtection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void mangledPlaceholderIsRestoredTolerantly() {
        // 翻译/LLM 把 __SI_TERM_0__ 改成大小写/空格/下划线变体时，应仍能还原成目标术语
        TerminologyService svc = serviceWith(List.of(term("卡塔西亚", "Kartesia", "Cartesia")));
        String src = "We use Cartesia today";
        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "en", "id");
        assertEquals(1, p.getTargetTermByPlaceholder().size());

        String mangled = "Kami memakai si term 0 hari ini";   // 占位符被改写
        String after = svc.applyAfterTranslate(src, mangled, "en", "id", p, UID);
        assertTrue(after.contains("Kartesia"), "被改写的占位符应被容错还原: " + after);
        assertFalse(after.toLowerCase().contains("si term"), "不应残留占位符: " + after);
        assertFalse(after.toLowerCase().contains("si_term"), "不应残留占位符: " + after);
    }

    @Test
    void unmappedResidualPlaceholderIsScrubbed() {
        // 还原失败/多余的占位符必须被兜底清除，绝不出现在最终译文
        TerminologyService svc = serviceWith(List.of());
        String src = "hello";
        TerminologyProtection p = TerminologyProtection.empty(src);
        String translated = "halo __SI_TERM_3__ dunia";
        String after = svc.applyAfterTranslate(src, translated, "en", "id", p, UID);
        assertFalse(after.contains("SI_TERM"), "残留占位符应被清除: " + after);
        assertTrue(after.contains("halo") && after.contains("dunia"), after);
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

    @Test
    void longestOverlappingSourceTermWins() {
        TerminologyService svc = serviceWith(List.of(
                term("合同", "kontrak", "contract"),
                term("合同履约保证金", "jaminan pelaksanaan kontrak", "contract performance bond")
        ));
        String src = "合同履约保证金到账";

        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "zh", "id");

        assertEquals("__SI_TERM_0__到账", p.getProtectedText());
        assertEquals(1, p.getTargetTermByPlaceholder().size());
        assertEquals("jaminan pelaksanaan kontrak", p.getTargetTermByPlaceholder().get("__SI_TERM_0__"));
    }

    @Test
    void ambiguousSameSourceTermIsSkipped() {
        TerminologyService svc = serviceWith(List.of(
                term("保证金", "deposit", "deposit"),
                term("保证金", "jaminan", "guarantee")
        ));
        String src = "保证金今天到账";

        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "zh", "id");

        assertEquals(src, p.getProtectedText());
        assertTrue(p.getTargetTermByPlaceholder().isEmpty());
    }

    @Test
    void largeTermListOnlyProtectsActualSentenceHits() {
        List<Terminology> terms = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            terms.add(term("无关术语" + i, "istilah tidak terkait " + i, "irrelevant term " + i));
        }
        terms.add(term("专用术语", "istilah khusus", "special term"));
        TerminologyService svc = serviceWith(terms);

        TerminologyProtection p = svc.applyBeforeTranslate(UID, "今天只说专用术语", "zh", "id");

        assertEquals("今天只说__SI_TERM_0__", p.getProtectedText());
        assertEquals(1, p.getTargetTermByPlaceholder().size());
        assertEquals("istilah khusus", p.getTargetTermByPlaceholder().get("__SI_TERM_0__"));
    }

    @Test
    void targetTermIsProtectedBeforeCompressionRewriteAndRestoredAfterward() {
        TerminologyService svc = serviceWith(List.of(term("卡塔西亚", "Kartesia", "Cartesia")));
        String src = "We use Cartesia today";
        TerminologyProtection p = svc.applyBeforeTranslate(UID, src, "en", "id");
        String restored = svc.applyAfterTranslate(src, "Kami memakai __SI_TERM_0__ hari ini", "en", "id", p, UID);

        String rewriteInput = svc.protectTargetTermsForRewrite(restored, p);
        String finalText = svc.applyAfterTranslate(src, rewriteInput, "en", "id", p, UID);

        assertTrue(rewriteInput.contains("__SI_TERM_0__"), rewriteInput);
        assertFalse(rewriteInput.contains("Kartesia"), rewriteInput);
        assertTrue(finalText.contains("Kartesia"), finalText);
        assertFalse(finalText.contains("SI_TERM"), finalText);
    }

    @Test
    void terminologyIndexIsInvalidatedAfterMutation() {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        when(mapper.findEnabled(UID)).thenReturn(
                List.of(term("旧词", "istilah lama", "old term")),
                List.of(term("新词", "istilah baru", "new term"))
        );
        when(mapper.updateEnabled(7L, UID, false)).thenReturn(1);
        TerminologyService svc = new TerminologyService(mapper);

        TerminologyProtection first = svc.applyBeforeTranslate(UID, "旧词", "zh", "id");
        TerminologyProtection cached = svc.applyBeforeTranslate(UID, "新词", "zh", "id");
        svc.updateEnabled(7L, UID, false);
        TerminologyProtection afterInvalidation = svc.applyBeforeTranslate(UID, "新词", "zh", "id");

        assertEquals(1, first.getTargetTermByPlaceholder().size());
        assertTrue(cached.getTargetTermByPlaceholder().isEmpty());
        assertEquals("istilah baru", afterInvalidation.getTargetTermByPlaceholder().get("__SI_TERM_0__"));
        verify(mapper, times(2)).findEnabled(UID);
    }
}
