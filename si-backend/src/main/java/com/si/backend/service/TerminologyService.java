package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.entity.Terminology;
import com.si.backend.mapper.TerminologyMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 术语服务，提供术语管理与翻译后修正能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminologyService {

    private final TerminologyMapper terminologyMapper;

    @PostConstruct
    public void initTable() {
        log.info("[TerminologyService] initTable start");
        terminologyMapper.createTableIfNotExists();
        addColumnIfMissing("user_id", terminologyMapper::addUserIdColumnIfNotExists);
        terminologyMapper.backfillDefaultUserId();
        addColumnIfMissing("pinyin", terminologyMapper::addPinyinColumnIfNotExists);
        addColumnIfMissing("note", terminologyMapper::addNoteColumnIfNotExists);
        addColumnIfMissing("source_sheet", terminologyMapper::addSourceSheetColumnIfNotExists);
        addColumnIfMissing("source_row", terminologyMapper::addSourceRowColumnIfNotExists);
        addColumnIfMissing("review_status", terminologyMapper::addReviewStatusColumnIfNotExists);
        log.info("[TerminologyService] initTable end");
    }

    private void addColumnIfMissing(String columnName, Runnable ddlAction) {
        try {
            ddlAction.run();
            log.info("[TerminologyService] column added, column={}", columnName);
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.info("[TerminologyService] column already exists, column={}", columnName);
                return;
            }
            throw e;
        }
    }

    @Transactional
    public Terminology createTerminology(Terminology terminology) {
        log.info("[TerminologyService] createTerminology start, category={}, enabled={}",
                terminology.getCategory(), terminology.getEnabled());
        if (terminology.getEnabled() == null) {
            terminology.setEnabled(true);
        }
        if (terminology.getUserId() == null) {
            terminology.setUserId(1L);
        }
        if (terminology.getReviewStatus() == null || terminology.getReviewStatus().isBlank()) {
            terminology.setReviewStatus("APPROVED");
        }
        terminologyMapper.insert(terminology);
        log.info("[TerminologyService] createTerminology end, id={}", terminology.getId());
        return terminology;
    }

    public List<Terminology> listTerminologies(Long userId, String keyword, Boolean enabled) {
        log.info("[TerminologyService] listTerminologies start, userId={}, keywordLen={}, enabled={}",
                userId, keyword != null ? keyword.length() : 0, enabled);
        List<Terminology> terminologies = terminologyMapper.findAll(userId, keyword, enabled);
        log.info("[TerminologyService] listTerminologies end, count={}", terminologies.size());
        return terminologies;
    }

    @Transactional
    public List<Terminology> createTerminologies(List<Terminology> terminologies) {
        log.info("[TerminologyService] createTerminologies start, count={}",
                terminologies != null ? terminologies.size() : 0);
        if (terminologies == null || terminologies.isEmpty()) {
            return List.of();
        }
        for (Terminology terminology : terminologies) {
            createTerminology(terminology);
        }
        log.info("[TerminologyService] createTerminologies end, count={}", terminologies.size());
        return terminologies;
    }

    public Terminology findOwnedTerminology(Long userId, Long id) {
        Terminology terminology = terminologyMapper.findByIdAndUserId(id, userId);
        if (terminology == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "术语不存在");
        }
        return terminology;
    }

    @Transactional
    public void updateEnabled(Long id, Long userId, Boolean enabled) {
        log.info("[TerminologyService] updateEnabled start, id={}, userId={}, enabled={}", id, userId, enabled);
        terminologyMapper.updateEnabled(id, userId, Boolean.TRUE.equals(enabled));
        log.info("[TerminologyService] updateEnabled end, id={}", id);
    }

    @Transactional
    public void updateTerminology(Long id, Long userId, Terminology terminology) {
        log.info("[TerminologyService] updateTerminology start, id={}, userId={}", id, userId);
        terminology.setId(id);
        terminology.setUserId(userId);
        if (terminology.getEnabled() == null) {
            terminology.setEnabled(true);
        }
        if (terminology.getReviewStatus() == null || terminology.getReviewStatus().isBlank()) {
            terminology.setReviewStatus("APPROVED");
        }
        terminologyMapper.update(terminology);
        log.info("[TerminologyService] updateTerminology end, id={}", id);
    }

    @Transactional
    public void deleteTerminology(Long id, Long userId) {
        log.info("[TerminologyService] deleteTerminology start, id={}, userId={}", id, userId);
        terminologyMapper.deleteById(id, userId);
        log.info("[TerminologyService] deleteTerminology end, id={}", id);
    }

    public TerminologyProtection applyBeforeTranslate(Long userId, String sourceText, String sourceLang, String targetLang) {
        log.debug("[TerminologyService] applyBeforeTranslate start, sourceLang={}, targetLang={}", sourceLang, targetLang);
        if (sourceText == null || sourceText.isBlank()) {
            return TerminologyProtection.empty(sourceText);
        }
        String protectedText = sourceText;
        Map<String, String> targetTermByPlaceholder = new LinkedHashMap<>();
        int placeholderIndex = 0;
        for (Terminology terminology : terminologyMapper.findEnabled(userId)) {
            String sourceTerm = termByLang(terminology, sourceLang);
            String targetTerm = termByLang(terminology, targetLang);
            if (sourceTerm == null || sourceTerm.isBlank() || targetTerm == null || targetTerm.isBlank()) {
                continue;
            }
            if (!protectedText.contains(sourceTerm)) {
                continue;
            }
            String placeholder = Constants.TERMINOLOGY_PLACEHOLDER_PREFIX
                    + placeholderIndex
                    + Constants.TERMINOLOGY_PLACEHOLDER_SUFFIX;
            protectedText = protectedText.replace(sourceTerm, placeholder);
            targetTermByPlaceholder.put(placeholder, targetTerm);
            placeholderIndex++;
        }
        log.debug("[TerminologyService] applyBeforeTranslate end, changed={}, termCount={}",
                !protectedText.equals(sourceText), targetTermByPlaceholder.size());
        return new TerminologyProtection(protectedText, targetTermByPlaceholder);
    }

    public String applyAfterTranslate(
            String sourceText,
            String targetText,
            String sourceLang,
            String targetLang,
            TerminologyProtection protection,
            Long userId
    ) {
        log.debug("[TerminologyService] applyAfterTranslate start, sourceLang={}, targetLang={}", sourceLang, targetLang);
        if (sourceText == null || sourceText.isBlank() || targetText == null || targetText.isBlank()) {
            return targetText;
        }
        String correctedText = targetText;
        if (protection != null) {
            for (Map.Entry<String, String> entry : protection.getTargetTermByPlaceholder().entrySet()) {
                String placeholder = entry.getKey();
                String targetTerm = entry.getValue();
                correctedText = correctedText
                        .replace(placeholder, targetTerm)
                        .replace(placeholder.toLowerCase(), targetTerm);
            }
        }
        for (Terminology terminology : terminologyMapper.findEnabled(userId)) {
            String sourceTerm = termByLang(terminology, sourceLang);
            String targetTerm = termByLang(terminology, targetLang);
            if (sourceTerm == null || sourceTerm.isBlank() || targetTerm == null || targetTerm.isBlank()) {
                continue;
            }
            if (sourceText.contains(sourceTerm) && !correctedText.contains(targetTerm)) {
                correctedText = correctedText + " (" + targetTerm + ")";
                log.info("[TerminologyService] terminology corrected, sourceTermLen={}, targetTermLen={}",
                        sourceTerm.length(), targetTerm.length());
            }
        }
        log.debug("[TerminologyService] applyAfterTranslate end, changed={}", !correctedText.equals(targetText));
        return correctedText;
    }

    private String termByLang(Terminology terminology, String lang) {
        if (lang == null) return null;
        String lower = lang.toLowerCase();
        if (lower.startsWith("zh")) return terminology.getTermZh();
        if (lower.startsWith("id") || Constants.LANG_ID_ISO6391.equalsIgnoreCase(lower)) return terminology.getTermId();
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return terminology.getTermEn();
        return null;
    }

    /**
     * Translation pre-hook result containing protected text and placeholder mappings.
     */
    public static class TerminologyProtection {

        private final String protectedText;
        private final Map<String, String> targetTermByPlaceholder;

        public TerminologyProtection(String protectedText, Map<String, String> targetTermByPlaceholder) {
            this.protectedText = protectedText;
            this.targetTermByPlaceholder = new LinkedHashMap<>(targetTermByPlaceholder);
        }

        public static TerminologyProtection empty(String text) {
            return new TerminologyProtection(text, Collections.emptyMap());
        }

        public String getProtectedText() {
            return protectedText;
        }

        public Map<String, String> getTargetTermByPlaceholder() {
            return targetTermByPlaceholder;
        }
    }
}
