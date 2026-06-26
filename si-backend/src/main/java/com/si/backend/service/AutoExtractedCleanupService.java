package com.si.backend.service;

import com.si.backend.mapper.AsrHotwordMapper;
import com.si.backend.mapper.TerminologyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 自动抽取的热词/术语只作【临时增强】,保留 N 天(默认 3)后自动删除,绝不永久污染库;
 * 手动导入的术语/热词(来源不是自动抽取)永不受此清理影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoExtractedCleanupService {

    /** 术语表里"自动从文件抽取"的来源标记 */
    static final String AUTO_TERM_SOURCE = "AUTO_DOC";
    /** 热词表里"自动抽取"的来源标记 */
    static final String AUTO_HOTWORD_SOURCE = "AUTO_EXTRACTED";

    private final TerminologyMapper terminologyMapper;
    private final AsrHotwordMapper asrHotwordMapper;

    /** 自动抽取条目的保留天数,过期即删 */
    @Value("${app.auto-extracted.retention-days:3}")
    int retentionDays;

    /**
     * 周期清理过期的自动抽取热词/术语。默认启动 2 分钟后首次执行,之后每 6 小时一次。
     */
    @Scheduled(
            initialDelayString = "${app.auto-extracted.cleanup-initial-delay-ms:120000}",
            fixedDelayString = "${app.auto-extracted.cleanup-interval-ms:21600000}")
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        int terms = terminologyMapper.deleteBySourceOlderThan(AUTO_TERM_SOURCE, cutoff);
        int hotwords = asrHotwordMapper.deleteBySourceTypeOlderThan(AUTO_HOTWORD_SOURCE, cutoff);
        if (terms > 0 || hotwords > 0) {
            log.info("[AutoExtractedCleanup] purged retentionDays={}, cutoff={}, terms={}, hotwords={}",
                    retentionDays, cutoff, terms, hotwords);
        } else {
            log.debug("[AutoExtractedCleanup] nothing to purge, cutoff={}", cutoff);
        }
    }
}
