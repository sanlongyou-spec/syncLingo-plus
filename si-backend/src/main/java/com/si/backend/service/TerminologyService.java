package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.entity.Terminology;
import com.si.backend.mapper.TerminologyMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.vo.TerminologyImportResultVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * 从 Excel 批量导入术语。表头自动识别（中文/印尼语/英语/拼音/分类/备注，支持中英文别名）；
     * 与已有术语按 中文+印尼语+英语 组合去重，重复行跳过。
     */
    @Transactional
    public TerminologyImportResultVo importExcel(Long userId, MultipartFile file) {
        String fileName = file != null ? file.getOriginalFilename() : null;
        log.info("[TerminologyService] importExcel start, userId={}, fileName={}, size={}",
                userId, fileName, file != null ? file.getSize() : 0);
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的 Excel 文件");
        }
        long uid = userId != null ? userId : 1L;
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            // 已有术语的去重键集合（中文|印尼语|英语，trim+小写）
            Set<String> seen = new HashSet<>();
            for (Terminology existing : terminologyMapper.findAll(uid, null, null)) {
                seen.add(dedupKey(existing.getTermZh(), existing.getTermId(), existing.getTermEn()));
            }
            int created = 0;
            int skipped = 0;
            List<String> sheetNames = new java.util.ArrayList<>();
            boolean headerFound = false;
            for (Sheet sheet : workbook) {
                Map<String, Integer> columns = null;
                int headerRowIndex = -1;
                for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 10); rowIndex++) {
                    Map<String, Integer> resolved = resolveTermColumns(sheet.getRow(rowIndex), formatter);
                    if (resolved.containsKey("termZh") || resolved.containsKey("termId") || resolved.containsKey("termEn")) {
                        columns = resolved;
                        headerRowIndex = rowIndex;
                        break;
                    }
                }
                if (columns == null) {
                    continue; // 该 sheet 没有术语列，跳过
                }
                headerFound = true;
                sheetNames.add(sheet.getSheetName());
                for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    String zh = cellText(row, columns.get("termZh"), formatter);
                    String id = cellText(row, columns.get("termId"), formatter);
                    String en = cellText(row, columns.get("termEn"), formatter);
                    if (zh == null && id == null && en == null) continue; // 空行
                    String key = dedupKey(zh, id, en);
                    if (!seen.add(key)) {
                        skipped++;
                        continue;
                    }
                    Terminology term = new Terminology();
                    term.setUserId(uid);
                    term.setTermZh(zh);
                    term.setTermId(id);
                    term.setTermEn(en);
                    term.setPinyin(cellText(row, columns.get("pinyin"), formatter));
                    term.setCategory(cellText(row, columns.get("category"), formatter));
                    term.setNote(cellText(row, columns.get("note"), formatter));
                    term.setSourceSheet(sheet.getSheetName());
                    term.setSourceRow(rowIndex + 1);
                    term.setReviewStatus("APPROVED");
                    term.setEnabled(true);
                    terminologyMapper.insert(term);
                    created++;
                }
            }
            if (!headerFound) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "未找到术语列（中文/印尼语/英语），请检查表头");
            }
            TerminologyImportResultVo result = TerminologyImportResultVo.builder()
                    .sheetName(String.join("、", sheetNames))
                    .createdCount(created)
                    .skippedCount(skipped)
                    .totalCount(created)
                    .build();
            log.info("[TerminologyService] importExcel end, userId={}, created={}, skipped={}", uid, created, skipped);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[TerminologyService] importExcel read failed, fileName={}", fileName, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "Excel 文件读取失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[TerminologyService] importExcel parse failed, fileName={}", fileName, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "Excel 文件解析失败：" + e.getMessage());
        }
    }

    private Map<String, Integer> resolveTermColumns(Row row, DataFormatter formatter) {
        Map<String, Integer> columns = new HashMap<>();
        if (row == null) return columns;
        for (Cell cell : row) {
            String h = formatter.formatCellValue(cell).toLowerCase(Locale.ROOT).replace(" ", "");
            int idx = cell.getColumnIndex();
            if (h.contains("中文") || h.contains("chinese") || h.equals("zh") || h.contains("汉")) {
                columns.putIfAbsent("termZh", idx);
            } else if (h.contains("印尼") || h.contains("indonesia") || h.contains("bahasa")) {
                columns.putIfAbsent("termId", idx);
            } else if (h.contains("英语") || h.contains("英文") || h.contains("english")) {
                columns.putIfAbsent("termEn", idx);
            } else if (h.contains("拼音") || h.contains("pinyin")) {
                columns.putIfAbsent("pinyin", idx);
            } else if (h.contains("分类") || h.contains("类别") || h.contains("category")) {
                columns.putIfAbsent("category", idx);
            } else if (h.contains("备注") || h.contains("说明") || h.contains("note") || h.contains("remark")) {
                columns.putIfAbsent("note", idx);
            }
        }
        return columns;
    }

    private String cellText(Row row, Integer columnIndex, DataFormatter formatter) {
        if (row == null || columnIndex == null || columnIndex < 0) return null;
        Cell cell = row.getCell(columnIndex);
        if (cell == null) return null;
        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }

    private String dedupKey(String zh, String id, String en) {
        return (norm(zh) + "" + norm(id) + "" + norm(en));
    }

    private String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
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
            if (!termMatches(protectedText, sourceTerm)) {
                continue;
            }
            String placeholder = Constants.TERMINOLOGY_PLACEHOLDER_PREFIX
                    + placeholderIndex
                    + Constants.TERMINOLOGY_PLACEHOLDER_SUFFIX;
            protectedText = replaceTerm(protectedText, sourceTerm, placeholder);
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
            if (termMatches(sourceText, sourceTerm) && !correctedText.contains(targetTerm)) {
                correctedText = correctedText + " (" + targetTerm + ")";
                log.info("[TerminologyService] terminology corrected, sourceTermLen={}, targetTermLen={}",
                        sourceTerm.length(), targetTerm.length());
            }
        }
        log.debug("[TerminologyService] applyAfterTranslate end, changed={}", !correctedText.equals(targetText));
        return correctedText;
    }

    /** 术语最小长度：短于此值（单字母 "f" / 单个汉字）一律不参与匹配，避免命中词内部。 */
    private static final int MIN_TERM_LEN = 2;

    /** 是否为拉丁文术语（含字母、且不含 CJK 汉字）。CJK 术语无空格分词，仍按子串处理。 */
    private static boolean isLatinTerm(String term) {
        boolean hasLetter = false;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return false;
            }
            if (Character.isLetter(c)) {
                hasLetter = true;
            }
        }
        return hasLetter;
    }

    private static java.util.regex.Pattern wordPattern(String term) {
        return java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(term) + "\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** 长度<2 的术语（单字母/单汉字）一律不匹配；拉丁文按单词边界，CJK 按子串。 */
    private boolean termMatches(String text, String term) {
        if (term.trim().length() < MIN_TERM_LEN) {
            return false;
        }
        if (isLatinTerm(term)) {
            return wordPattern(term).matcher(text).find();
        }
        return text.contains(term);
    }

    /** 与 {@link #termMatches} 一致的替换：长度<2 跳过；拉丁文按单词边界，CJK 按子串。 */
    private String replaceTerm(String text, String term, String replacement) {
        if (term.trim().length() < MIN_TERM_LEN) {
            return text;
        }
        if (isLatinTerm(term)) {
            return wordPattern(term).matcher(text)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return text.replace(term, replacement);
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
