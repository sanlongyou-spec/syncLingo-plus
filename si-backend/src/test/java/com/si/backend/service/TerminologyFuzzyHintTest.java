package com.si.backend.service;

import com.si.backend.entity.Terminology;
import com.si.backend.mapper.TerminologyMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模糊术语提示测试:ASR 把术语听错(kupu≈pupuk、buron≈boron)时,仍能把正确术语作为参考提示给 LLM。
 */
class TerminologyFuzzyHintTest {

    private static final long UID = 1L;

    private Terminology term(String zh, String id) {
        Terminology t = new Terminology();
        t.setEnabled(true);
        t.setUserId(UID);
        t.setTermZh(zh);
        t.setTermId(id);
        return t;
    }

    private TerminologyService serviceWith(List<Terminology> terms) {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        when(mapper.findEnabled(anyLong())).thenReturn(terms);
        return new TerminologyService(mapper);
    }

    @Test
    void misheardTermsAreSuggestedAsHints() {
        TerminologyService svc = serviceWith(List.of(
                term("肥料", "pupuk"), term("硼", "boron"), term("地块", "blok")));

        Map<String, String> hints = svc.fuzzyIdToZhHints(
                UID, "tanaman kupu dan buron rendah", "id", "zh", 20);

        assertEquals("肥料", hints.get("pupuk"), "kupu 应提示 pupuk(肥料)");
        assertEquals("硼", hints.get("boron"), "buron 应提示 boron(硼)");
        assertFalse(hints.containsKey("blok"), "blok 与文中词不相近,不应误提示");
    }

    @Test
    void exactOccurrenceIsNotDuplicatedAsFuzzyHint() {
        TerminologyService svc = serviceWith(List.of(term("肥料", "pupuk")));
        // 原文已精确含 pupuk → 由精确匹配处理,模糊提示不应再重复
        Map<String, String> hints = svc.fuzzyIdToZhHints(UID, "kami beli pupuk hari ini", "id", "zh", 20);
        assertTrue(hints.isEmpty(), "精确出现的术语不应作为模糊提示重复");
    }

    @Test
    void unrelatedTextProducesNoHints() {
        TerminologyService svc = serviceWith(List.of(term("肥料", "pupuk"), term("硼", "boron")));
        Map<String, String> hints = svc.fuzzyIdToZhHints(UID, "selamat pagi semua hadirin", "id", "zh", 20);
        assertTrue(hints.isEmpty(), "无相近词时不应产生提示");
    }
}
