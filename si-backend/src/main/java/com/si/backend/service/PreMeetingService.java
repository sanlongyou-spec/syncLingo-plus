package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.PreMeetingChatRequest.ChatTurn;
import com.si.backend.dto.PreMeetingParticipantRequest;
import com.si.backend.entity.InterpretationResult;
import com.si.backend.entity.PreMeetingUsageRecord;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.mapper.PreMeetingUsageMapper;
import com.si.backend.vo.PreMeetingChatVo;
import com.si.backend.vo.PreMeetingAttendanceRowVo;
import com.si.backend.vo.PreMeetingAttendanceVo;
import com.si.backend.vo.PreMeetingDailyUsageVo;
import com.si.backend.vo.PreMeetingFileVo;
import com.si.backend.vo.PreMeetingSummaryVo;
import com.si.backend.vo.TeamsBotQuerySourceVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreMeetingService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAN_PATTERN = Pattern.compile("\\p{IsHan}");
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}月\\d{1,2}日");
    private static final Pattern GROUP_COUNT_PATTERN = Pattern.compile("^(.*?)[（(]\\d+[）)]\\s*[:：]\\s*(.*)$");
    private static final int MAX_ATTENDANCE_NAME_LENGTH = 60;
    private static final int ATTENDANCE_EXPORT_FONT_SIZE = 11;
    private static final int MAX_RAG_SESSIONS = 5;
    private static final int MAX_RAG_SNIPPETS_PER_SESSION = 20;
    private static final int MAX_RAG_SOURCES = 8;
    private static final int SOURCE_SNIPPET_MAX_CHARS = 220;
    private static final String ATTENDANCE_STATUS_PRESENT = "present";
    private static final String ATTENDANCE_STATUS_ABSENT = "absent";
    private static final String ATTENDANCE_STATUS_UNEXPECTED = "unexpected";

    private static final Set<String> ATTENDANCE_SECTION_KEYWORDS = Set.of(
            "参会", "参加", "出席", "列席", "与会", "Peserta", "peserta");
    private static final Set<String> ATTENDANCE_STOP_KEYWORDS = Set.of(
            "参会人数", "实际参会人数", "请假", "缺席", "事假", "Absen", "Jlh.", "会议议程", "Agenda");
    private static final Set<String> NAME_STOPWORDS = Set.of(
            "会议", "通知", "时间", "地点", "人员", "名单", "参会", "参加", "出席", "列席", "秘书",
            "主持", "记录", "团队", "部门", "单位", "职务", "姓名", "人数", "其他", "国内连线",
            "总部", "大区", "工业", "会议室", "Teams", "Meeting", "Password", "Agenda", "Notulen",
            "Peserta", "Akt", "Orang", "Absen");

    private final LlmIntegration llmIntegration;
    private final PreMeetingUsageMapper usageMapper;
    private final InterpretationResultMapper interpretationResultMapper;
    private final VectorSearchService vectorSearchService;
    private final RagEnhancementService ragEnhancementService;
    private final com.si.backend.mapper.InterpretationSessionMapper interpretationSessionMapper;
    private final com.si.backend.mapper.SpeakerSummaryRecordMapper speakerSummaryMapper;
    private final com.si.backend.mapper.PersistentPreMeetingFileMapper persistentFileMapper;

    private record PreMeetingDoc(String fileName, String ext, String text, byte[] originalBytes) {}
    private record ExpectedParticipant(String name, String department, String email, String sourceText) {}
    private record ActualParticipant(String displayName, String email, String normalizedName, String chineseName) {}
    private record AttendanceBlock(int startIndex, int endIndex) {}
    public record UnifiedContextResult(String context, List<TeamsBotQuerySourceVo> sources) {}
    private record RunShell(
            boolean bold,
            boolean italic,
            UnderlinePatterns underline,
            String color,
            int textPosition,
            String fontFamily,
            int fontSize) {}

    private final ConcurrentHashMap<String, PreMeetingDoc> store = new ConcurrentHashMap<>();

    @PostConstruct
    public void initTable() {
        log.info("[PreMeetingService] initTable start");
        usageMapper.createTableIfNotExists();
        log.info("[PreMeetingService] initTable end");
    }

    public List<PreMeetingFileVo> upload(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) originalName = "unnamed";
        String ext = extension(originalName).toLowerCase();

        log.info("[PreMeetingService] upload start, fileName={}, size={}", originalName, file.getSize());

        List<PreMeetingFileVo> result;
        if ("zip".equals(ext)) {
            result = processZip(file.getInputStream());
        } else {
            byte[] bytes = file.getBytes();
            String text = extractText(new ByteArrayInputStream(bytes), ext);
            String fileId = storeDoc(originalName, ext, text, bytes);
            PreMeetingDoc doc = store.get(fileId);
            result = List.of(PreMeetingFileVo.builder().fileId(fileId).fileName(originalName)
                    .meetingTitle(doc != null ? deriveMeetingTitle(doc) : null).build());
        }

        log.info("[PreMeetingService] upload done, fileCount={}", result.size());
        return result;
    }

    public String getDocText(String fileId) {
        PreMeetingDoc doc = store.get(fileId);
        return doc != null ? doc.text() : "";
    }

    /** Expected participant names + meeting venue parsed from an uploaded meeting agenda. */
    public record MeetingEntities(List<String> participantNames, String venue) {}

    /**
     * Extracts expected participant names and the meeting venue from a stored agenda file,
     * for feeding into the ASR hotword list.
     */
    public MeetingEntities extractMeetingEntities(String fileId) {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            return new MeetingEntities(List.of(), null);
        }
        List<String> names = parseExpectedParticipants(doc.text()).stream()
                .map(ExpectedParticipant::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        String venue = parseMeetingVenue(doc.text());
        log.info("[PreMeetingService] extractMeetingEntities, fileId={}, names={}, hasVenue={}",
                fileId, names.size(), venue != null && !venue.isBlank());
        return new MeetingEntities(names, venue);
    }

    private String parseMeetingVenue(String text) {
        if (text == null) return null;
        for (String rawLine : text.split("\\R")) {
            String line = normalizeLine(rawLine);
            if (line.contains("会议地点") || line.contains("会议室")
                    || line.contains("会议场地") || line.startsWith("地点")) {
                String venue = substringAfterColon(line);
                if (venue == null || venue.isBlank()) {
                    continue;
                }
                return venue.length() > 40 ? venue.substring(0, 40).trim() : venue.trim();
            }
        }
        return null;
    }

    public String extractFileText(byte[] bytes, String ext, String fileName) throws IOException {
        String normalizedExt = ext.toLowerCase();
        if ("zip".equals(normalizedExt)) {
            List<PreMeetingFileVo> parts = processZip(new java.io.ByteArrayInputStream(bytes));
            if (parts.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (PreMeetingFileVo part : parts) {
                PreMeetingDoc doc = store.get(part.getFileId());
                if (doc != null) sb.append(doc.text()).append("\n\n");
            }
            return sb.toString().trim();
        }
        return extractText(new java.io.ByteArrayInputStream(bytes), normalizedExt);
    }

    public PreMeetingSummaryVo summarize(String fileId, String requirements, long userId) throws IOException {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期，请重新上传");
        }
        log.info("[PreMeetingService] summarize start, fileId={}, textLen={}", fileId, doc.text().length());
        String summary = llmIntegration.summarizeDocument(doc.text(), requirements);
        log.info("[PreMeetingService] summarize done, fileId={}, summaryLen={}", fileId, summary.length());

        long inputTokens  = Math.max(1L, Math.round(doc.text().length() / 2.0));
        long outputTokens = Math.max(1L, Math.round(summary.length() / 2.0));
        try {
            usageMapper.insert(PreMeetingUsageRecord.builder()
                    .userId(userId)
                    .fileName(doc.fileName())
                    .llmInputTokens(inputTokens)
                    .llmOutputTokens(outputTokens)
                    .build());
        } catch (Exception e) {
            log.warn("[PreMeetingService] failed to record usage, fileId={}", fileId, e);
        }

        return PreMeetingSummaryVo.builder()
                .fileId(fileId)
                .fileName(doc.fileName())
                .summary(summary)
                .extractedText(doc.text())
                .build();
    }

    public PreMeetingAttendanceVo generateAttendance(
            String fileId,
            List<PreMeetingParticipantRequest> actualParticipants) {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期，请重新上传");
        }

        log.info("[PreMeetingService] generateAttendance start, fileId={}, textLen={}, actualCount={}",
                fileId, doc.text().length(), actualParticipants == null ? 0 : actualParticipants.size());

        List<ExpectedParticipant> expectedParticipants = parseExpectedParticipants(doc.text());
        if (expectedParticipants.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未能从会议安排中识别参会人员，请检查文件中的参会人员格式");
        }

        List<ActualParticipant> actualList = normalizeActualParticipants(actualParticipants);
        Set<Integer> matchedActualIndexes = new HashSet<>();
        List<PreMeetingAttendanceRowVo> rows = new ArrayList<>();
        int presentCount = 0;

        for (ExpectedParticipant expected : expectedParticipants) {
            int actualIndex = findActualMatch(expected, actualList, matchedActualIndexes);
            if (actualIndex >= 0) {
                ActualParticipant actual = actualList.get(actualIndex);
                matchedActualIndexes.add(actualIndex);
                presentCount++;
                rows.add(PreMeetingAttendanceRowVo.builder()
                        .name(expected.name())
                        .department(expected.department())
                        .email(expected.email())
                        .actualName(actual.displayName())
                        .actualEmail(actual.email())
                        .status(ATTENDANCE_STATUS_PRESENT)
                        .sourceText(expected.sourceText())
                        .build());
            } else {
                rows.add(PreMeetingAttendanceRowVo.builder()
                        .name(expected.name())
                        .department(expected.department())
                        .email(expected.email())
                        .status(ATTENDANCE_STATUS_ABSENT)
                        .sourceText(expected.sourceText())
                        .build());
            }
        }

        for (int i = 0; i < actualList.size(); i++) {
            if (matchedActualIndexes.contains(i)) {
                continue;
            }
            ActualParticipant actual = actualList.get(i);
            rows.add(PreMeetingAttendanceRowVo.builder()
                    .name(actual.displayName())
                    .email(actual.email())
                    .actualName(actual.displayName())
                    .actualEmail(actual.email())
                    .status(ATTENDANCE_STATUS_UNEXPECTED)
                    .build());
        }

        int absentCount = expectedParticipants.size() - presentCount;
        int unexpectedCount = actualList.size() - matchedActualIndexes.size();
        log.info("[PreMeetingService] generateAttendance done, fileId={}, expectedCount={}, presentCount={}, absentCount={}, unexpectedCount={}",
                fileId, expectedParticipants.size(), presentCount, absentCount, unexpectedCount);

        return PreMeetingAttendanceVo.builder()
                .fileId(fileId)
                .fileName(doc.fileName())
                .meetingTitle(deriveMeetingTitle(doc))
                .expectedCount(expectedParticipants.size())
                .actualCount(actualList.size())
                .presentCount(presentCount)
                .absentCount(absentCount)
                .unexpectedCount(unexpectedCount)
                .rows(rows)
                .build();
    }

    public byte[] buildAttendanceExportDocx(
            String fileId,
            List<PreMeetingParticipantRequest> actualParticipants) throws IOException {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期，请重新上传");
        }

        log.info("[PreMeetingService] buildAttendanceExportDocx start, fileId={}", fileId);
        PreMeetingAttendanceVo attendance = generateAttendance(fileId, actualParticipants);
        List<String> attendanceLines = buildAttendanceSectionLines(attendance);

        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XWPFDocument.class.getClassLoader());

            XWPFDocument output;
            if ("docx".equals(doc.ext())) {
                output = new XWPFDocument(new ByteArrayInputStream(doc.originalBytes()));
                replaceAttendanceBlock(output, attendanceLines);
            } else {
                output = buildNewAttendanceDocument(doc, attendanceLines);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.write(out);
            output.close();
            log.info("[PreMeetingService] buildAttendanceExportDocx done, fileId={}, lineCount={}",
                    fileId, attendanceLines.size());
            return out.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    public List<PreMeetingDailyUsageVo> getDailyUsage(long userId, int days) {
        String since = LocalDate.now().minusDays(days - 1).toString();
        return usageMapper.findDailyUsage(userId, since);
    }

    private List<PreMeetingFileVo> processZip(InputStream in) throws IOException {
        List<PreMeetingFileVo> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String name = entry.getName();
                String baseName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                String ext = extension(baseName).toLowerCase();
                if (!Set.of("doc", "docx", "pdf").contains(ext)) { zis.closeEntry(); continue; }

                byte[] bytes = zis.readAllBytes();
                try {
                    String text = extractText(new ByteArrayInputStream(bytes), ext);
                    String fileId = storeDoc(baseName, ext, text, bytes);
                    PreMeetingDoc stored = store.get(fileId);
                    result.add(PreMeetingFileVo.builder().fileId(fileId).fileName(baseName)
                            .meetingTitle(stored != null ? deriveMeetingTitle(stored) : null).build());
                    log.info("[PreMeetingService] zip entry extracted, name={}, textLen={}", baseName, text.length());
                } catch (Exception e) {
                    log.warn("[PreMeetingService] zip entry skipped, name={}, reason={}", baseName, e.getMessage());
                }
                zis.closeEntry();
            }
        }
        return result;
    }

    private List<String> buildAttendanceSectionLines(PreMeetingAttendanceVo attendance) {
        List<String> lines = new ArrayList<>();
        Map<String, List<String>> presentByDepartment = new LinkedHashMap<>();
        List<String> absentNames = new ArrayList<>();
        List<String> unexpectedNames = new ArrayList<>();

        for (PreMeetingAttendanceRowVo row : attendance.getRows()) {
            String status = safeString(row.getStatus());
            if (ATTENDANCE_STATUS_PRESENT.equals(status)) {
                String department = safeString(row.getDepartment()).isBlank() ? "Lainnya 其他连线" : row.getDepartment();
                presentByDepartment.computeIfAbsent(department, ignored -> new ArrayList<>()).add(safeString(row.getName()));
            } else if (ATTENDANCE_STATUS_ABSENT.equals(status)) {
                absentNames.add(safeString(row.getName()));
            } else if (ATTENDANCE_STATUS_UNEXPECTED.equals(status)) {
                unexpectedNames.add(!safeString(row.getActualName()).isBlank() ? row.getActualName() : safeString(row.getName()));
            }
        }

        lines.add("Peserta Akt. 实际应参会人员\t：");
        for (Map.Entry<String, List<String>> entry : presentByDepartment.entrySet()) {
            List<String> names = entry.getValue();
            lines.add(entry.getKey() + "（" + names.size() + "）\t：" + String.join("、", names));
        }
        lines.add("Jlh. Akt. 实际参会人数\t：" + attendance.getActualCount() + " Orang人");
        lines.add("Absen 事假\t：" + (absentNames.isEmpty() ? "无" : String.join("、", absentNames)));
        lines.add("Jlh. Absen 请假人数\t：" + attendance.getAbsentCount() + " Orang 人");
        if (!unexpectedNames.isEmpty()) {
            lines.add("Tambahan 未在安排中（" + unexpectedNames.size() + "）\t：" + String.join("、", unexpectedNames));
        }
        return lines;
    }

    private XWPFDocument buildNewAttendanceDocument(
            PreMeetingDoc doc,
            List<String> attendanceLines) {
        XWPFDocument output = new XWPFDocument();
        List<String> lines = new ArrayList<>(extractScheduleHeaderLines(doc));
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.addAll(attendanceLines);
        for (String line : lines) {
            XWPFParagraph paragraph = output.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(line);
            run.setFontSize(ATTENDANCE_EXPORT_FONT_SIZE);
            if (line.startsWith("【") || line.contains("实际应参会人员") || line.startsWith("Absen")) {
                run.setBold(true);
            }
        }
        return output;
    }

    private void replaceAttendanceBlock(
            XWPFDocument document,
            List<String> attendanceLines) {
        AttendanceBlock block = findAttendanceBlock(document.getParagraphs());
        if (block == null) {
            appendAttendanceSection(document, attendanceLines);
            return;
        }

        List<XWPFParagraph> paragraphs = document.getParagraphs();
        XWPFParagraph template = paragraphs.get(block.startIndex());
        replaceParagraphLines(template, attendanceLines, template);

        for (int index = block.endIndex(); index > block.startIndex(); index--) {
            int bodyPosition = document.getPosOfParagraph(paragraphs.get(index));
            if (bodyPosition >= 0) {
                document.removeBodyElement(bodyPosition);
            }
        }
    }

    private AttendanceBlock findAttendanceBlock(List<XWPFParagraph> paragraphs) {
        int startIndex = -1;
        for (int index = 0; index < paragraphs.size(); index++) {
            String line = normalizeLine(paragraphs.get(index).getText());
            if (isAttendanceBlockStart(line)) {
                startIndex = index;
                break;
            }
        }
        if (startIndex < 0) {
            return null;
        }

        int endIndex = startIndex;
        for (int index = startIndex + 1; index < paragraphs.size(); index++) {
            String line = normalizeLine(paragraphs.get(index).getText());
            if (line.isBlank()) {
                endIndex = index;
                continue;
            }
            if (isAttendanceBlockTerminalLine(line)) {
                return new AttendanceBlock(startIndex, index);
            }
            if (isPostAttendanceSectionStart(line)) {
                return new AttendanceBlock(startIndex, Math.max(startIndex, index - 1));
            }
            endIndex = index;
        }
        return new AttendanceBlock(startIndex, endIndex);
    }

    private void appendAttendanceSection(
            XWPFDocument document,
            List<String> attendanceLines) {
        XWPFParagraph separator = document.createParagraph();
        separator.createRun().setText("");
        for (String line : attendanceLines) {
            XWPFParagraph paragraph = document.createParagraph();
            replaceParagraphText(paragraph, line, null);
        }
    }

    private void replaceParagraphText(
            XWPFParagraph paragraph,
            String text,
            XWPFParagraph styleTemplate) {
        RunShell runShell = captureRunShell(firstRun(styleTemplate));
        for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) {
            paragraph.removeRun(index);
        }
        XWPFRun run = paragraph.createRun();
        applyRunShell(runShell, run);
        run.setText(text);
    }

    private void replaceParagraphLines(
            XWPFParagraph paragraph,
            List<String> lines,
            XWPFParagraph styleTemplate) {
        RunShell runShell = captureRunShell(firstRun(styleTemplate));
        for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) {
            paragraph.removeRun(index);
        }
        XWPFRun run = paragraph.createRun();
        applyRunShell(runShell, run);
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                run.addBreak();
            }
            run.setText(lines.get(index));
        }
    }

    private XWPFRun firstRun(XWPFParagraph paragraph) {
        if (paragraph == null || paragraph.getRuns().isEmpty()) {
            return null;
        }
        return paragraph.getRuns().get(0);
    }

    private RunShell captureRunShell(XWPFRun source) {
        if (source == null) {
            return null;
        }
        return new RunShell(
                source.isBold(),
                source.isItalic(),
                source.getUnderline(),
                source.getColor(),
                source.getTextPosition(),
                source.getFontFamily(),
                source.getFontSize());
    }

    private void applyRunShell(
            RunShell source,
            XWPFRun target) {
        if (source == null) {
            target.setFontSize(ATTENDANCE_EXPORT_FONT_SIZE);
            return;
        }
        target.setBold(source.bold());
        target.setItalic(source.italic());
        target.setUnderline(source.underline());
        if (source.color() != null) {
            target.setColor(source.color());
        }
        target.setTextPosition(source.textPosition());
        String fontFamily = source.fontFamily();
        if (fontFamily != null) {
            target.setFontFamily(fontFamily);
        }
        int fontSize = source.fontSize();
        if (fontSize > 0) {
            target.setFontSize(fontSize);
        }
    }

    private List<String> extractScheduleHeaderLines(PreMeetingDoc doc) {
        List<String> lines = new ArrayList<>();
        for (String rawLine : doc.text().split("\\R")) {
            String line = normalizeLine(rawLine);
            if (line.isBlank()) {
                continue;
            }
            if (isAttendanceHeader(line) || GROUP_COUNT_PATTERN.matcher(line).matches()) {
                break;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            lines.add("【" + deriveMeetingTitle(doc) + "】");
        }
        return lines;
    }

    private List<ExpectedParticipant> parseExpectedParticipants(String text) {
        Map<String, ExpectedParticipant> participants = new LinkedHashMap<>();
        boolean inAttendanceSection = false;
        String currentDepartment = "";

        for (String rawLine : text.split("\\R")) {
            String line = normalizeLine(rawLine);
            if (line.isBlank()) {
                continue;
            }

            if (isAttendanceHeader(line)) {
                inAttendanceSection = true;
                String namePart = substringAfterColon(line);
                addExpectedNames(participants, namePart, currentDepartment, line);
                continue;
            }

            if (inAttendanceSection && isAttendanceStopLine(line)) {
                break;
            }

            Matcher groupMatcher = GROUP_COUNT_PATTERN.matcher(line);
            if (groupMatcher.matches()) {
                inAttendanceSection = true;
                currentDepartment = cleanDepartment(groupMatcher.group(1));
                addExpectedNames(participants, groupMatcher.group(2), currentDepartment, line);
                continue;
            }

            if (inAttendanceSection) {
                addExpectedNames(participants, line, currentDepartment, line);
            }
        }

        return new ArrayList<>(participants.values());
    }

    private void addExpectedNames(
            Map<String, ExpectedParticipant> participants,
            String namePart,
            String department,
            String sourceText) {
        if (namePart == null || namePart.isBlank()) {
            return;
        }
        for (String piece : namePart.split("[、,，;；]+")) {
            String candidate = cleanNameCandidate(piece);
            if (!isPlausibleName(candidate)) {
                continue;
            }
            String key = normalizeIdentity(candidate);
            if (key.isBlank() || participants.containsKey(key)) {
                continue;
            }
            participants.put(key, new ExpectedParticipant(
                    candidate,
                    department,
                    extractEmail(piece),
                    sourceText));
        }
    }

    private List<ActualParticipant> normalizeActualParticipants(List<PreMeetingParticipantRequest> actualParticipants) {
        if (actualParticipants == null || actualParticipants.isEmpty()) {
            return Collections.emptyList();
        }
        List<ActualParticipant> actualList = new ArrayList<>();
        for (PreMeetingParticipantRequest participant : actualParticipants) {
            if (participant == null) {
                continue;
            }
            String displayName = safeString(participant.getDisplayName());
            String email = safeString(participant.getEmail());
            if (displayName.isBlank() && !email.isBlank()) {
                displayName = email;
            }
            if (displayName.isBlank()) {
                continue;
            }
            actualList.add(new ActualParticipant(
                    displayName,
                    email,
                    normalizeIdentity(displayName),
                    chineseOnly(displayName)));
        }
        return actualList;
    }

    private int findActualMatch(
            ExpectedParticipant expected,
            List<ActualParticipant> actualList,
            Set<Integer> matchedActualIndexes) {
        for (int i = 0; i < actualList.size(); i++) {
            if (matchedActualIndexes.contains(i)) {
                continue;
            }
            ActualParticipant actual = actualList.get(i);
            if (!expected.email().isBlank()
                    && !actual.email().isBlank()
                    && expected.email().equalsIgnoreCase(actual.email())) {
                return i;
            }
            if (isNameMatch(expected.name(), actual)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isNameMatch(String expectedName, ActualParticipant actual) {
        String expectedNormalized = normalizeIdentity(expectedName);
        String actualNormalized = actual.normalizedName();
        if (expectedNormalized.length() >= 2
                && actualNormalized.length() >= 2
                && (expectedNormalized.contains(actualNormalized) || actualNormalized.contains(expectedNormalized))) {
            return true;
        }

        String expectedChinese = chineseOnly(expectedName);
        if (expectedChinese.length() >= 2
                && actual.chineseName().length() >= 2
                && (expectedChinese.contains(actual.chineseName()) || actual.chineseName().contains(expectedChinese))) {
            return true;
        }

        String expectedLatin = latinOnly(expectedName);
        String actualLatin = latinOnly(actual.displayName());
        return expectedLatin.length() >= 2
                && actualLatin.length() >= 2
                && (expectedLatin.contains(actualLatin) || actualLatin.contains(expectedLatin));
    }

    private boolean isAttendanceHeader(String line) {
        if (isAttendanceStopLine(line)) {
            return false;
        }
        boolean hasKeyword = ATTENDANCE_SECTION_KEYWORDS.stream().anyMatch(line::contains);
        return hasKeyword && (line.contains("人员") || line.contains("名单") || line.contains("Peserta"));
    }

    private boolean isAttendanceBlockStart(String line) {
        return isAttendanceHeader(line)
                || line.contains("应参会人员")
                || line.contains("实际应参会人员")
                || GROUP_COUNT_PATTERN.matcher(line).matches();
    }

    private boolean isAttendanceBlockTerminalLine(String line) {
        return line.contains("请假人数")
                || line.contains("Jlh. Absen");
    }

    private boolean isPostAttendanceSectionStart(String line) {
        return line.contains("会议议程")
                || line.contains("Agenda")
                || line.contains("议题")
                || line.contains("事项")
                || line.contains("Pembahasan");
    }

    private boolean isAttendanceStopLine(String line) {
        return ATTENDANCE_STOP_KEYWORDS.stream().anyMatch(line::contains);
    }

    private String normalizeLine(String line) {
        return safeString(line)
                .replace('\u00A0', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String substringAfterColon(String line) {
        int cn = line.indexOf('：');
        int en = line.indexOf(':');
        int index = cn >= 0 ? cn : en;
        return index >= 0 && index + 1 < line.length() ? line.substring(index + 1).trim() : "";
    }

    private String cleanDepartment(String value) {
        return safeString(value)
                .replaceAll("[：:]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanNameCandidate(String value) {
        String candidate = safeString(value)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        candidate = EMAIL_PATTERN.matcher(candidate).replaceAll("").trim();
        int cn = candidate.lastIndexOf('：');
        int en = candidate.lastIndexOf(':');
        int colon = Math.max(cn, en);
        if (colon >= 0 && colon + 1 < candidate.length()) {
            candidate = candidate.substring(colon + 1).trim();
        }
        return candidate
                .replaceAll("^[\\d\\s.、,，;；\\-]+", "")
                .replaceAll("[\\s.、,，;；\\-]+$", "")
                .replaceAll("^[【\\[\\(（]+|[】\\]\\)）]+$", "")
                .trim();
    }

    private boolean isPlausibleName(String candidate) {
        if (candidate == null
                || candidate.length() < 2
                || candidate.length() > MAX_ATTENDANCE_NAME_LENGTH
                || candidate.matches(".*\\d.*")) {
            return false;
        }
        if (NAME_STOPWORDS.stream().anyMatch(word -> word.equalsIgnoreCase(candidate) || candidate.contains(word))) {
            return false;
        }
        return containsHan(candidate) || candidate.matches(".*[A-Za-z].*");
    }

    private String extractEmail(String value) {
        Matcher matcher = EMAIL_PATTERN.matcher(safeString(value));
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : "";
    }

    private String deriveMeetingTitle(PreMeetingDoc doc) {
        String title = null;
        String date = null;
        for (String rawLine : doc.text().split("\\R")) {
            String line = normalizeLine(rawLine);
            if (title == null
                    && line.length() >= 4
                    && line.length() <= 80
                    && containsHan(line)
                    && !line.contains("会议通知")
                    && !line.contains("会议时间")
                    && !line.contains("会议地点")) {
                title = line.replaceAll("^[【\\[]|[】\\]]$", "");
            }
            if (date == null) {
                Matcher m = DATE_PATTERN.matcher(line);
                if (m.find()) date = m.group();
            }
            if (title != null && date != null) break;
        }
        if (title == null) title = stripExtension(doc.fileName());
        if (date != null && !title.contains(date)) return title + "（" + date + "）";
        return title;
    }

    private boolean containsHan(String value) {
        return HAN_PATTERN.matcher(safeString(value)).find();
    }

    private String normalizeIdentity(String value) {
        String normalized = Normalizer.normalize(safeString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    private String chineseOnly(String value) {
        return safeString(value).replaceAll("[^\\p{IsHan}]", "");
    }

    private String latinOnly(String value) {
        return Normalizer.normalize(safeString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    private String extractText(InputStream in, String ext) throws IOException {
        return switch (ext) {
            case "docx" -> extractDocx(in);
            case "doc"  -> extractDoc(in);
            case "pdf"  -> extractPdf(in);
            default -> throw new IOException("不支持的文件格式: " + ext);
        };
    }

    private String extractDocx(InputStream in) throws IOException {
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XWPFDocument.class.getClassLoader());
            try (XWPFDocument doc = new XWPFDocument(in)) {
                StringBuilder sb = new StringBuilder();
                for (XWPFParagraph para : doc.getParagraphs()) {
                    String text = para.getText();
                    if (text != null && !text.isBlank()) sb.append(text).append('\n');
                }
                for (XWPFTable table : doc.getTables()) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String text = cell.getText();
                            if (text != null && !text.isBlank()) {
                                cells.add(text.replaceAll("\\s+", " ").trim());
                            }
                        }
                        if (!cells.isEmpty()) {
                            sb.append(String.join("\t", cells)).append('\n');
                        }
                    }
                }
                return sb.toString().trim();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    private String extractDoc(InputStream in) throws IOException {
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(HWPFDocument.class.getClassLoader());
            try (HWPFDocument doc = new HWPFDocument(in)) {
                return doc.getDocumentText().trim();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    private String extractPdf(InputStream in) throws IOException {
        try (PDDocument doc = PDDocument.load(in)) {
            return new PDFTextStripper().getText(doc).trim();
        }
    }

    public byte[] buildExportDocx(String fileId, String summary) throws IOException {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期，请重新上传");
        }

        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XWPFDocument.class.getClassLoader());

            XWPFDocument output;
            if ("docx".equals(doc.ext())) {
                output = new XWPFDocument(new ByteArrayInputStream(doc.originalBytes()));
            } else {
                // For .doc and .pdf: create a new docx with extracted text
                output = new XWPFDocument();
                for (String line : doc.text().split("\n")) {
                    XWPFParagraph p = output.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText(line);
                }
            }

            // Separator paragraph
            XWPFParagraph sep = output.createParagraph();
            XWPFRun sepRun = sep.createRun();
            sepRun.setText("─────────────────────────────────────────");
            sepRun.setColor("94A3B8");

            // AI summary heading
            XWPFParagraph heading = output.createParagraph();
            XWPFRun headingRun = heading.createRun();
            headingRun.setText("AI 会议摘要");
            headingRun.setBold(true);
            headingRun.setFontSize(14);
            headingRun.setColor("4338CA");

            // Summary body — split by line to preserve paragraph breaks
            for (String line : summary.split("\n")) {
                XWPFParagraph p = output.createParagraph();
                XWPFRun r = p.createRun();
                r.setText(line);
                r.setFontSize(11);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.write(out);
            output.close();
            return out.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    public PreMeetingChatVo chat(
            String fileId,
            String sessionId,
            String question,
            List<ChatTurn> history) throws IOException {
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();

        if (fileId != null && !fileId.isBlank()) {
            PreMeetingDoc doc = store.get(fileId);
            if (doc != null) {
                context.append("[会议文件：").append(doc.fileName()).append("]\n").append(doc.text());
                sources.add(doc.fileName());
            }
        }

        if (sessionId != null && !sessionId.isBlank()) {
            List<InterpretationResult> results = interpretationResultMapper.findBySessionId(sessionId);
            if (!results.isEmpty()) {
                if (context.length() > 0) context.append("\n\n");
                context.append("[同传记录]\n");
                for (InterpretationResult r : results) {
                    context.append(r.getSourceText()).append(" → ").append(r.getTranslatedText()).append("\n");
                }
                sources.add("同传记录(" + sessionId + ")");
            }
        }

        log.info("[PreMeetingService] chat, fileId={}, sessionId={}, contextLen={}, historySize={}",
                fileId, sessionId, context.length(), history != null ? history.size() : 0);
        String answer = llmIntegration.chat(context.toString(), question, history);
        String contextSummary = sources.isEmpty() ? "无参考资料" : "基于：" + String.join("、", sources);
        return PreMeetingChatVo.builder().answer(answer).contextSummary(contextSummary).build();
    }

    public PreMeetingChatVo chatCrossMeeting(
            long userId,
            String question,
            List<ChatTurn> history,
            int days) throws IOException {

        String since = days > 0 ? LocalDate.now().minusDays(days).toString() : null;
        log.info("[PreMeetingService] chatCrossMeeting vector, userId={}, days={}", userId, days);

        float[] queryVec = llmIntegration.embed(question);
        List<VectorSearchService.SearchResult> hits =
                vectorSearchService.search(userId, queryVec, null, null, since, 40);

        if (hits.isEmpty()) {
            String range = days > 0 ? "过去 " + days + " 天的" : "所有";
            return PreMeetingChatVo.builder()
                    .answer("在" + range + "会议记录中，未找到与该问题相关的内容。")
                    .contextSummary("无匹配记录")
                    .referencedSessions(List.of())
                    .build();
        }

        Map<String, List<VectorSearchService.SearchResult>> bySession = new LinkedHashMap<>();
        for (VectorSearchService.SearchResult hit : hits) {
            bySession.computeIfAbsent(sourceGroupKey(hit), k -> new ArrayList<>()).add(hit);
        }

        StringBuilder context = new StringBuilder();
        List<String> sessionLabels = new ArrayList<>();
        int sessionCount = 0;

        for (Map.Entry<String, List<VectorSearchService.SearchResult>> entry : bySession.entrySet()) {
            if (sessionCount++ >= 5) break;
            List<VectorSearchService.SearchResult> snippets = entry.getValue();
            VectorSearchService.SearchResult first = snippets.get(0);

            String title = sourceGroupTitle(first, entry.getKey());
            String date = first.sessionDate() != null ? first.sessionDate() : "日期未知";
            String label = title + " (" + date + ")";
            sessionLabels.add(label);

            // C4: 带来源标记
            context.append("[来源：").append(title).append("·").append(date).append("]\n");
            int count = 0;
            for (VectorSearchService.SearchResult snippet : snippets) {
                if (count++ >= 20) break;
                if (snippet.speakerName() != null && !snippet.speakerName().isBlank()) {
                    context.append(snippet.speakerName()).append(": ");
                }
                context.append(snippet.sourceText())
                        .append(" → ").append(snippet.translatedText()).append("\n");
            }
            // C1/C2/C3: 叠加结构化内容
            appendMeetingSummary(context, entry.getKey());
            appendSpeakerSummaries(context, entry.getKey());
            appendFileSummaries(context, entry.getKey());
            context.append("\n");
        }

        String answer = llmIntegration.chatCrossMeeting(context.toString(), question, history);
        int refCount = Math.min(3, sessionLabels.size());
        String contextSummary = "语义检索了 " + bySession.size() + " 场会议，引用：" +
                String.join("、", sessionLabels.subList(0, refCount));

        return PreMeetingChatVo.builder()
                .answer(answer)
                .contextSummary(contextSummary)
                .referencedSessions(sessionLabels)
                .build();
    }

    /** Filter dimensions extracted from a natural-language question (B2/B3). */
    public record QuestionFilter(Long meetingId, String speakerName, String since) {
        public static final QuestionFilter EMPTY = new QuestionFilter(null, null, null);
    }

    public String buildUnifiedContext(long userId, String question) {
        return buildUnifiedContext(userId, question, QuestionFilter.EMPTY);
    }

    public String buildUnifiedContext(long userId, String question, QuestionFilter filter) {
        return buildUnifiedContextResult(userId, question, filter).context();
    }

    public UnifiedContextResult buildUnifiedContextResult(long userId, String question, QuestionFilter filter) {
        log.info("[PreMeetingService] buildUnifiedContext vector, userId={}", userId);
        try {
            // P0: multi-query expansion → merged recall → LLM rerank (all no-ops when flags off).
            List<String> queries = ragEnhancementService.expandQueries(question);
            List<VectorSearchService.SearchResult> hits = multiQueryRecall(userId, queries, filter, 40);
            if (hits.isEmpty()) {
                log.info("[PreMeetingService] buildUnifiedContext done, userId={}, sessions=0, sources=0", userId);
                return new UnifiedContextResult("", List.of());
            }
            hits = ragEnhancementService.rerank(question, hits);

            Map<String, List<VectorSearchService.SearchResult>> bySession = new LinkedHashMap<>();
            for (VectorSearchService.SearchResult hit : hits) {
                bySession.computeIfAbsent(sourceGroupKey(hit), k -> new ArrayList<>()).add(hit);
            }

            StringBuilder context = new StringBuilder();
            List<TeamsBotQuerySourceVo> sources = new ArrayList<>();
            Set<String> sourceKeys = new HashSet<>();
            int sessionCount = 0;
            for (Map.Entry<String, List<VectorSearchService.SearchResult>> entry : bySession.entrySet()) {
                if (sessionCount++ >= MAX_RAG_SESSIONS) break;
                List<VectorSearchService.SearchResult> snippets = entry.getValue();
                VectorSearchService.SearchResult first = snippets.get(0);
                String title = sourceGroupTitle(first, entry.getKey());
                String date = first.sessionDate() != null ? first.sessionDate() : "日期未知";

                // C4: 带来源标记的区块头
                context.append("[来源：").append(title).append("·").append(date).append("]\n");

                // 同传片段
                int count = 0;
                for (VectorSearchService.SearchResult snippet : snippets) {
                    if (count++ >= MAX_RAG_SNIPPETS_PER_SESSION) break;
                    if (snippet.speakerName() != null && !snippet.speakerName().isBlank()) {
                        context.append(snippet.speakerName()).append(": ");
                    }
                    context.append(snippet.sourceText())
                            .append(" → ").append(snippet.translatedText()).append("\n");
                    addSourceFromHit(sources, sourceKeys, snippet, title, date);
                }

                // C1: 会议总结
                appendMeetingSummary(context, entry.getKey());

                // C2: 发言摘要
                appendSpeakerSummaries(context, entry.getKey());

                // C3: 会前文件摘要
                appendFileSummaries(context, entry.getKey());

                context.append("\n");
            }
            log.info("[PreMeetingService] buildUnifiedContext done, userId={}, sessions={}",
                    userId, bySession.size());
            return new UnifiedContextResult(context.toString(), sources);
        } catch (Exception e) {
            log.warn("[PreMeetingService] buildUnifiedContext embed failed: {}", e.getMessage());
            return new UnifiedContextResult("", List.of());
        }
    }

    /** Embeds each (expanded) query, runs vector search, and merges hits keeping the best score per chunk. */
    private List<VectorSearchService.SearchResult> multiQueryRecall(
            long userId, List<String> queries, QuestionFilter filter, int perQueryTopK) {
        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        for (String q : queries) {
            float[] vec;
            try {
                vec = llmIntegration.embed(q);
            } catch (Exception e) {
                log.warn("[PreMeetingService] multiQueryRecall embed failed for a query: {}", e.getMessage());
                continue;
            }
            List<VectorSearchService.SearchResult> hits = vectorSearchService.search(
                    userId, vec, filter.meetingId(), filter.speakerName(), filter.since(), perQueryTopK);
            for (VectorSearchService.SearchResult h : hits) {
                String key = h.sourceType() + "|" + h.sourceId() + "|" + h.refId() + "|"
                        + (h.sourceText() == null ? "" : h.sourceText());
                VectorSearchService.SearchResult existing = merged.get(key);
                if (existing == null || h.score() > existing.score()) {
                    merged.put(key, h);
                }
            }
        }
        List<VectorSearchService.SearchResult> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingDouble((VectorSearchService.SearchResult r) -> (double) r.score()).reversed());
        return out;
    }

    private String sourceGroupKey(VectorSearchService.SearchResult hit) {
        if (hit.sessionId() != null && !hit.sessionId().isBlank()) {
            return hit.sessionId();
        }
        if (hit.meetingId() != null) {
            return "meeting:" + hit.meetingId();
        }
        return "source:" + hit.sourceType() + ":" + hit.refId();
    }

    private String sourceGroupTitle(VectorSearchService.SearchResult hit, String groupKey) {
        if (hit.sessionTitle() != null && !hit.sessionTitle().isBlank()) {
            return hit.sessionTitle();
        }
        if (hit.meetingId() != null) {
            return "会议 " + hit.meetingId();
        }
        return groupKey.length() > 8 ? groupKey.substring(0, 8) : groupKey;
    }

    private void addSourceFromHit(
            List<TeamsBotQuerySourceVo> sources,
            Set<String> sourceKeys,
            VectorSearchService.SearchResult hit,
            String meetingTitle,
            String sourceDate) {
        if (sources.size() >= MAX_RAG_SOURCES) return;
        String key = hit.sourceType() + ":" + hit.sourceId() + ":" + hit.refId();
        if (!sourceKeys.add(key)) return;

        Long fileId = isFileSource(hit.sourceType()) ? hit.refId() : null;
        sources.add(TeamsBotQuerySourceVo.builder()
                .sourceType(hit.sourceType())
                .title(sourceTitle(hit, meetingTitle))
                .meetingTitle(meetingTitle)
                .sessionId(hit.sessionId())
                .meetingId(hit.meetingId())
                .fileId(fileId)
                .sourceName(sourceName(hit, meetingTitle))
                .sourceDate(sourceDate)
                .snippet(snippetOf(hit))
                .score(hit.score())
                .build());
    }

    private String sourceTitle(VectorSearchService.SearchResult hit, String meetingTitle) {
        String type = hit.sourceType();
        if (ContentEmbeddingService.TYPE_FILE_CONTENT.equals(type)) return "文件内容";
        if (ContentEmbeddingService.TYPE_FILE_SUMMARY.equals(type)) return "文件总结";
        if (ContentEmbeddingService.TYPE_MEETING_SUMMARY.equals(type)) return "会议总结";
        if (ContentEmbeddingService.TYPE_SPEAKER_SUMMARY.equals(type)) return "发言摘要";
        if (ContentEmbeddingService.TYPE_ACTION_ITEM.equals(type)) return "行动项";
        return meetingTitle;
    }

    private String sourceName(VectorSearchService.SearchResult hit, String meetingTitle) {
        if (isFileSource(hit.sourceType()) && hit.refId() != null) {
            try {
                var file = persistentFileMapper.findById(hit.refId());
                if (file != null && file.getFileName() != null && !file.getFileName().isBlank()) {
                    return file.getFileName();
                }
            } catch (Exception e) {
                log.debug("[PreMeetingService] sourceName file lookup failed, refId={}: {}", hit.refId(), e.getMessage());
            }
        }
        if (hit.speakerName() != null && !hit.speakerName().isBlank()) {
            return hit.speakerName();
        }
        return meetingTitle;
    }

    private boolean isFileSource(String sourceType) {
        return ContentEmbeddingService.TYPE_FILE_CONTENT.equals(sourceType)
                || ContentEmbeddingService.TYPE_FILE_SUMMARY.equals(sourceType);
    }

    private String snippetOf(VectorSearchService.SearchResult hit) {
        StringBuilder text = new StringBuilder();
        if (hit.sourceText() != null && !hit.sourceText().isBlank()) {
            text.append(hit.sourceText().trim());
        }
        if (hit.translatedText() != null && !hit.translatedText().isBlank()) {
            if (!text.isEmpty()) text.append(" → ");
            text.append(hit.translatedText().trim());
        }
        String value = text.toString().replaceAll("\\s+", " ").trim();
        if (value.length() <= SOURCE_SNIPPET_MAX_CHARS) return value;
        return value.substring(0, SOURCE_SNIPPET_MAX_CHARS) + "…";
    }

    private void appendMeetingSummary(StringBuilder context, String sessionId) {
        try {
            var session = interpretationSessionMapper.findBySessionId(sessionId);
            if (session != null && session.getMeetingSummary() != null
                    && !session.getMeetingSummary().isBlank()) {
                String summary = session.getMeetingSummary();
                if (summary.length() > 800) summary = summary.substring(0, 800) + "…";
                context.append("[会议总结]\n").append(summary).append("\n");
            }
        } catch (Exception e) {
            log.debug("[PreMeetingService] appendMeetingSummary failed: {}", e.getMessage());
        }
    }

    private void appendSpeakerSummaries(StringBuilder context, String sessionId) {
        try {
            var summaries = speakerSummaryMapper.findBySessionId(sessionId);
            for (var s : summaries) {
                if (s.getSummary() == null || s.getSummary().isBlank()) continue;
                String label = s.getSpeakerName() != null ? s.getSpeakerName() : s.getSpeakerId();
                if (s.getTitle() != null && !s.getTitle().isBlank()) label += "·" + s.getTitle();
                String body = s.getSummary().length() > 400
                        ? s.getSummary().substring(0, 400) + "…" : s.getSummary();
                context.append("[发言摘要 - ").append(label).append("]\n").append(body).append("\n");
            }
        } catch (Exception e) {
            log.debug("[PreMeetingService] appendSpeakerSummaries failed: {}", e.getMessage());
        }
    }

    private void appendFileSummaries(StringBuilder context, String sessionId) {
        try {
            var session = interpretationSessionMapper.findBySessionId(sessionId);
            if (session == null || session.getMeetingId() == null) return;
            var files = persistentFileMapper.findByMeetingId(session.getMeetingId());
            for (var f : files) {
                if (f.getSummary() == null || f.getSummary().isBlank()) continue;
                String body = f.getSummary().length() > 400
                        ? f.getSummary().substring(0, 400) + "…" : f.getSummary();
                context.append("[文件摘要 - ").append(f.getFileName()).append("]\n")
                        .append(body).append("\n");
            }
        } catch (Exception e) {
            log.debug("[PreMeetingService] appendFileSummaries failed: {}", e.getMessage());
        }
    }

    public String getFileName(String fileId) {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期");
        return doc.fileName();
    }

    private String storeDoc(String fileName, String ext, String text, byte[] originalBytes) {
        String fileId = UUID.randomUUID().toString();
        store.put(fileId, new PreMeetingDoc(fileName, ext, text, originalBytes));
        return fileId;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
