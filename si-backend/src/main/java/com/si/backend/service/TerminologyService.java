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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 术语服务，提供术语管理与翻译后修正能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminologyService {

    private static final long INDEX_CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    private final TerminologyMapper terminologyMapper;
    private final Map<Long, TerminologyIndexSnapshot> terminologyIndexCache = new ConcurrentHashMap<>();

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
        requireUserId(terminology.getUserId());
        if (terminology.getEnabled() == null) {
            terminology.setEnabled(true);
        }
        if (terminology.getReviewStatus() == null || terminology.getReviewStatus().isBlank()) {
            terminology.setReviewStatus("APPROVED");
        }
        terminologyMapper.insert(terminology);
        invalidateTerminologyIndex(terminology.getUserId());
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
        requireUserId(userId);
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的 Excel 文件");
        }
        long uid = userId;
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
            invalidateTerminologyIndex(uid);
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

    /**
     * 把"从双语会议文件自动抽取的术语对"入库(强制级,与手动导入同等:exact 命中即"必须遵守")。
     * 去重:① 完整三元组(中|印|英)已存在 → 跳过;② 同一印尼词已有映射 → 跳过(避免一个 id 对多个 zh 的冲突)。
     * 只接受至少含中文+印尼语的对。返回新建条数。
     */
    public int addExtractedTerms(Long userId, List<Terminology> candidates) {
        requireUserId(userId);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        long uid = userId;
        Set<String> seenTriple = new HashSet<>();
        Set<String> seenId = new HashSet<>();
        for (Terminology existing : terminologyMapper.findAll(uid, null, null)) {
            seenTriple.add(dedupKey(existing.getTermZh(), existing.getTermId(), existing.getTermEn()));
            if (existing.getTermId() != null && !existing.getTermId().isBlank()) {
                seenId.add(norm(existing.getTermId()));
            }
        }
        int created = 0;
        for (Terminology c : candidates) {
            String zh = blankToNull(c.getTermZh());
            String id = blankToNull(c.getTermId());
            String en = blankToNull(c.getTermEn());
            if (zh == null || id == null) {
                continue; // 自动术语需中+印对照才有意义(否则无法支撑 id→zh 精确命中)
            }
            if (!seenTriple.add(dedupKey(zh, id, en))) {
                continue;
            }
            if (!seenId.add(norm(id))) {
                continue; // 该印尼词已有映射,跳过避免冲突译法
            }
            Terminology term = new Terminology();
            term.setUserId(uid);
            term.setTermZh(zh);
            term.setTermId(id);
            term.setTermEn(en);
            term.setCategory(blankToNull(c.getCategory()));
            term.setSourceSheet("AUTO_DOC");
            term.setReviewStatus("APPROVED");
            term.setEnabled(true);
            terminologyMapper.insert(term);
            created++;
        }
        if (created > 0) {
            invalidateTerminologyIndex(uid);
        }
        log.info("[TerminologyService] addExtractedTerms end, userId={}, candidates={}, created={}",
                uid, candidates.size(), created);
        return created;
    }

    private String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
        requireModified(terminologyMapper.updateEnabled(id, userId, Boolean.TRUE.equals(enabled)));
        invalidateTerminologyIndex(userId);
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
        requireModified(terminologyMapper.update(terminology));
        invalidateTerminologyIndex(userId);
        log.info("[TerminologyService] updateTerminology end, id={}", id);
    }

    @Transactional
    public void deleteTerminology(Long id, Long userId) {
        log.info("[TerminologyService] deleteTerminology start, id={}, userId={}", id, userId);
        requireModified(terminologyMapper.deleteById(id, userId));
        invalidateTerminologyIndex(userId);
        log.info("[TerminologyService] deleteTerminology end, id={}", id);
    }

    /** 清空当前用户的全部术语，返回删除条数。 */
    @Transactional
    public int clearAllTerminologies(Long userId) {
        log.info("[TerminologyService] clearAllTerminologies start, userId={}", userId);
        requireUserId(userId);
        int deleted = terminologyMapper.deleteAllByUserId(userId);
        invalidateTerminologyIndex(userId);
        log.info("[TerminologyService] clearAllTerminologies end, userId={}, deleted={}", userId, deleted);
        return deleted;
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
    }

    private void requireModified(int modifiedRows) {
        if (modifiedRows == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "术语不存在");
        }
    }

    public TerminologyProtection applyBeforeTranslate(Long userId, String sourceText, String sourceLang, String targetLang) {
        long startMs = System.currentTimeMillis();
        log.info("[TerminologyService] applyBeforeTranslate start, userId={}, sourceLang={}, targetLang={}, sourceLen={}, sourceHash={}",
                userId, sourceLang, targetLang, sourceText != null ? sourceText.length() : 0, diagnosticHash(sourceText));
        if (sourceText == null || sourceText.isBlank()) {
            log.info("[TerminologyService] applyBeforeTranslate end, userId={}, sourceHash={}, reason=blankInput, costMs={}",
                    userId, diagnosticHash(sourceText), System.currentTimeMillis() - startMs);
            return TerminologyProtection.empty(sourceText);
        }
        List<TerminologyCandidate> candidates = loadTerminologyIndex(userId).candidates(sourceLang, targetLang);
        if (candidates.isEmpty()) {
            log.info("[TerminologyService] applyBeforeTranslate end, userId={}, sourceHash={}, candidates=0, reason=noCandidates, costMs={}",
                    userId, diagnosticHash(sourceText), System.currentTimeMillis() - startMs);
            return TerminologyProtection.empty(sourceText);
        }
        List<TerminologyOccurrence> rawOccurrences = findSourceOccurrences(sourceText, candidates);
        List<TerminologyOccurrence> selectedOccurrences = selectLongestNonOverlapping(rawOccurrences);
        log.info("[TerminologyService] applyBeforeTranslate matched, userId={}, sourceHash={}, candidates={}, rawOccurrences={}, selectedOccurrences={}",
                userId, diagnosticHash(sourceText), candidates.size(), rawOccurrences.size(), selectedOccurrences.size());
        if (selectedOccurrences.isEmpty()) {
            log.info("[TerminologyService] applyBeforeTranslate end, userId={}, sourceHash={}, changed=false, termCount=0, costMs={}",
                    userId, diagnosticHash(sourceText), System.currentTimeMillis() - startMs);
            return TerminologyProtection.empty(sourceText);
        }
        StringBuilder protectedText = new StringBuilder(sourceText);
        Map<String, String> targetTermByPlaceholder = new LinkedHashMap<>();
        Map<String, String> sourceTermByPlaceholder = new LinkedHashMap<>();
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < selectedOccurrences.size(); i++) {
            TerminologyOccurrence occurrence = selectedOccurrences.get(i);
            String placeholder = Constants.TERMINOLOGY_PLACEHOLDER_PREFIX
                    + i
                    + Constants.TERMINOLOGY_PLACEHOLDER_SUFFIX;
            placeholders.add(placeholder);
            targetTermByPlaceholder.put(placeholder, occurrence.candidate().targetTerm());
            sourceTermByPlaceholder.put(placeholder, occurrence.candidate().sourceTerm());
        }
        for (int i = selectedOccurrences.size() - 1; i >= 0; i--) {
            TerminologyOccurrence occurrence = selectedOccurrences.get(i);
            protectedText.replace(occurrence.start(), occurrence.end(), placeholders.get(i));
        }
        log.info("[TerminologyService] applyBeforeTranslate end, userId={}, sourceHash={}, changed=true, termCount={}, protectedLen={}, costMs={}",
                userId, diagnosticHash(sourceText), targetTermByPlaceholder.size(), protectedText.length(),
                System.currentTimeMillis() - startMs);
        return new TerminologyProtection(protectedText.toString(), targetTermByPlaceholder, sourceTermByPlaceholder);
    }

    /**
     * 模糊匹配:返回印尼语原文里"形近某术语"的术语对照(印尼语→中文),用于 LLM 纠错翻译。
     * 解决 ASR 把术语听错(kupu≈pupuk、buron≈boron)导致精确匹配失效的场景——
     * 把可能被听错的术语作为"参考"提示给 LLM。只处理单词拉丁术语,按编辑距离阈值取近形词。
     *
     * @param maxHints 最多返回的提示数(防止 prompt 过大)
     * @return 印尼语→中文 的有序映射(按相近程度),可能为空
     */
    public LinkedHashMap<String, String> fuzzyIdToZhHints(
            Long userId, String sourceText, String sourceLang, String targetLang, int maxHints) {
        LinkedHashMap<String, String> hints = new LinkedHashMap<>();
        if (sourceText == null || sourceText.isBlank() || maxHints <= 0) {
            return hints;
        }
        List<TerminologyCandidate> candidates;
        try {
            candidates = loadTerminologyIndex(userId).candidates(sourceLang, targetLang);
        } catch (Exception e) {
            log.debug("[TerminologyService] fuzzyIdToZhHints load failed, reason={}", e.getMessage());
            return hints;
        }
        if (candidates.isEmpty()) {
            return hints;
        }
        String lower = sourceText.toLowerCase(Locale.ROOT);
        Set<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : lower.split("[^\\p{IsLatin}]+")) {
            if (token.length() >= FUZZY_MIN_LEN) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return hints;
        }
        // (distance, sourceTerm, targetTerm) — 取编辑距离最小的若干个
        List<Object[]> scored = new ArrayList<>();
        for (TerminologyCandidate candidate : candidates) {
            String src = candidate.sourceTerm();
            if (src == null) {
                continue;
            }
            String s = src.toLowerCase(Locale.ROOT);
            if (s.length() < FUZZY_MIN_LEN || s.indexOf(' ') >= 0 || !isLatinTerm(src)) {
                continue;
            }
            if (lower.contains(s)) {
                continue;   // 精确出现的由 applyBeforeTranslate 处理,这里只补"形近未精确命中"
            }
            int maxAllowed = s.length() <= FUZZY_SHORT_LEN ? 1 : 2;
            int best = Integer.MAX_VALUE;
            for (String token : tokens) {
                if (Math.abs(token.length() - s.length()) > maxAllowed) {
                    continue;
                }
                int distance = boundedLevenshtein(token, s, maxAllowed);
                if (distance < best) {
                    best = distance;
                }
                if (best <= 1) {
                    break;
                }
            }
            if (best >= 1 && best <= maxAllowed) {
                scored.add(new Object[]{best, src, candidate.targetTerm()});
            }
        }
        scored.sort(Comparator.comparingInt(row -> (int) row[0]));
        for (Object[] row : scored) {
            if (hints.size() >= maxHints) {
                break;
            }
            hints.putIfAbsent((String) row[1], (String) row[2]);
        }
        log.debug("[TerminologyService] fuzzyIdToZhHints userId={}, tokens={}, hints={}",
                userId, tokens.size(), hints.size());
        return hints;
    }

    /** 模糊匹配术语的最小长度(过短易误伤) */
    private static final int FUZZY_MIN_LEN = 4;
    /** 长度=4 的术语只允许编辑距离 1,更长(≥5)的允许 2(覆盖 kupu→pupuk 这类 ASR 错听) */
    private static final int FUZZY_SHORT_LEN = 4;

    /** 带上界的 Levenshtein:超过 maxDistance 立即返回 maxDistance+1,避免无谓计算。 */
    private static int boundedLevenshtein(String a, String b, int maxDistance) {
        int lenA = a.length();
        int lenB = b.length();
        if (Math.abs(lenA - lenB) > maxDistance) {
            return maxDistance + 1;
        }
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];
        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lenB; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > maxDistance) {
                return maxDistance + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }

    public String protectTargetTermsForRewrite(String targetText, TerminologyProtection protection) {
        long startMs = System.currentTimeMillis();
        int placeholderCount = protection != null ? protection.getTargetTermByPlaceholder().size() : 0;
        log.info("[TerminologyService] protectTargetTermsForRewrite start, textLen={}, textHash={}, placeholders={}",
                targetText != null ? targetText.length() : 0, diagnosticHash(targetText), placeholderCount);
        if (targetText == null || targetText.isBlank() || protection == null
                || protection.getTargetTermByPlaceholder().isEmpty()) {
            log.info("[TerminologyService] protectTargetTermsForRewrite end, textHash={}, changed=false, reason=notApplicable, costMs={}",
                    diagnosticHash(targetText), System.currentTimeMillis() - startMs);
            return targetText;
        }
        List<TargetTermOccurrence> rawOccurrences = findTargetOccurrences(targetText, protection.getTargetTermByPlaceholder());
        List<TargetTermOccurrence> selectedOccurrences = selectLongestNonOverlappingTargets(rawOccurrences);
        log.info("[TerminologyService] protectTargetTermsForRewrite matched, textHash={}, placeholders={}, rawOccurrences={}, selectedOccurrences={}",
                diagnosticHash(targetText), placeholderCount, rawOccurrences.size(), selectedOccurrences.size());
        if (selectedOccurrences.isEmpty()) {
            log.info("[TerminologyService] protectTargetTermsForRewrite end, textHash={}, changed=false, termCount=0, costMs={}",
                    diagnosticHash(targetText), System.currentTimeMillis() - startMs);
            return targetText;
        }
        StringBuilder protectedText = new StringBuilder(targetText);
        for (int i = selectedOccurrences.size() - 1; i >= 0; i--) {
            TargetTermOccurrence occurrence = selectedOccurrences.get(i);
            protectedText.replace(occurrence.start(), occurrence.end(), occurrence.placeholder());
        }
        log.info("[TerminologyService] protectTargetTermsForRewrite end, textHash={}, changed=true, termCount={}, protectedLen={}, costMs={}",
                diagnosticHash(targetText), selectedOccurrences.size(), protectedText.length(),
                System.currentTimeMillis() - startMs);
        return protectedText.toString();
    }

    public String applyAfterTranslate(
            String sourceText,
            String targetText,
            String sourceLang,
            String targetLang,
            TerminologyProtection protection,
            Long userId
    ) {
        long startMs = System.currentTimeMillis();
        int placeholderCount = protection != null ? protection.getTargetTermByPlaceholder().size() : 0;
        log.info("[TerminologyService] applyAfterTranslate start, userId={}, sourceLang={}, targetLang={}, sourceLen={}, targetLen={}, targetHash={}, placeholders={}",
                userId, sourceLang, targetLang, sourceText != null ? sourceText.length() : 0,
                targetText != null ? targetText.length() : 0, diagnosticHash(targetText), placeholderCount);
        if (sourceText == null || sourceText.isBlank() || targetText == null || targetText.isBlank()) {
            log.info("[TerminologyService] applyAfterTranslate end, userId={}, targetHash={}, reason=blankInput, costMs={}",
                    userId, diagnosticHash(targetText), System.currentTimeMillis() - startMs);
            return targetText;
        }
        String correctedText = targetText;
        int restoredCount = 0;
        if (protection != null) {
            for (Map.Entry<String, String> entry : protection.getTargetTermByPlaceholder().entrySet()) {
                String placeholder = entry.getKey();
                String targetTerm = entry.getValue();
                String beforeRestore = correctedText;
                // 先精确替换；再容错替换：翻译/LLM 常把 __SI_TERM_0__ 改成大小写/下划线/空格变体，按序号做宽松匹配
                correctedText = correctedText
                        .replace(placeholder, targetTerm)
                        .replace(placeholder.toLowerCase(), targetTerm);
                String idx = placeholder.replaceAll("\\D", "");
                if (!idx.isEmpty()) {
                    correctedText = tolerantPlaceholderPattern(idx).matcher(correctedText)
                            .replaceAll(java.util.regex.Matcher.quoteReplacement(targetTerm));
                }
                if (!beforeRestore.equals(correctedText)) {
                    restoredCount++;
                }
            }
        }
        int residualBeforeCleanup = countResidualPlaceholders(correctedText);
        // 兜底：清除任何残留的占位符(没还原成功的)，绝不让 SI_TERM_N 出现在最终译文/TTS
        correctedText = RESIDUAL_PLACEHOLDER.matcher(correctedText).replaceAll("")
                .replaceAll("\\s{2,}", " ").trim();
        // 术语只走"译前占位符 → 译后内联还原"这条干净路径(上面)。
        // 已移除原先"在句尾追加 (目标译名)"的兜底：术语多/多候选时会满屏括号、污染译文。
        log.info("[TerminologyService] applyAfterTranslate end, userId={}, targetHash={}, changed={}, placeholders={}, restored={}, residualBeforeCleanup={}, resultLen={}, costMs={}",
                userId, diagnosticHash(targetText), !correctedText.equals(targetText), placeholderCount, restoredCount,
                residualBeforeCleanup, correctedText.length(), System.currentTimeMillis() - startMs);
        return correctedText;
    }

    private TerminologyIndexSnapshot loadTerminologyIndex(Long userId) {
        if (userId == null) {
            log.info("[TerminologyService] terminology index skipped, reason=anonymousUser");
            return new TerminologyIndexSnapshot(List.of(), System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        TerminologyIndexSnapshot cached = terminologyIndexCache.get(userId);
        if (cached != null && !cached.isExpired(now)) {
            log.info("[TerminologyService] terminology index cache hit, userId={}, enabledCount={}, ageMs={}, languagePairIndexes={}",
                    userId, cached.enabledTerms.size(), now - cached.loadedAtMillis, cached.candidatesByLanguagePair.size());
            return cached;
        }
        if (cached != null) {
            log.info("[TerminologyService] terminology index cache expired, userId={}, enabledCount={}, ageMs={}",
                    userId, cached.enabledTerms.size(), now - cached.loadedAtMillis);
        } else {
            log.info("[TerminologyService] terminology index cache miss, userId={}", userId);
        }
        long loadStartMs = System.currentTimeMillis();
        List<Terminology> enabledTerms = terminologyMapper.findEnabled(userId);
        TerminologyIndexSnapshot snapshot = new TerminologyIndexSnapshot(enabledTerms, now);
        terminologyIndexCache.put(userId, snapshot);
        log.info("[TerminologyService] terminology index loaded, userId={}, enabledCount={}, costMs={}",
                userId, enabledTerms.size(), System.currentTimeMillis() - loadStartMs);
        return snapshot;
    }

    private void invalidateTerminologyIndex(Long userId) {
        if (userId == null) {
            return;
        }
        terminologyIndexCache.remove(userId);
        log.debug("[TerminologyService] terminology index invalidated, userId={}", userId);
    }

    private List<TerminologyCandidate> buildCandidates(
            List<Terminology> enabledTerms,
            String sourceLang,
            String targetLang
    ) {
        long startMs = System.currentTimeMillis();
        Map<String, TerminologyCandidate> candidateBySourceKey = new LinkedHashMap<>();
        Set<String> ambiguousSourceKeys = new HashSet<>();
        int skippedInvalid = 0;
        for (Terminology terminology : enabledTerms) {
            String sourceTerm = cleanSourceTerm(termByLang(terminology, sourceLang));
            String targetTerm = cleanTermValue(termByLang(terminology, targetLang));
            if (!isMatchableTerm(sourceTerm) || targetTerm == null || targetTerm.isBlank()) {
                skippedInvalid++;
                continue;
            }
            String sourceKey = normalizeTermKey(sourceTerm);
            TerminologyCandidate existing = candidateBySourceKey.get(sourceKey);
            if (existing == null) {
                candidateBySourceKey.put(sourceKey, new TerminologyCandidate(
                        sourceTerm,
                        targetTerm,
                        wordPatternIfNeeded(sourceTerm),
                        terminology.getId()
                ));
                continue;
            }
            if (!normalizeTargetTerm(existing.targetTerm()).equals(normalizeTargetTerm(targetTerm))) {
                ambiguousSourceKeys.add(sourceKey);
            }
        }
        List<TerminologyCandidate> candidates = candidateBySourceKey.entrySet().stream()
                .filter(entry -> !ambiguousSourceKeys.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted(Comparator
                        .comparingInt((TerminologyCandidate candidate) -> candidate.sourceTerm().length()).reversed()
                        .thenComparing(candidate -> candidate.sourceTerm().toLowerCase(Locale.ROOT))
                        .thenComparing(candidate -> candidate.id() != null ? candidate.id() : Long.MAX_VALUE))
                .toList();
        if (!ambiguousSourceKeys.isEmpty()) {
            log.info("[TerminologyService] ambiguous terminology skipped, sourceLang={}, targetLang={}, count={}",
                    sourceLang, targetLang, ambiguousSourceKeys.size());
        }
        log.info("[TerminologyService] terminology candidates built, sourceLang={}, targetLang={}, enabledCount={}, candidateKeys={}, ambiguous={}, skippedInvalid={}, count={}, costMs={}",
                sourceLang, targetLang, enabledTerms != null ? enabledTerms.size() : 0,
                candidateBySourceKey.size(), ambiguousSourceKeys.size(), skippedInvalid, candidates.size(),
                System.currentTimeMillis() - startMs);
        return candidates;
    }

    private List<TerminologyOccurrence> findSourceOccurrences(String text, List<TerminologyCandidate> candidates) {
        List<TerminologyOccurrence> occurrences = new ArrayList<>();
        for (TerminologyCandidate candidate : candidates) {
            if (candidate.sourcePattern() != null) {
                Matcher matcher = candidate.sourcePattern().matcher(text);
                while (matcher.find()) {
                    occurrences.add(new TerminologyOccurrence(matcher.start(), matcher.end(), candidate));
                }
                continue;
            }
            int fromIndex = 0;
            while (fromIndex < text.length()) {
                int start = text.indexOf(candidate.sourceTerm(), fromIndex);
                if (start < 0) {
                    break;
                }
                occurrences.add(new TerminologyOccurrence(start, start + candidate.sourceTerm().length(), candidate));
                fromIndex = start + 1;
            }
        }
        return occurrences;
    }

    private List<TargetTermOccurrence> findTargetOccurrences(
            String text,
            Map<String, String> targetTermByPlaceholder
    ) {
        List<TargetTermOccurrence> occurrences = new ArrayList<>();
        for (Map.Entry<String, String> entry : targetTermByPlaceholder.entrySet()) {
            String placeholder = entry.getKey();
            String targetTerm = cleanSourceTerm(entry.getValue());
            if (!isMatchableTerm(targetTerm)) {
                continue;
            }
            Pattern pattern = wordPatternIfNeeded(targetTerm);
            if (pattern != null) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    occurrences.add(new TargetTermOccurrence(matcher.start(), matcher.end(), placeholder));
                }
                continue;
            }
            int fromIndex = 0;
            while (fromIndex < text.length()) {
                int start = text.indexOf(targetTerm, fromIndex);
                if (start < 0) {
                    break;
                }
                occurrences.add(new TargetTermOccurrence(start, start + targetTerm.length(), placeholder));
                fromIndex = start + 1;
            }
        }
        return occurrences;
    }

    private List<TerminologyOccurrence> selectLongestNonOverlapping(List<TerminologyOccurrence> occurrences) {
        List<TerminologyOccurrence> priority = new ArrayList<>(occurrences);
        priority.sort(Comparator
                .comparingInt(TerminologyOccurrence::length).reversed()
                .thenComparing(TerminologyOccurrence::start)
                .thenComparing(occurrence -> occurrence.candidate().sourceTerm().toLowerCase(Locale.ROOT)));
        List<TerminologyOccurrence> selected = new ArrayList<>();
        for (TerminologyOccurrence occurrence : priority) {
            if (selected.stream().noneMatch(existing -> overlaps(existing.start(), existing.end(),
                    occurrence.start(), occurrence.end()))) {
                selected.add(occurrence);
            }
        }
        selected.sort(Comparator.comparingInt(TerminologyOccurrence::start));
        return selected;
    }

    private List<TargetTermOccurrence> selectLongestNonOverlappingTargets(List<TargetTermOccurrence> occurrences) {
        List<TargetTermOccurrence> priority = new ArrayList<>(occurrences);
        priority.sort(Comparator
                .comparingInt(TargetTermOccurrence::length).reversed()
                .thenComparing(TargetTermOccurrence::start)
                .thenComparing(TargetTermOccurrence::placeholder));
        List<TargetTermOccurrence> selected = new ArrayList<>();
        for (TargetTermOccurrence occurrence : priority) {
            if (selected.stream().noneMatch(existing -> overlaps(existing.start(), existing.end(),
                    occurrence.start(), occurrence.end()))) {
                selected.add(occurrence);
            }
        }
        selected.sort(Comparator.comparingInt(TargetTermOccurrence::start));
        return selected;
    }

    private boolean overlaps(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        return firstStart < secondEnd && secondStart < firstEnd;
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

    private static Pattern wordPattern(String term) {
        return Pattern.compile(
                "\\b" + Pattern.quote(term) + "\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    private Pattern wordPatternIfNeeded(String term) {
        return isLatinTerm(term) ? wordPattern(term) : null;
    }

    private boolean isMatchableTerm(String term) {
        return term != null && term.trim().length() >= MIN_TERM_LEN;
    }

    private String cleanSourceTerm(String term) {
        if (term == null) {
            return null;
        }
        String cleaned = term.trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalizeTermKey(String term) {
        String cleaned = cleanSourceTerm(term);
        if (cleaned == null) {
            return "";
        }
        return isLatinTerm(cleaned) ? cleaned.toLowerCase(Locale.ROOT) : cleaned;
    }

    private String normalizeTargetTerm(String term) {
        String cleaned = cleanSourceTerm(term);
        return cleaned == null ? "" : cleaned.toLowerCase(Locale.ROOT);
    }

    private String languagePairKey(String sourceLang, String targetLang) {
        return normalizeLanguageKey(sourceLang) + "->" + normalizeLanguageKey(targetLang);
    }

    private String normalizeLanguageKey(String lang) {
        if (lang == null || lang.isBlank()) {
            return "";
        }
        String lower = lang.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) {
            return "zh";
        }
        if (lower.startsWith("id") || Constants.LANG_ID_ISO6391.equalsIgnoreCase(lower)) {
            return "id";
        }
        if (lower.startsWith(Constants.LANG_EN_SHORT)) {
            return Constants.LANG_EN_SHORT;
        }
        return lower;
    }

    /** 残留占位符兜底清洗：匹配 SI_TERM_数字 的各种被改写变体(大小写/下划线/空格)。 */
    private static final java.util.regex.Pattern RESIDUAL_PLACEHOLDER =
            java.util.regex.Pattern.compile("(?i)_*si[_ ]*term[_ ]*[0-9]+_*");

    /** 按序号宽松匹配某个占位符(容忍大小写/下划线/空格变体)。 */
    private static java.util.regex.Pattern tolerantPlaceholderPattern(String idx) {
        return java.util.regex.Pattern.compile("(?i)_*si[_ ]*term[_ ]*" + idx + "(?![0-9])_*");
    }

    /** 清洗术语目标值：多候选(; ； 、)只取第一个, 去掉括号注释, 避免内联还原把 "A (B); C" 整串插入译文。 */
    private String cleanTermValue(String term) {
        if (term == null || term.isBlank()) {
            return term;
        }
        String first = term.split("[;；、]", 2)[0];
        first = first.replaceAll("\\s*[(（][^)）]*[)）]", "").trim();
        return first.isBlank() ? term.trim() : first;
    }

    private String termByLang(Terminology terminology, String lang) {
        if (lang == null) return null;
        String lower = lang.toLowerCase();
        if (lower.startsWith("zh")) return terminology.getTermZh();
        if (lower.startsWith("id") || Constants.LANG_ID_ISO6391.equalsIgnoreCase(lower)) return terminology.getTermId();
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return terminology.getTermEn();
        return null;
    }

    private static int countResidualPlaceholders(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = RESIDUAL_PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String diagnosticHash(String value) {
        return value == null ? "null" : Integer.toHexString(value.hashCode());
    }

    private final class TerminologyIndexSnapshot {

        private final List<Terminology> enabledTerms;
        private final long loadedAtMillis;
        private final Map<String, List<TerminologyCandidate>> candidatesByLanguagePair = new ConcurrentHashMap<>();

        private TerminologyIndexSnapshot(List<Terminology> enabledTerms, long loadedAtMillis) {
            this.enabledTerms = List.copyOf(enabledTerms);
            this.loadedAtMillis = loadedAtMillis;
        }

        private boolean isExpired(long nowMillis) {
            return nowMillis - loadedAtMillis > INDEX_CACHE_TTL_MILLIS;
        }

        private List<TerminologyCandidate> candidates(String sourceLang, String targetLang) {
            return candidatesByLanguagePair.computeIfAbsent(
                    languagePairKey(sourceLang, targetLang),
                    ignored -> buildCandidates(enabledTerms, sourceLang, targetLang)
            );
        }
    }

    private record TerminologyCandidate(
            String sourceTerm,
            String targetTerm,
            Pattern sourcePattern,
            Long id
    ) {
    }

    private record TerminologyOccurrence(
            int start,
            int end,
            TerminologyCandidate candidate
    ) {
        private int length() {
            return end - start;
        }
    }

    private record TargetTermOccurrence(
            int start,
            int end,
            String placeholder
    ) {
        private int length() {
            return end - start;
        }
    }

    /**
     * Translation pre-hook result containing protected text and placeholder mappings.
     */
    public static class TerminologyProtection {

        private final String protectedText;
        private final Map<String, String> targetTermByPlaceholder;
        private final Map<String, String> sourceTermByPlaceholder;

        public TerminologyProtection(String protectedText, Map<String, String> targetTermByPlaceholder) {
            this(protectedText, targetTermByPlaceholder, Collections.emptyMap());
        }

        public TerminologyProtection(
                String protectedText,
                Map<String, String> targetTermByPlaceholder,
                Map<String, String> sourceTermByPlaceholder
        ) {
            this.protectedText = protectedText;
            this.targetTermByPlaceholder = new LinkedHashMap<>(targetTermByPlaceholder);
            this.sourceTermByPlaceholder = new LinkedHashMap<>(sourceTermByPlaceholder);
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

        public Map<String, String> getSourceTermByPlaceholder() {
            return sourceTermByPlaceholder;
        }
    }
}
