package com.si.backend.facade;

import com.si.backend.entity.AsrCorrection;
import com.si.backend.service.AsrCorrectionService;
import com.si.backend.vo.AsrCorrectionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ASR 错词库 Facade：列表查询与会后挖词入库的编排。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrCorrectionFacade {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AsrCorrectionService asrCorrectionService;

    public List<AsrCorrectionVo> list(Long userId) {
        log.info("[AsrCorrectionFacade] list start, userId={}", userId);
        List<AsrCorrectionVo> vos = asrCorrectionService.listAll(userId).stream().map(this::toVo).toList();
        log.info("[AsrCorrectionFacade] list end, userId={}, count={}", userId, vos.size());
        return vos;
    }

    public int mine(Long userId, String transcript, String document) {
        log.info("[AsrCorrectionFacade] mine start, userId={}, transcriptLen={}, docLen={}",
                userId, transcript != null ? transcript.length() : 0, document != null ? document.length() : 0);
        int stored = asrCorrectionService.mineAndStore(userId, transcript, document);
        log.info("[AsrCorrectionFacade] mine end, userId={}, stored={}", userId, stored);
        return stored;
    }

    private AsrCorrectionVo toVo(AsrCorrection entity) {
        return AsrCorrectionVo.builder()
                .id(entity.getId())
                .variant(entity.getVariant())
                .canonical(entity.getCanonical())
                .srcLang(entity.getSrcLang())
                .confidence(entity.getConfidence())
                .hitCount(entity.getHitCount())
                .status(entity.getStatus())
                .source(entity.getSource())
                .updateTime(entity.getUpdateTime() != null ? entity.getUpdateTime().format(TIME_FMT) : null)
                .build();
    }
}
