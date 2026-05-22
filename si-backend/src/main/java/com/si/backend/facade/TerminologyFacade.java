package com.si.backend.facade;

import com.si.backend.dto.SaveTerminologyRequest;
import com.si.backend.entity.Terminology;
import com.si.backend.service.TerminologyService;
import com.si.backend.vo.TerminologyVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminologyFacade {

    private final TerminologyService terminologyService;

    public TerminologyVo create(Long userId, SaveTerminologyRequest request) {
        log.info("[TerminologyFacade] create start, userId={}, category={}", userId, request.getCategory());
        Terminology created = terminologyService.createTerminology(toEntity(userId, request));
        log.info("[TerminologyFacade] create end, userId={}, id={}", userId, created.getId());
        return toVo(created);
    }

    public List<TerminologyVo> list(Long userId, String keyword, Boolean enabled) {
        log.info("[TerminologyFacade] list start, userId={}, keywordLen={}, enabled={}",
                userId, keyword != null ? keyword.length() : 0, enabled);
        List<TerminologyVo> result = terminologyService.listTerminologies(userId, keyword, enabled).stream()
                .map(this::toVo)
                .toList();
        log.info("[TerminologyFacade] list end, userId={}, count={}", userId, result.size());
        return result;
    }

    public List<TerminologyVo> createBatch(Long userId, List<SaveTerminologyRequest> requests) {
        log.info("[TerminologyFacade] createBatch start, userId={}, count={}", userId, requests.size());
        List<TerminologyVo> result = terminologyService.createTerminologies(
                        requests.stream().map(request -> toEntity(userId, request)).toList()
                ).stream()
                .map(this::toVo)
                .toList();
        log.info("[TerminologyFacade] createBatch end, userId={}, count={}", userId, result.size());
        return result;
    }

    public void updateEnabled(Long id, Long userId, Boolean enabled) {
        log.info("[TerminologyFacade] updateEnabled start, id={}, userId={}, enabled={}", id, userId, enabled);
        terminologyService.updateEnabled(id, userId, enabled);
        log.info("[TerminologyFacade] updateEnabled end, id={}, userId={}", id, userId);
    }

    public void update(Long id, Long userId, SaveTerminologyRequest request) {
        log.info("[TerminologyFacade] update start, id={}, userId={}", id, userId);
        terminologyService.updateTerminology(id, userId, toEntity(userId, request));
        log.info("[TerminologyFacade] update end, id={}, userId={}", id, userId);
    }

    public void delete(Long id, Long userId) {
        log.info("[TerminologyFacade] delete start, id={}, userId={}", id, userId);
        terminologyService.deleteTerminology(id, userId);
        log.info("[TerminologyFacade] delete end, id={}, userId={}", id, userId);
    }

    private Terminology toEntity(Long userId, SaveTerminologyRequest request) {
        Terminology terminology = new Terminology();
        terminology.setUserId(userId);
        terminology.setTermZh(request.getTermZh());
        terminology.setTermId(request.getTermId());
        terminology.setTermEn(request.getTermEn());
        terminology.setPinyin(request.getPinyin());
        terminology.setCategory(request.getCategory());
        terminology.setNote(request.getNote());
        terminology.setSourceSheet(request.getSourceSheet());
        terminology.setSourceRow(request.getSourceRow());
        terminology.setReviewStatus(request.getReviewStatus());
        terminology.setEnabled(request.getEnabled());
        return terminology;
    }

    private TerminologyVo toVo(Terminology terminology) {
        return TerminologyVo.builder()
                .id(terminology.getId())
                .termZh(terminology.getTermZh())
                .termId(terminology.getTermId())
                .termEn(terminology.getTermEn())
                .pinyin(terminology.getPinyin())
                .category(terminology.getCategory())
                .note(terminology.getNote())
                .sourceSheet(terminology.getSourceSheet())
                .sourceRow(terminology.getSourceRow())
                .reviewStatus(terminology.getReviewStatus())
                .enabled(terminology.getEnabled())
                .createTime(terminology.getCreateTime())
                .updateTime(terminology.getUpdateTime())
                .build();
    }
}
