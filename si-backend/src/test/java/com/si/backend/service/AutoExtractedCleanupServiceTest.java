package com.si.backend.service;

import com.si.backend.mapper.AsrHotwordMapper;
import com.si.backend.mapper.TerminologyMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 3 天 TTL 清理:只删自动来源(AUTO_DOC / AUTO_EXTRACTED),cutoff = now - retentionDays。
 */
class AutoExtractedCleanupServiceTest {

    @Test
    void purgesOnlyAutoSourcesWithRetentionCutoff() {
        TerminologyMapper tm = mock(TerminologyMapper.class);
        AsrHotwordMapper hm = mock(AsrHotwordMapper.class);
        when(tm.deleteBySourceOlderThan(eq("AUTO_DOC"), any())).thenReturn(2);
        when(hm.deleteBySourceTypeOlderThan(eq("AUTO_EXTRACTED"), any())).thenReturn(5);

        AutoExtractedCleanupService svc = new AutoExtractedCleanupService(tm, hm);
        svc.retentionDays = 3;

        svc.purgeExpired();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tm).deleteBySourceOlderThan(eq("AUTO_DOC"), cutoff.capture());
        verify(hm).deleteBySourceTypeOlderThan(eq("AUTO_EXTRACTED"), any());

        LocalDateTime expected = LocalDateTime.now().minusDays(3);
        long driftSec = Math.abs(Duration.between(cutoff.getValue(), expected).toSeconds());
        assertTrue(driftSec < 60, "cutoff 应约为 now-3天, 实际偏差秒=" + driftSec);
    }

    @Test
    void retentionFloorAtOneDay() {
        TerminologyMapper tm = mock(TerminologyMapper.class);
        AsrHotwordMapper hm = mock(AsrHotwordMapper.class);
        AutoExtractedCleanupService svc = new AutoExtractedCleanupService(tm, hm);
        svc.retentionDays = 0; // 异常配置,应被钳到至少 1 天

        svc.purgeExpired();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tm).deleteBySourceOlderThan(eq("AUTO_DOC"), cutoff.capture());
        LocalDateTime expected = LocalDateTime.now().minusDays(1);
        assertTrue(Math.abs(Duration.between(cutoff.getValue(), expected).toSeconds()) < 60);
    }
}
