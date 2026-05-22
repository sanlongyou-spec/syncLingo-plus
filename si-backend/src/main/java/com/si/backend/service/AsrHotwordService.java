package com.si.backend.service;

import com.si.backend.entity.AsrHotword;
import com.si.backend.entity.Terminology;
import com.si.backend.mapper.AsrHotwordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages user-owned ASR hotwords and terminology-derived suggestions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrHotwordService {

    private final AsrHotwordMapper hotwordMapper;

    @PostConstruct
    public void initTable() {
        log.info("[AsrHotwordService] initTable start");
        hotwordMapper.createTableIfNotExists();
        try {
            hotwordMapper.addLastUsedTimeColumnIfNotExists();
        } catch (DataAccessException e) {
            if (e.getMessage() == null || !e.getMessage().contains("Duplicate column")) {
                throw e;
            }
        }
        log.info("[AsrHotwordService] initTable end");
    }

    public List<AsrHotword> list(Long userId, String keyword, Boolean enabled, String language, String category) {
        log.info("[AsrHotwordService] list start, userId={}, keywordLen={}, enabled={}",
                userId, keyword != null ? keyword.length() : 0, enabled);
        List<AsrHotword> result = hotwordMapper.findAll(userId, keyword, enabled, language, category);
        log.info("[AsrHotwordService] list end, userId={}, count={}", userId, result.size());
        return result;
    }

    public List<AsrHotword> listActive(Long userId, String language) {
        return hotwordMapper.findActiveByUserId(userId, language);
    }

    public List<AsrHotword> filterSelected(List<AsrHotword> activeHotwords, String selectedIds) {
        if (selectedIds == null || selectedIds.isBlank()) {
            return activeHotwords;
        }
        List<Long> allowedIds = List.of(selectedIds.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
        return activeHotwords.stream()
                .filter(hotword -> hotword.getId() != null && allowedIds.contains(hotword.getId()))
                .toList();
    }

    @Transactional
    public List<AsrHotword> createBatch(Long userId, List<AsrHotword> hotwords) {
        log.info("[AsrHotwordService] createBatch start, userId={}, count={}", userId, hotwords != null ? hotwords.size() : 0);
        if (hotwords == null || hotwords.isEmpty()) {
            return List.of();
        }
        hotwords.forEach(hotword -> create(userId, hotword));
        log.info("[AsrHotwordService] createBatch end, userId={}, count={}", userId, hotwords.size());
        return hotwords;
    }

    public void markUsed(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        hotwordMapper.markUsed(userId, ids);
    }

    @Transactional
    public AsrHotword create(Long userId, AsrHotword hotword) {
        log.info("[AsrHotwordService] create start, userId={}, phraseLen={}",
                userId, hotword.getPhrase() != null ? hotword.getPhrase().length() : 0);
        hotword.setUserId(userId);
        if (hotword.getEnabled() == null) hotword.setEnabled(true);
        if (hotword.getWeight() == null) hotword.setWeight(1.0);
        if (hotword.getSourceType() == null || hotword.getSourceType().isBlank()) hotword.setSourceType("MANUAL");
        hotwordMapper.insert(hotword);
        log.info("[AsrHotwordService] create end, userId={}, id={}", userId, hotword.getId());
        return hotword;
    }

    @Transactional
    public void update(Long id, Long userId, AsrHotword hotword) {
        log.info("[AsrHotwordService] update start, id={}, userId={}", id, userId);
        hotword.setId(id);
        hotword.setUserId(userId);
        if (hotword.getEnabled() == null) hotword.setEnabled(true);
        if (hotword.getWeight() == null) hotword.setWeight(1.0);
        hotwordMapper.update(hotword);
        log.info("[AsrHotwordService] update end, id={}, userId={}", id, userId);
    }

    @Transactional
    public void updateEnabled(Long id, Long userId, Boolean enabled) {
        log.info("[AsrHotwordService] updateEnabled start, id={}, userId={}, enabled={}", id, userId, enabled);
        hotwordMapper.updateEnabled(id, userId, Boolean.TRUE.equals(enabled));
        log.info("[AsrHotwordService] updateEnabled end, id={}, userId={}", id, userId);
    }

    @Transactional
    public void delete(Long id, Long userId) {
        log.info("[AsrHotwordService] delete start, id={}, userId={}", id, userId);
        hotwordMapper.deleteById(id, userId);
        log.info("[AsrHotwordService] delete end, id={}, userId={}", id, userId);
    }

    @Transactional
    public List<AsrHotword> createFromTerminology(Long userId, Terminology terminology) {
        log.info("[AsrHotwordService] createFromTerminology start, userId={}, terminologyId={}",
                userId, terminology.getId());
        List<AsrHotword> created = new ArrayList<>();
        addDerivedHotword(created, userId, terminology, terminology.getTermZh(), "zh-CN");
        addDerivedHotword(created, userId, terminology, terminology.getTermId(), "id-ID");
        addDerivedHotword(created, userId, terminology, terminology.getTermEn(), "en-US");
        log.info("[AsrHotwordService] createFromTerminology end, userId={}, terminologyId={}, count={}",
                userId, terminology.getId(), created.size());
        return created;
    }

    private void addDerivedHotword(
            List<AsrHotword> created,
            Long userId,
            Terminology terminology,
            String phrase,
            String language
    ) {
        if (phrase == null || phrase.isBlank()) return;
        AsrHotword hotword = new AsrHotword();
        hotword.setPhrase(phrase);
        hotword.setLanguage(language);
        hotword.setCategory(terminology.getCategory());
        hotword.setWeight(1.0);
        hotword.setSourceType("TERMINOLOGY");
        hotword.setSourceTerminologyId(terminology.getId());
        hotword.setEnabled(true);
        created.add(create(userId, hotword));
    }
}
