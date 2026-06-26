package com.si.backend.service;

import com.si.backend.entity.Terminology;
import com.si.backend.mapper.TerminologyMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动术语入库:中+印必填、跨候选/与现有去重、同一印尼词冲突跳过。
 */
class TerminologyAddExtractedTest {

    private final long uid = 1L;

    private Terminology existing(String zh, String id) {
        Terminology t = new Terminology();
        t.setUserId(uid);
        t.setTermZh(zh);
        t.setTermId(id);
        return t;
    }

    private Terminology cand(String zh, String id, String en) {
        Terminology t = new Terminology();
        t.setTermZh(zh);
        t.setTermId(id);
        t.setTermEn(en);
        return t;
    }

    @Test
    void insertsNewSkipsDuplicatesAndConflicts() {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        when(mapper.findAll(anyLong(), isNull(), isNull()))
                .thenReturn(List.of(existing("施肥", "pupuk")));
        TerminologyService service = new TerminologyService(mapper);

        int created = service.addExtractedTerms(uid, List.of(
                cand("硼", "boron", "boron"),     // 新增 ✓
                cand("钾肥", "pupuk", null),       // 印尼词 pupuk 已存在 → 跳过
                cand("缺素", "", null),            // 无印尼语 → 跳过
                cand("", "kalium", null),          // 无中文 → 跳过
                cand("叶片分析", "LSU", null),     // 新增 ✓
                cand("叶片分析", "LSU", null)      // 与上一条同 → 跳过
        ));

        assertEquals(2, created);
        ArgumentCaptor<Terminology> captor = ArgumentCaptor.forClass(Terminology.class);
        verify(mapper, times(2)).insert(captor.capture());
        List<String> insertedIds = captor.getAllValues().stream().map(Terminology::getTermId).toList();
        assertTrue(insertedIds.contains("boron"));
        assertTrue(insertedIds.contains("LSU"));
        // 入库标记为强制级(启用 + 已审批),与手动导入同等
        captor.getAllValues().forEach(t -> {
            assertTrue(Boolean.TRUE.equals(t.getEnabled()));
            assertEquals("APPROVED", t.getReviewStatus());
        });
    }

    @Test
    void emptyCandidatesNoInsert() {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        TerminologyService service = new TerminologyService(mapper);
        assertEquals(0, service.addExtractedTerms(uid, List.of()));
        verify(mapper, times(0)).insert(any());
    }
}
