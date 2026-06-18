package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.OpenAiProperties;
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
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.text.Collator;
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
    private static final Pattern GROUP_LINE_REWRITE_PATTERN = Pattern.compile("^(.*?[（(])\\d+([）)]\\s*[:：]\\s*)(.*)$");
    private static final Pattern PARTICIPANT_TOTAL_LINE_PATTERN = Pattern.compile(
            "^(.*?[:：]\\s*)\\d+\\s*Orang\\s*人?.*$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_ATTENDANCE_NAME_LENGTH = 60;
    private static final int ATTENDANCE_EXPORT_FONT_SIZE = 11;
    private static final int MAX_RAG_SESSIONS = 5;
    private static final int MAX_RAG_SNIPPETS_PER_SESSION = 20;
    private static final int MAX_RAG_SOURCES = 8;
    private static final int SOURCE_SNIPPET_MAX_CHARS = 220;
    private static final String ATTENDANCE_STATUS_PRESENT = "present";
    private static final String ATTENDANCE_STATUS_ABSENT = "absent";
    private static final String ATTENDANCE_STATUS_UNEXPECTED = "unexpected";
    private static final String ACTUAL_ATTENDANCE_HEADER = "Peserta Akt. 实际参会人员 ：";
    private static final String PARTICIPANT_TOTAL_FALLBACK_PREFIX = "Jumlah Peserta参会人数\t：\t";
    private static final String ABSENT_PREFIX = "Tidak Hadir缺席\t\t：";
    private static final String ABSENT_COUNT_TITLE = "Jumlah Tidak Hadir";
    private static final String ABSENT_COUNT_PREFIX = "缺席人数\t\t\t\t\t：";
    private static final String ABSENT_COUNT_UNIT = " orang 人";
    private static final String UNEXPECTED_PREFIX = "Tambahan 未在安排中（";
    private static final String NAME_SEPARATOR = "、";

    private static final Set<String> ATTENDANCE_SECTION_KEYWORDS = Set.of(
            "参会", "参加", "出席", "列席", "与会", "Peserta", "peserta");
    private static final Set<String> ATTENDANCE_STOP_KEYWORDS = Set.of(
            "参会人数", "实际参会人数", "请假", "缺席", "事假", "Absen", "Jlh.",
            "Tidak Hadir", "Jumlah Tidak Hadir", "会议议程", "Agenda");
    private static final Set<String> NAME_STOPWORDS = Set.of(
            "会议", "通知", "时间", "地点", "人员", "名单", "参会", "参加", "出席", "列席", "秘书",
            "主持", "记录", "团队", "部门", "单位", "职务", "姓名", "人数", "其他", "国内连线",
            "总部", "大区", "工业", "会议室", "Teams", "Meeting", "Password", "Agenda", "Notulen",
            "Peserta", "Akt", "Orang", "Absen", "Tidak", "Hadir", "Jumlah");

    private final LlmIntegration llmIntegration;
    private final PreMeetingUsageMapper usageMapper;
    private final InterpretationResultMapper interpretationResultMapper;
    private final VectorSearchService vectorSearchService;
    private final RagEnhancementService ragEnhancementService;
    private final OpenAiProperties openAiProperties;
    private final com.si.backend.mapper.InterpretationSessionMapper interpretationSessionMapper;
    private final com.si.backend.mapper.SpeakerSummaryRecordMapper speakerSummaryMapper;
    private final com.si.backend.mapper.PersistentPreMeetingFileMapper persistentFileMapper;
    private final com.si.backend.mapper.MeetingMapper meetingMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** LibreOffice executable used to convert the export Word into a faithful PDF. */
    @Value("${libreoffice.path:soffice}")
    private String libreOfficePath;

    /** Small-to-Big: chars of original text to include on each side of a matched file chunk. */
    @Value("${rag.retrieval.window-pad:400}")
    private int retrievalWindowPad;

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
    private final ConcurrentHashMap<String, Long> storeTimes = new ConcurrentHashMap<>();
    private static final long STORE_TTL_MS = 4L * 60 * 60 * 1000; // 4 hours

    @PostConstruct
    public void initTable() {
        log.info("[PreMeetingService] initTable start");
        usageMapper.createTableIfNotExists();
        try {
            usageMapper.addMeetingIdColumnIfNotExists();
        } catch (org.springframework.dao.DataAccessException e) {
            if (e.getMessage() == null || !e.getMessage().contains("Duplicate column")) {
                log.warn("[PreMeetingService] addMeetingIdColumn failed: {}", e.getMessage());
            }
        }
        log.info("[PreMeetingService] initTable end");
    }

    /** Remove 会前 usage records of a meeting (called from meeting deletion to drop its 会前 cost). */
    public void deleteUsageByMeetingId(Long meetingId) {
        if (meetingId == null) return;
        try {
            int rows = usageMapper.deleteByMeetingId(meetingId);
            log.info("[PreMeetingService] deleteUsageByMeetingId, meetingId={}, rows={}", meetingId, rows);
        } catch (Exception e) {
            log.warn("[PreMeetingService] deleteUsageByMeetingId failed, meetingId={}: {}", meetingId, e.getMessage());
        }
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
        return summarize(fileId, requirements, userId, null);
    }

    public PreMeetingSummaryVo summarize(String fileId, String requirements, long userId, Long meetingId) throws IOException {
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
                    .meetingId(meetingId)
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

        return buildAttendanceVo(expectedParticipants, actualParticipants, fileId, doc.fileName(), deriveMeetingTitle(doc));
    }

    /**
     * Parse the 应到 list from a freshly-uploaded 会议安排 and persist it on the meeting, so the
     * attendance comparison survives across sessions (next time just refresh 实到 to re-compare).
     */
    public int saveExpectedParticipants(String fileId, Long meetingId) {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期，请重新上传");
        }
        List<ExpectedParticipant> expected = parseExpectedParticipants(doc.text());
        if (expected.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未能从会议安排中识别参会人员，请检查文件中的参会人员格式");
        }
        try {
            String json = objectMapper.writeValueAsString(expected);
            meetingMapper.updateExpectedParticipants(meetingId, json);
            log.info("[PreMeetingService] saveExpectedParticipants done, meetingId={}, count={}", meetingId, expected.size());
            return expected.size();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "保存应到名单失败：" + e.getMessage());
        }
    }

    /**
     * Re-generate the attendance comparison using the 应到 list saved on the meeting (no in-memory
     * 会议安排 needed) against the freshly-pulled Teams 实到 list.
     */
    public PreMeetingAttendanceVo generateAttendanceFromMeeting(
            Long meetingId,
            List<PreMeetingParticipantRequest> actualParticipants) {
        com.si.backend.entity.Meeting meeting = meetingMapper.findById(meetingId);
        if (meeting == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会议不存在");
        }
        String json = meeting.getExpectedParticipantsJson();
        if (json == null || json.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "该会议未保存应到名单，请上传会议安排");
        }
        List<ExpectedParticipant> expected;
        try {
            expected = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ExpectedParticipant.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "读取应到名单失败：" + e.getMessage());
        }
        if (expected.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "该会议未保存应到名单，请上传会议安排");
        }
        return buildAttendanceVo(expected, actualParticipants, null, null, meeting.getTitle());
    }

    /**
     * 应到名单 names saved on the meeting (parsed from a prior 会议安排 upload). Empty list if none saved
     * or unparsable. Used for the meeting notification when the original file is no longer in memory.
     */
    public List<String> expectedParticipantNames(Long meetingId) {
        com.si.backend.entity.Meeting meeting = meetingMapper.findById(meetingId);
        if (meeting == null) return List.of();
        String json = meeting.getExpectedParticipantsJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ExpectedParticipant> expected = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ExpectedParticipant.class));
            return expected.stream().map(ExpectedParticipant::name)
                    .filter(n -> n != null && !n.isBlank()).toList();
        } catch (Exception e) {
            log.warn("[PreMeetingService] expectedParticipantNames parse failed, meetingId={}: {}",
                    meetingId, e.getMessage());
            return List.of();
        }
    }

    public String getMeetingTitle(Long meetingId) {
        if (meetingId == null) {
            return "实际参会名单";
        }
        com.si.backend.entity.Meeting meeting = meetingMapper.findById(meetingId);
        if (meeting == null || meeting.getTitle() == null || meeting.getTitle().isBlank()) {
            return "实际参会名单";
        }
        return meeting.getTitle();
    }

    /** Core 应到 vs 实到 comparison, shared by the file-based and meeting-based attendance paths. */
    private PreMeetingAttendanceVo buildAttendanceVo(
            List<ExpectedParticipant> expectedParticipants,
            List<PreMeetingParticipantRequest> actualParticipants,
            String fileId,
            String fileName,
            String meetingTitle) {
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
        log.info("[PreMeetingService] buildAttendanceVo done, fileId={}, expectedCount={}, presentCount={}, absentCount={}, unexpectedCount={}",
                fileId, expectedParticipants.size(), presentCount, absentCount, unexpectedCount);

        return PreMeetingAttendanceVo.builder()
                .fileId(fileId)
                .fileName(fileName)
                .meetingTitle(meetingTitle)
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
        return buildAttendanceExportDocx(fileId, null, actualParticipants);
    }

    public byte[] buildAttendanceExportDocx(
            String fileId,
            Long meetingId,
            List<PreMeetingParticipantRequest> actualParticipants) throws IOException {
        if (fileId != null && !fileId.isBlank()) {
            return buildAttendanceExportDocxFromFile(fileId, actualParticipants);
        }
        if (meetingId != null) {
            return buildAttendanceExportDocxFromMeeting(meetingId, actualParticipants);
        }
        throw BizException.of(ErrorCode.BAD_REQUEST, "请选择会议安排文件或已保存应到名单的会议");
    }

    private byte[] buildAttendanceExportDocxFromFile(
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
                boolean templateUpdated = replaceAttendanceBlockPreservingLayout(output, attendance);
                if (!templateUpdated) {
                    replaceAttendanceBlock(output, attendanceLines);
                }
                // Drop the agenda section (【SUSUNAN JADWAL RAPAT 会议议程】) and everything after it.
                removeAgendaSectionOnward(output);
                if (templateUpdated) {
                    appendAbsenceSection(output, attendance);
                }
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

    private byte[] buildAttendanceExportDocxFromMeeting(
            Long meetingId,
            List<PreMeetingParticipantRequest> actualParticipants) throws IOException {
        if (meetingId == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择已保存应到名单的会议");
        }

        log.info("[PreMeetingService] buildAttendanceExportDocxFromMeeting start, meetingId={}", meetingId);
        PreMeetingAttendanceVo attendance = generateAttendanceFromMeeting(meetingId, actualParticipants);
        List<String> attendanceLines = buildAttendanceSectionLines(attendance);

        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XWPFDocument.class.getClassLoader());
            XWPFDocument output = buildNewAttendanceDocument(attendance.getMeetingTitle(), attendanceLines);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.write(out);
            output.close();
            log.info("[PreMeetingService] buildAttendanceExportDocxFromMeeting done, meetingId={}, lineCount={}",
                    meetingId, attendanceLines.size());
            return out.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    public List<PreMeetingDailyUsageVo> getDailyUsage(long userId, int days) {
        String since = LocalDate.now().minusDays(days - 1).toString();
        return usageMapper.findDailyUsage(userId, since);
    }

    private static final int ZIP_MAX_ENTRIES = 50;
    private static final long ZIP_MAX_ENTRY_BYTES = 50L * 1024 * 1024;   // 50 MB per entry
    private static final long ZIP_MAX_TOTAL_BYTES = 200L * 1024 * 1024;  // 200 MB total

    private List<PreMeetingFileVo> processZip(InputStream in) throws IOException {
        List<PreMeetingFileVo> result = new ArrayList<>();
        int entryCount = 0;
        long totalBytes = 0;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                if (++entryCount > ZIP_MAX_ENTRIES) {
                    log.warn("[PreMeetingService] zip entry count exceeded {}, aborting", ZIP_MAX_ENTRIES);
                    break;
                }
                String name = entry.getName();
                String baseName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                String ext = extension(baseName).toLowerCase();
                if (!Set.of("doc", "docx", "pdf").contains(ext)) { zis.closeEntry(); continue; }

                byte[] bytes = readZipEntry(zis, ZIP_MAX_ENTRY_BYTES);
                totalBytes += bytes.length;
                if (totalBytes > ZIP_MAX_TOTAL_BYTES) {
                    log.warn("[PreMeetingService] zip total decompressed size exceeded {} bytes, aborting", ZIP_MAX_TOTAL_BYTES);
                    break;
                }
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

    private static byte[] readZipEntry(ZipInputStream zis, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long read = 0;
        int n;
        while ((n = zis.read(chunk)) != -1) {
            read += n;
            if (read > maxBytes) {
                throw new IOException("ZIP entry exceeds max size " + maxBytes + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private List<String> buildAttendanceSectionLines(PreMeetingAttendanceVo attendance) {
        List<String> lines = new ArrayList<>();
        Map<String, List<String>> presentByDepartment = buildPresentNamesByDepartment(attendance);
        List<String> absentNames = buildAbsentNames(attendance);
        List<String> unexpectedNames = buildUnexpectedNames(attendance);

        // Names sorted alphabetically (Chinese sorted by pinyin) for the Word export.
        presentByDepartment.values().forEach(this::sortNamesAlphabetically);
        sortNamesAlphabetically(absentNames);
        sortNamesAlphabetically(unexpectedNames);

        lines.add(ACTUAL_ATTENDANCE_HEADER);
        for (Map.Entry<String, List<String>> entry : presentByDepartment.entrySet()) {
            List<String> names = entry.getValue();
            lines.add(entry.getKey() + "（" + names.size() + "）\t：" + String.join(NAME_SEPARATOR, names));
        }
        lines.add(PARTICIPANT_TOTAL_FALLBACK_PREFIX + safeCount(attendance.getActualCount()) + " Orang人");
        lines.add(ABSENT_PREFIX + formatNameList(absentNames));
        lines.add(ABSENT_COUNT_TITLE);
        lines.add(ABSENT_COUNT_PREFIX + safeCount(attendance.getAbsentCount()) + ABSENT_COUNT_UNIT);
        if (!unexpectedNames.isEmpty()) {
            lines.add(UNEXPECTED_PREFIX + unexpectedNames.size() + "）\t："
                    + String.join(NAME_SEPARATOR, unexpectedNames));
        }
        return lines;
    }

    private Map<String, List<String>> buildPresentNamesByDepartment(PreMeetingAttendanceVo attendance) {
        Map<String, List<String>> presentByDepartment = new LinkedHashMap<>();
        for (PreMeetingAttendanceRowVo row : attendanceRows(attendance)) {
            if (!ATTENDANCE_STATUS_PRESENT.equals(safeString(row.getStatus()))) {
                continue;
            }
            String department = safeString(row.getDepartment()).isBlank()
                    ? "Lainnya 其他连线"
                    : row.getDepartment();
            presentByDepartment.computeIfAbsent(department, ignored -> new ArrayList<>())
                    .add(safeString(row.getName()));
        }
        return presentByDepartment;
    }

    private List<String> buildAbsentNames(PreMeetingAttendanceVo attendance) {
        List<String> absentNames = new ArrayList<>();
        for (PreMeetingAttendanceRowVo row : attendanceRows(attendance)) {
            if (ATTENDANCE_STATUS_ABSENT.equals(safeString(row.getStatus()))) {
                absentNames.add(safeString(row.getName()));
            }
        }
        return absentNames;
    }

    private List<String> buildUnexpectedNames(PreMeetingAttendanceVo attendance) {
        List<String> unexpectedNames = new ArrayList<>();
        for (PreMeetingAttendanceRowVo row : attendanceRows(attendance)) {
            if (!ATTENDANCE_STATUS_UNEXPECTED.equals(safeString(row.getStatus()))) {
                continue;
            }
            String displayName = safeString(row.getActualName());
            unexpectedNames.add(!displayName.isBlank() ? displayName : safeString(row.getName()));
        }
        return unexpectedNames;
    }

    private List<PreMeetingAttendanceRowVo> attendanceRows(PreMeetingAttendanceVo attendance) {
        if (attendance == null || attendance.getRows() == null) {
            return List.of();
        }
        return attendance.getRows();
    }

    private String formatNameList(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "无";
        }
        return String.join(NAME_SEPARATOR, names);
    }

    /**
     * Sort names alphabetically. Chinese names are ordered by pinyin via a zh-CN {@link Collator},
     * so a list mixing Chinese and Latin names comes out in a consistent A–Z order. A fresh Collator
     * is created per call because Collator instances are not thread-safe.
     */
    private void sortNamesAlphabetically(List<String> names) {
        if (names == null || names.size() < 2) {
            return;
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        names.sort((a, b) -> collator.compare(safeString(a), safeString(b)));
    }

    private int safeCount(Integer count) {
        return count == null ? 0 : count;
    }

    private XWPFDocument buildNewAttendanceDocument(
            PreMeetingDoc doc,
            List<String> attendanceLines) {
        List<String> lines = new ArrayList<>(extractScheduleHeaderLines(doc));
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.addAll(attendanceLines);
        return buildAttendanceLinesDocument(lines);
    }

    private XWPFDocument buildNewAttendanceDocument(
            String meetingTitle,
            List<String> attendanceLines) {
        List<String> lines = new ArrayList<>();
        String title = safeString(meetingTitle).isBlank() ? "实际参会名单" : meetingTitle;
        lines.add("【" + title + "】");
        lines.add("");
        lines.addAll(attendanceLines);
        return buildAttendanceLinesDocument(lines);
    }

    private XWPFDocument buildAttendanceLinesDocument(List<String> lines) {
        XWPFDocument output = new XWPFDocument();
        for (String line : lines) {
            XWPFParagraph paragraph = output.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(line);
            run.setFontSize(ATTENDANCE_EXPORT_FONT_SIZE);
            if (line.startsWith("【")
                    || line.contains("实际参会人员")
                    || line.startsWith("Tidak Hadir")
                    || line.startsWith("Jumlah Tidak Hadir")
                    || line.contains("缺席人数")) {
                run.setBold(true);
            }
        }
        return output;
    }

    private boolean replaceAttendanceBlockPreservingLayout(
            XWPFDocument document,
            PreMeetingAttendanceVo attendance) {
        AttendanceBlock block = findAttendanceBlock(document.getParagraphs());
        if (block == null) {
            return false;
        }

        List<XWPFParagraph> paragraphs = document.getParagraphs();
        if (!hasTemplateAttendanceGroup(paragraphs, block)) {
            return false;
        }

        Map<String, List<String>> presentByDepartment = buildPresentNamesByDepartment(attendance);
        Set<Integer> continuationIndexes = new HashSet<>();
        boolean inGroupContinuation = false;
        boolean totalLineUpdated = false;

        for (int index = block.startIndex(); index <= block.endIndex() && index < paragraphs.size(); index++) {
            XWPFParagraph paragraph = paragraphs.get(index);
            String originalText = paragraph.getText();
            String line = normalizeLine(originalText);
            if (line.isBlank()) {
                continue;
            }

            Matcher groupMatcher = GROUP_COUNT_PATTERN.matcher(line);
            if (groupMatcher.matches()) {
                String department = cleanDepartment(groupMatcher.group(1));
                List<String> names = presentByDepartment.getOrDefault(department, List.of());
                replaceParagraphText(paragraph, rewriteAttendanceGroupLine(originalText, names.size(), names), paragraph);
                inGroupContinuation = true;
                continue;
            }

            if (isParticipantTotalLine(line)) {
                replaceParagraphText(paragraph,
                        rewriteParticipantTotalLine(originalText, safeCount(attendance.getActualCount())),
                        paragraph);
                inGroupContinuation = false;
                totalLineUpdated = true;
                continue;
            }

            if (isTemplateAbsenceLine(line)) {
                continuationIndexes.add(index);
                inGroupContinuation = false;
                continue;
            }

            if (index == block.startIndex() || isAttendanceHeader(line)) {
                replaceParagraphText(paragraph, rewriteAttendanceHeaderLine(originalText), paragraph);
                inGroupContinuation = false;
                continue;
            }

            if (inGroupContinuation) {
                continuationIndexes.add(index);
            }
        }

        removeParagraphsByIndex(document, paragraphs, continuationIndexes);
        if (!totalLineUpdated) {
            appendParticipantTotalLine(document, attendance);
        }
        return true;
    }

    private boolean hasTemplateAttendanceGroup(List<XWPFParagraph> paragraphs, AttendanceBlock block) {
        for (int index = block.startIndex(); index <= block.endIndex() && index < paragraphs.size(); index++) {
            String line = normalizeLine(paragraphs.get(index).getText());
            if (GROUP_COUNT_PATTERN.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    private void removeParagraphsByIndex(
            XWPFDocument document,
            List<XWPFParagraph> paragraphs,
            Set<Integer> indexes) {
        List<Integer> sortedIndexes = new ArrayList<>(indexes);
        sortedIndexes.sort(Comparator.reverseOrder());
        for (Integer index : sortedIndexes) {
            if (index == null || index < 0 || index >= paragraphs.size()) {
                continue;
            }
            int bodyPosition = document.getPosOfParagraph(paragraphs.get(index));
            if (bodyPosition >= 0) {
                document.removeBodyElement(bodyPosition);
            }
        }
    }

    private void appendParticipantTotalLine(
            XWPFDocument document,
            PreMeetingAttendanceVo attendance) {
        XWPFParagraph paragraph = document.createParagraph();
        replaceParagraphText(paragraph,
                PARTICIPANT_TOTAL_FALLBACK_PREFIX + safeCount(attendance.getActualCount()) + " Orang人",
                findLastNonEmptyParagraph(document));
    }

    private void appendAbsenceSection(
            XWPFDocument document,
            PreMeetingAttendanceVo attendance) {
        List<String> lines = new ArrayList<>();
        lines.add(ABSENT_PREFIX + formatNameList(buildAbsentNames(attendance)));
        lines.add(ABSENT_COUNT_TITLE);
        lines.add(ABSENT_COUNT_PREFIX + safeCount(attendance.getAbsentCount()) + ABSENT_COUNT_UNIT);

        List<String> unexpectedNames = buildUnexpectedNames(attendance);
        if (!unexpectedNames.isEmpty()) {
            lines.add(UNEXPECTED_PREFIX + unexpectedNames.size() + "）\t："
                    + String.join(NAME_SEPARATOR, unexpectedNames));
        }

        XWPFParagraph paragraph = document.createParagraph();
        replaceParagraphLines(paragraph, lines, findLastNonEmptyParagraph(document));
    }

    private XWPFParagraph findLastNonEmptyParagraph(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int index = paragraphs.size() - 1; index >= 0; index--) {
            XWPFParagraph paragraph = paragraphs.get(index);
            if (!normalizeLine(paragraph.getText()).isBlank()) {
                return paragraph;
            }
        }
        return null;
    }

    private String rewriteAttendanceHeaderLine(String originalText) {
        return leadingWhitespace(originalText) + ACTUAL_ATTENDANCE_HEADER;
    }

    private String rewriteAttendanceGroupLine(
            String originalText,
            int count,
            List<String> names) {
        Matcher matcher = GROUP_LINE_REWRITE_PATTERN.matcher(originalText);
        if (matcher.matches()) {
            return matcher.group(1) + count + matcher.group(2) + String.join(NAME_SEPARATOR, names);
        }
        return originalText + "（" + count + "）：" + String.join(NAME_SEPARATOR, names);
    }

    private String rewriteParticipantTotalLine(String originalText, int actualCount) {
        Matcher matcher = PARTICIPANT_TOTAL_LINE_PATTERN.matcher(originalText);
        if (matcher.matches()) {
            return matcher.group(1) + actualCount + " Orang人";
        }
        return leadingWhitespace(originalText) + PARTICIPANT_TOTAL_FALLBACK_PREFIX + actualCount + " Orang人";
    }

    private String leadingWhitespace(String value) {
        String text = value == null ? "" : value;
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return text.substring(0, index);
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

    /**
     * Pick a representative BODY run from the document so the appended summary can inherit the
     * original's font/size. Uses the longest paragraph (body content, not a short centered title)
     * to avoid copying the title's larger/centered formatting onto the summary body.
     */
    private XWPFRun findBodySampleRun(XWPFDocument doc) {
        XWPFRun best = null;
        int bestLen = 0;
        for (XWPFParagraph p : doc.getParagraphs()) {
            String t = p.getText();
            if (t == null || t.trim().isEmpty()) {
                continue;
            }
            XWPFRun r = firstRun(p);
            if (r != null && r.getText(0) != null && t.trim().length() > bestLen) {
                bestLen = t.trim().length();
                best = r;
            }
        }
        return best;
    }

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern MD_ULIST = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern MD_OLIST = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");

    // ── AI 摘要固定排版：中文=仿宋/18，印尼语(拉丁)=Times New Roman/16（不再可选） ──
    private static final String SUMMARY_CN_FONT = "仿宋";
    private static final int SUMMARY_CN_SIZE = 18;
    private static final String SUMMARY_LATIN_FONT = "Times New Roman";
    private static final int SUMMARY_LATIN_SIZE = 16;

    /** Render the summary Markdown into the document: headings, bullet/numbered lists, inline **bold**. */
    private void appendMarkdownSummary(XWPFDocument output, String summary) {
        if (summary == null) return;
        for (String raw : summary.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                output.createParagraph();
                continue;
            }
            Matcher h = MD_HEADING.matcher(line);
            Matcher u = MD_ULIST.matcher(line);
            Matcher o = MD_OLIST.matcher(line);
            if (h.matches()) {
                XWPFParagraph p = output.createParagraph();
                renderInlineBilingual(p, h.group(2).trim(), true);   // 标题加粗
            } else if (u.matches()) {
                XWPFParagraph p = output.createParagraph();
                p.setIndentationLeft(360);
                emitMarker(p, "• ");
                renderInlineBilingual(p, u.group(1).trim(), false);
            } else if (o.matches()) {
                XWPFParagraph p = output.createParagraph();
                p.setIndentationLeft(360);
                emitMarker(p, o.group(1) + ". ");
                renderInlineBilingual(p, o.group(2).trim(), false);
            } else {
                XWPFParagraph p = output.createParagraph();
                renderInlineBilingual(p, line.trim(), false);
            }
        }
    }

    /** List bullet/number marker — Latin font/size. */
    private void emitMarker(XWPFParagraph p, String marker) {
        XWPFRun r = p.createRun();
        r.setFontFamily(SUMMARY_LATIN_FONT);
        r.setFontSize(SUMMARY_LATIN_SIZE);
        r.setText(marker);
    }

    /** Split a line on **bold** spans, then emit per-script runs (中文 vs 拉丁) with the fixed fonts. */
    private void renderInlineBilingual(XWPFParagraph p, String text, boolean baseBold) {
        if (text == null) return;
        String[] parts = text.split("\\*\\*", -1);
        for (int k = 0; k < parts.length; k++) {
            if (parts[k].isEmpty()) continue;
            boolean bold = baseBold || (k % 2 == 1);   // odd segments are between ** **
            emitBilingual(p, parts[k], bold);
        }
    }

    /** Emit runs grouping consecutive Chinese / Latin chars: 中文→仿宋18, 其它→Times New Roman16. */
    private void emitBilingual(XWPFParagraph p, String seg, boolean bold) {
        int i = 0;
        while (i < seg.length()) {
            boolean cn = isChineseChar(seg.charAt(i));
            int j = i + 1;
            while (j < seg.length() && isChineseChar(seg.charAt(j)) == cn) j++;
            XWPFRun r = p.createRun();
            if (cn) {
                r.setFontFamily(SUMMARY_CN_FONT);
                r.setFontFamily(SUMMARY_CN_FONT, XWPFRun.FontCharRange.eastAsia);
                r.setFontSize(SUMMARY_CN_SIZE);
            } else {
                r.setFontFamily(SUMMARY_LATIN_FONT);
                r.setFontSize(SUMMARY_LATIN_SIZE);
            }
            r.setBold(bold);
            r.setText(seg.substring(i, j));
            i = j;
        }
    }

    /** Han ideographs + CJK punctuation/full-width forms count as Chinese (so 逗号句号也用仿宋). */
    private static boolean isChineseChar(char c) {
        if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return true;
        return (c >= '　' && c <= '〿') || (c >= '＀' && c <= '￯');
    }

    private void applyFont(XWPFRun r, String font, int size, boolean bold) {
        if (notBlank(font)) r.setFontFamily(font);
        if (size > 0) r.setFontSize(size);
        r.setBold(bold);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
                || line.contains("实际参会人员")
                || GROUP_COUNT_PATTERN.matcher(line).matches();
    }

    private boolean isAttendanceBlockTerminalLine(String line) {
        return line.contains("请假人数")
                || line.contains("缺席人数")
                || line.contains("Jlh. Absen")
                || (line.contains("Jumlah Tidak Hadir") && (line.contains(":") || line.contains("：")));
    }

    private boolean isParticipantTotalLine(String line) {
        return line.contains("Jumlah Peserta")
                || line.contains("参会人数");
    }

    private boolean isTemplateAbsenceLine(String line) {
        return line.contains("事假")
                || line.contains("请假")
                || line.contains("缺席")
                || line.contains("Jlh. Absen")
                || line.contains("Tidak Hadir")
                || line.contains("Jumlah Tidak Hadir");
    }

    private boolean isPostAttendanceSectionStart(String line) {
        return line.contains("会议议程")
                || line.contains("Agenda")
                || line.contains("议题")
                || line.contains("事项")
                || line.contains("Pembahasan");
    }

    /** The agenda heading (e.g. 「【SUSUNAN JADWAL RAPAT 会议议程】」) that marks the end of the kept content. */
    private boolean isAgendaSectionHeading(String line) {
        return line.contains("会议议程")
                || line.toUpperCase(Locale.ROOT).contains("SUSUNAN JADWAL");
    }

    /**
     * Remove the agenda section and everything after it from the export. Finds the agenda heading
     * paragraph and deletes every body element (paragraphs AND tables) from there to the end.
     */
    private void removeAgendaSectionOnward(XWPFDocument document) {
        int agendaPos = -1;
        for (XWPFParagraph p : document.getParagraphs()) {
            if (isAgendaSectionHeading(normalizeLine(p.getText()))) {
                agendaPos = document.getPosOfParagraph(p);
                break;
            }
        }
        if (agendaPos < 0) {
            return;
        }
        for (int index = document.getBodyElements().size() - 1; index >= agendaPos; index--) {
            document.removeBodyElement(index);
        }
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
            if (line.length() >= 4
                    && line.length() <= 80
                    && (line.contains("专项会议") || line.contains("专题会议"))
                    && !line.contains("会议通知")) {
                title = line.replaceAll("^[【\\[]|[】\\]]$", "");
                break;
            }
        }
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

    /** User-adjustable summary formatting (Option 1): body font/size + heading size. 0/blank = inherit. */
    public record SummaryFormat(String bodyFont, int bodySize, int headingSize) {
        public static final SummaryFormat INHERIT = new SummaryFormat(null, 0, 0);
    }

    public byte[] buildExportDocx(String fileId, String summary) throws IOException {
        return buildExportDocx(fileId, summary, SummaryFormat.INHERIT);
    }

    public byte[] buildExportDocx(String fileId, String summary, SummaryFormat fmt) throws IOException {
        PreMeetingDoc doc = store.get(fileId);
        if (doc == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已过期，请重新上传");
        }
        if (fmt == null) fmt = SummaryFormat.INHERIT;

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

            // Capture the original document's body text format (font/size/etc.) BEFORE appending,
            // so the summary we add below matches the original content's look unless overridden.
            RunShell bodyShell = captureRunShell(findBodySampleRun(output));

            // Resolve effective fonts/sizes: explicit format overrides > original body > defaults.
            String bodyFont = notBlank(fmt.bodyFont()) ? fmt.bodyFont()
                    : (bodyShell != null ? bodyShell.fontFamily() : null);
            int bodySize = fmt.bodySize() > 0 ? fmt.bodySize()
                    : (bodyShell != null && bodyShell.fontSize() > 0 ? bodyShell.fontSize() : 11);

            // Separator paragraph (centered)
            XWPFParagraph sep = output.createParagraph();
            sep.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun sepRun = sep.createRun();
            applyFont(sepRun, bodyFont, bodySize, false);
            sepRun.setText("──────────────────────");

            // "chatgpt总结" main heading — centered, bold, 仿宋.
            XWPFParagraph heading = output.createParagraph();
            heading.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun headingRun = heading.createRun();
            headingRun.setFontFamily(SUMMARY_CN_FONT);
            headingRun.setFontFamily(SUMMARY_CN_FONT, XWPFRun.FontCharRange.eastAsia);
            headingRun.setFontSize(SUMMARY_CN_SIZE);
            headingRun.setBold(true);
            headingRun.setText("chatgpt总结");

            // Summary body — fixed bilingual fonts (中文仿宋18 / 印尼语 Times New Roman16).
            appendMarkdownSummary(output, summary);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.write(out);
            output.close();
            return out.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    /**
     * Build a standalone Word doc from a history summary/总结 text (no original file): centered title
     * + the summary rendered as Markdown (headings/lists/bold). Used to send a Word to Teams.
     */
    public byte[] buildSummaryDocx(String title, String summaryText, SummaryFormat fmt) throws IOException {
        if (fmt == null) fmt = SummaryFormat.INHERIT;
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XWPFDocument.class.getClassLoader());
            XWPFDocument output = new XWPFDocument();

            if (notBlank(title)) {
                XWPFParagraph t = output.createParagraph();
                t.setAlignment(ParagraphAlignment.CENTER);
                renderInlineBilingual(t, title, true);   // 仿宋18 / TNR16，加粗
            }
            appendMarkdownSummary(output, summaryText == null ? "" : summaryText);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.write(out);
            output.close();
            return out.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    /**
     * Build the export PDF: generate the same Word document (original content + appended summary)
     * as {@link #buildExportDocx}, then convert it to PDF with LibreOffice headless so the PDF is a
     * faithful render of the Word (centered titles, fonts, tables all preserved).
     */
    public byte[] buildExportPdf(String fileId, String summary) throws IOException {
        return buildExportPdf(fileId, summary, SummaryFormat.INHERIT);
    }

    public byte[] buildExportPdf(String fileId, String summary, SummaryFormat fmt) throws IOException {
        return convertDocxToPdf(buildExportDocx(fileId, summary, fmt));
    }

    /** Build a standalone 会议总结 PDF (same 仿宋18/TNR16 format as the AI summary), title + body. */
    public byte[] buildSummaryPdf(String title, String summaryText) throws IOException {
        return convertDocxToPdf(buildSummaryDocx(title, summaryText, SummaryFormat.INHERIT));
    }

    /**
     * Build a 发言摘要 PDF:
     * <pre>
     * {会议名}                                                    ← 标题（居中加粗）
     * —— {发言人}（{去姓+总}）在{会议名}发言 GPT 总结（NNN#） ——      ← 小标题（居中）
     * {正文}
     * 日期：{dateText}
     * 整理：GPT（XXX）
     * </pre>
     * Same 仿宋18 / Times New Roman16 fonts as the AI/会议总结.
     */
    public byte[] buildSpeakerSummaryPdf(String meetingName, String speakerName, int sequence,
                                         String dateText, String body) throws IOException {
        return convertDocxToPdf(buildSpeakerSummaryDocx(meetingName, speakerName, sequence, dateText, body));
    }

    public byte[] buildSpeakerSummaryDocx(String meetingName, String speakerName, int sequence,
                                          String dateText, String body) throws IOException {
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XWPFDocument.class.getClassLoader());
            XWPFDocument output = new XWPFDocument();

            if (notBlank(meetingName)) {
                XWPFParagraph t = output.createParagraph();
                t.setAlignment(ParagraphAlignment.CENTER);
                renderInlineBilingual(t, meetingName, true);   // 标题：会议名
            }
            String honorific = speakerHonorific(speakerName);
            String subtitle = "—— " + (speakerName == null ? "" : speakerName)
                    + (notBlank(honorific) ? "（" + honorific + "）" : "")
                    + "在" + (meetingName == null ? "" : meetingName)
                    + "发言 GPT 总结（" + String.format("%03d", Math.max(sequence, 0)) + "#） ——";
            XWPFParagraph sub = output.createParagraph();
            sub.setAlignment(ParagraphAlignment.CENTER);
            renderInlineBilingual(sub, subtitle, false);

            output.createParagraph();                          // 空行
            appendMarkdownSummary(output, body == null ? "" : body);   // 发言总结正文
            output.createParagraph();                          // 空行

            XWPFParagraph dateP = output.createParagraph();
            renderInlineBilingual(dateP, "日期：" + (dateText == null ? "" : dateText), false);
            XWPFParagraph orgP = output.createParagraph();
            renderInlineBilingual(orgP, "整理：GPT（XXX）", false);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.write(out);
            output.close();
            return out.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    /** 昵称：中文姓名去掉姓（首字）后 + 总，如「杨保才」→「保才总」；非中文名不生成昵称。 */
    private static String speakerHonorific(String name) {
        if (name == null) return "";
        String n = name.trim();
        if (n.isEmpty()) return "";
        if (Character.UnicodeScript.of(n.charAt(0)) != Character.UnicodeScript.HAN) return "";
        return (n.length() <= 1 ? n : n.substring(1)) + "总";
    }

    /** Convert a .docx byte[] to PDF via LibreOffice headless (faithful render of fonts/alignment). */
    public byte[] convertDocxToPdf(byte[] docxBytes) throws IOException {
        Path tmpDir = Files.createTempDirectory("si-pdf-");
        try {
            Path docxPath = tmpDir.resolve("export.docx");
            Files.write(docxPath, docxBytes);
            // Use a per-call user profile so concurrent conversions don't fight over the default lock.
            String profileArg = "-env:UserInstallation=" + tmpDir.resolve("profile").toUri();

            Process proc = new ProcessBuilder(
                    libreOfficePath, profileArg, "--headless", "--norestore", "--nolockcheck",
                    "--convert-to", "pdf", "--outdir", tmpDir.toString(), docxPath.toString())
                    .redirectErrorStream(true)
                    .start();
            // LibreOffice closes its streams on exit, so draining first returns when it finishes.
            String procOutput = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = proc.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw BizException.of(ErrorCode.BAD_REQUEST, "PDF 转换超时，请重试");
            }

            Path pdfPath = tmpDir.resolve("export.pdf");
            if (!Files.exists(pdfPath)) {
                log.error("[PreMeetingService] LibreOffice 未生成 PDF, exit={}, output={}",
                        proc.exitValue(), procOutput);
                throw BizException.of(ErrorCode.BAD_REQUEST,
                        "PDF 转换失败（请确认后端已安装 LibreOffice）");
            }
            return Files.readAllBytes(pdfPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.BAD_REQUEST, "PDF 转换被中断");
        } finally {
            try (var paths = Files.walk(tmpDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }

    public PreMeetingChatVo chat(
            String fileId,
            String sessionId,
            String question,
            List<ChatTurn> history) throws IOException {
        long startMs = System.currentTimeMillis();
        log.info("[PreMeetingService] chat start, fileId={}, sessionId={}, questionLen={}, questionHash={}, historySize={}",
                fileId, sessionId, question != null ? question.length() : 0,
                diagnosticHash(question), history != null ? history.size() : 0);
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
        log.info("[PreMeetingService] chat context built, fileId={}, sessionId={}, questionHash={}, contextLen={}, sources={}",
                fileId, sessionId, diagnosticHash(question), context.length(), sources.size());
        long llmStartMs = System.currentTimeMillis();
        String answer = llmIntegration.chat(context.toString(), question, history);
        log.info("[PreMeetingService] chat llm done, questionHash={}, answerLen={}, llmMs={}",
                diagnosticHash(question), answer != null ? answer.length() : 0,
                System.currentTimeMillis() - llmStartMs);
        String contextSummary = sources.isEmpty() ? "无参考资料" : "基于：" + String.join("、", sources);
        log.info("[PreMeetingService] chat end, questionHash={}, contextLen={}, answerLen={}, costMs={}",
                diagnosticHash(question), context.length(), answer != null ? answer.length() : 0,
                System.currentTimeMillis() - startMs);
        return PreMeetingChatVo.builder().answer(answer).contextSummary(contextSummary).build();
    }

    public PreMeetingChatVo chatCrossMeeting(
            long userId,
            String question,
            List<ChatTurn> history,
            int days) throws IOException {

        long startMs = System.currentTimeMillis();
        String since = days > 0 ? LocalDate.now().minusDays(days).toString() : null;
        log.info("[PreMeetingService] chatCrossMeeting start, userId={}, days={}, since={}, questionLen={}, questionHash={}, historySize={}",
                userId, days, since, question != null ? question.length() : 0,
                diagnosticHash(question), history != null ? history.size() : 0);

        // P0-3: resolve pronouns/ellipsis against recent history so retrieval matches the real intent.
        String retrievalQuery = ragEnhancementService.rewriteQuery(history, question);
        log.info("[PreMeetingService] chatCrossMeeting retrieval query, userId={}, questionHash={}, retrievalHash={}, changed={}",
                userId, diagnosticHash(question), diagnosticHash(retrievalQuery),
                retrievalQuery != null && !retrievalQuery.equals(question));
        long embedStartMs = System.currentTimeMillis();
        float[] queryVec = llmIntegration.embed(retrievalQuery);
        log.info("[PreMeetingService] chatCrossMeeting embedded, userId={}, retrievalHash={}, dims={}, costMs={}",
                userId, diagnosticHash(retrievalQuery), queryVec.length,
                System.currentTimeMillis() - embedStartMs);
        List<VectorSearchService.SearchResult> hits =
                vectorSearchService.search(userId, retrievalQuery, queryVec, null, null, since, 40);
        log.info("[PreMeetingService] chatCrossMeeting recall done, userId={}, retrievalHash={}, hits={}",
                userId, diagnosticHash(retrievalQuery), hits.size());

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

        log.info("[PreMeetingService] chatCrossMeeting context built, userId={}, questionHash={}, sessions={}, contextLen={}",
                userId, diagnosticHash(question), bySession.size(), context.length());
        long llmStartMs = System.currentTimeMillis();
        String answer = llmIntegration.chatCrossMeeting(context.toString(), question, history);
        log.info("[PreMeetingService] chatCrossMeeting llm done, userId={}, questionHash={}, answerLen={}, llmMs={}",
                userId, diagnosticHash(question), answer != null ? answer.length() : 0,
                System.currentTimeMillis() - llmStartMs);
        int refCount = Math.min(3, sessionLabels.size());
        String contextSummary = "语义检索了 " + bySession.size() + " 场会议，引用：" +
                String.join("、", sessionLabels.subList(0, refCount));

        log.info("[PreMeetingService] chatCrossMeeting end, userId={}, questionHash={}, sessions={}, answerLen={}, costMs={}",
                userId, diagnosticHash(question), bySession.size(),
                answer != null ? answer.length() : 0, System.currentTimeMillis() - startMs);
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
        long startMs = System.currentTimeMillis();
        log.info("[PreMeetingService] buildUnifiedContext start, userId={}, questionLen={}, questionHash={}, filterMeetingId={}, filterSpeaker={}, filterSince={}",
                userId, question != null ? question.length() : 0, diagnosticHash(question),
                filter != null ? filter.meetingId() : null,
                filter != null ? filter.speakerName() : null,
                filter != null ? filter.since() : null);
        try {
            // P0: multi-query expansion → merged recall → LLM rerank (all no-ops when flags off).
            // P1-4: split multi-hop / comparison questions into sub-questions, then expand each.
            List<String> queries = new ArrayList<>();
            int maxQueries = openAiProperties.getRagMaxQueries() > 0 ? openAiProperties.getRagMaxQueries() : 9;
            for (String sub : ragEnhancementService.decompose(question)) {
                for (String q : ragEnhancementService.expandQueries(sub)) {
                    if (queries.stream().noneMatch(q::equalsIgnoreCase)) queries.add(q);
                    if (queries.size() >= maxQueries) break;
                }
                if (queries.size() >= maxQueries) break;
            }
            log.info("[PreMeetingService] buildUnifiedContext queries built, userId={}, questionHash={}, queryCount={}, maxQueries={}",
                    userId, diagnosticHash(question), queries.size(), maxQueries);
            int perQueryTopK = openAiProperties.getRagRecallPerQueryTopK() > 0
                    ? openAiProperties.getRagRecallPerQueryTopK()
                    : 40;
            long recallStart = System.currentTimeMillis();
            List<VectorSearchService.SearchResult> hits = multiQueryRecall(userId, queries, filter, perQueryTopK);
            log.info("[PreMeetingService] multiQueryRecall done, userId={}, queries={}, perQueryTopK={}, hits={}, costMs={}",
                    userId, queries.size(), perQueryTopK, hits.size(), System.currentTimeMillis() - recallStart);
            if (hits.isEmpty()) {
                log.info("[PreMeetingService] buildUnifiedContext done, userId={}, questionHash={}, sessions=0, sources=0, reason=noHits, costMs={}",
                        userId, diagnosticHash(question), System.currentTimeMillis() - startMs);
                return new UnifiedContextResult("", List.of());
            }
            int hitsBeforeRerank = hits.size();
            hits = ragEnhancementService.rerank(question, hits);
            log.info("[PreMeetingService] buildUnifiedContext rerank applied, userId={}, questionHash={}, before={}, after={}",
                    userId, diagnosticHash(question), hitsBeforeRerank, hits.size());

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
                Set<Long> expandedFiles = new HashSet<>();   // Small-to-Big: expand each file's window once
                for (VectorSearchService.SearchResult snippet : snippets) {
                    if (count++ >= MAX_RAG_SNIPPETS_PER_SESSION) break;
                    String contextText = contextTextForHit(snippet, expandedFiles);
                    if (contextText == null) continue;   // a later chunk of an already-expanded file
                    if (snippet.speakerName() != null && !snippet.speakerName().isBlank()) {
                        context.append(snippet.speakerName()).append(": ");
                    }
                    context.append(contextText);
                    if (snippet.translatedText() != null && !snippet.translatedText().isBlank()) {
                        context.append(" → ").append(snippet.translatedText());
                    }
                    context.append("\n");
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
            log.info("[PreMeetingService] buildUnifiedContext done, userId={}, questionHash={}, sessions={}, sources={}, contextLen={}, costMs={}",
                    userId, diagnosticHash(question), bySession.size(), sources.size(), context.length(),
                    System.currentTimeMillis() - startMs);
            return new UnifiedContextResult(context.toString(), sources);
        } catch (Exception e) {
            log.warn("[PreMeetingService] buildUnifiedContext failed, userId={}, questionHash={}, costMs={}: {}",
                    userId, diagnosticHash(question), System.currentTimeMillis() - startMs, e.getMessage());
            return new UnifiedContextResult("", List.of());
        }
    }

    /** Embeds each (expanded) query, runs vector search, and merges hits keeping the best score per chunk. */
    private List<VectorSearchService.SearchResult> multiQueryRecall(
            long userId, List<String> queries, QuestionFilter filter, int perQueryTopK) {
        long startMs = System.currentTimeMillis();
        Long filterMeetingId = filter != null ? filter.meetingId() : null;
        String filterSpeaker = filter != null ? filter.speakerName() : null;
        String filterSince = filter != null ? filter.since() : null;
        log.info("[PreMeetingService] multiQueryRecall start, userId={}, queries={}, perQueryTopK={}, filterMeetingId={}, filterSpeaker={}, filterSince={}",
                userId, queries != null ? queries.size() : 0, perQueryTopK,
                filterMeetingId, filterSpeaker, filterSince);
        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        for (String q : queries) {
            long queryStartMs = System.currentTimeMillis();
            float[] vec;
            try {
                vec = llmIntegration.embed(q);
                log.info("[PreMeetingService] multiQueryRecall embedded, userId={}, queryHash={}, dims={}, costMs={}",
                        userId, diagnosticHash(q), vec.length, System.currentTimeMillis() - queryStartMs);
            } catch (Exception e) {
                log.warn("[PreMeetingService] multiQueryRecall embed failed, userId={}, queryHash={}, costMs={}: {}",
                        userId, diagnosticHash(q), System.currentTimeMillis() - queryStartMs, e.getMessage());
                continue;
            }
            List<VectorSearchService.SearchResult> hits = vectorSearchService.search(
                    userId, q, vec, filterMeetingId, filterSpeaker, filterSince, perQueryTopK);
            log.info("[PreMeetingService] multiQueryRecall query done, userId={}, queryHash={}, hits={}, mergedBefore={}, costMs={}",
                    userId, diagnosticHash(q), hits.size(), merged.size(),
                    System.currentTimeMillis() - queryStartMs);
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
        log.info("[PreMeetingService] multiQueryRecall end, userId={}, queries={}, merged={}, costMs={}",
                userId, queries != null ? queries.size() : 0, out.size(), System.currentTimeMillis() - startMs);
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

    /**
     * Small-to-Big: for a file_content hit, return a wider window of the ORIGINAL file text around the
     * matched chunk (precise retrieval on the small chunk, full context for the LLM). Each file's window
     * is emitted only once per context block ({@code expandedFiles}); later chunks of the same file
     * return null and are skipped. Non-file hits (transcripts/summaries are already short) return the
     * chunk text as-is.
     */
    private String contextTextForHit(VectorSearchService.SearchResult hit, Set<Long> expandedFiles) {
        if (ContentEmbeddingService.TYPE_FILE_CONTENT.equals(hit.sourceType()) && hit.refId() != null) {
            if (!expandedFiles.add(hit.refId())) {
                return null;   // already expanded this file's window
            }
            if (hit.chunkStart() != null) {
                try {
                    var file = persistentFileMapper.findById(hit.refId());
                    if (file != null && file.getFileContent() != null && !file.getFileContent().isBlank()) {
                        String full = file.getFileContent();
                        int chunkLen = hit.sourceText() != null ? hit.sourceText().length() : 0;
                        int s = Math.max(0, Math.min(hit.chunkStart() - retrievalWindowPad, full.length()));
                        int e = Math.max(s, Math.min(hit.chunkStart() + chunkLen + retrievalWindowPad, full.length()));
                        return full.substring(snapToSentenceStart(full, s), snapToSentenceEnd(full, e));
                    }
                } catch (Exception ex) {
                    log.debug("[PreMeetingService] window expand failed, refId={}: {}", hit.refId(), ex.getMessage());
                }
            }
        }
        return hit.sourceText();
    }

    /** Move start backward to just after the previous sentence ender, so the window begins cleanly. */
    private int snapToSentenceStart(String text, int pos) {
        for (int i = pos; i > 0; i--) {
            char c = text.charAt(i - 1);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '!' || c == '?' || c == '；') {
                return i;
            }
        }
        return 0;
    }

    /** Move end forward to the next sentence ender, so the window finishes on a whole sentence. */
    private int snapToSentenceEnd(String text, int pos) {
        for (int i = pos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '!' || c == '?' || c == '；') {
                return i + 1;
            }
        }
        return text.length();
    }

    private String diagnosticHash(String value) {
        return value == null ? "null" : Integer.toHexString(value.hashCode());
    }

    private void addSourceFromHit(
            List<TeamsBotQuerySourceVo> sources,
            Set<String> sourceKeys,
            VectorSearchService.SearchResult hit,
            String meetingTitle,
            String sourceDate) {
        if (sources.size() >= MAX_RAG_SOURCES) return;
        // Dedup by DOCUMENT (refId), not by chunk (sourceId): a file is embedded as many chunks,
        // each with a distinct sourceId but the same refId. Keying on sourceId would list the same
        // file once per retrieved chunk; keying on refId collapses them into a single citation.
        String entityId = hit.refId() != null ? String.valueOf(hit.refId())
                : hit.sessionId() != null ? hit.sessionId()
                : String.valueOf(hit.sourceId());
        String key = hit.sourceType() + ":" + entityId;
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
        if (ContentEmbeddingService.TYPE_DECISION.equals(type)) return "决策";
        if (ContentEmbeddingService.TYPE_RISK.equals(type)) return "风险";
        if (ContentEmbeddingService.TYPE_METRIC.equals(type)) return "关键指标";
        if (ContentEmbeddingService.TYPE_TOPIC.equals(type)) return "主题";
        if (ContentEmbeddingService.TYPE_CROSS_SUMMARY.equals(type)) return "跨会议概览";
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
        storeTimes.put(fileId, System.currentTimeMillis());
        return fileId;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30 * 60 * 1000) // every 30 min
    public void evictExpiredDocs() {
        long cutoff = System.currentTimeMillis() - STORE_TTL_MS;
        int removed = 0;
        for (java.util.Iterator<java.util.Map.Entry<String, Long>> it = storeTimes.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, Long> e = it.next();
            if (e.getValue() < cutoff) {
                store.remove(e.getKey());
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[PreMeetingService] evictExpiredDocs removed={}", removed);
        }
    }

    /**
     * Re-load a persisted meeting file (saved in DB) back into the in-memory store so it can be
     * re-selected for summary / export. Returns a fresh ephemeral fileId plus the previously saved
     * summary (if any) and the extracted text.
     */
    public PreMeetingSummaryVo rehydratePersistedFile(com.si.backend.entity.PersistentPreMeetingFile pf) {
        if (pf == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已删除");
        }
        String fileName = pf.getFileName() != null ? pf.getFileName() : "document";
        String ext = extension(fileName).toLowerCase();
        String text = pf.getFileContent() != null ? pf.getFileContent() : "";
        String fileId = storeDoc(fileName, ext, text, pf.getFileData());
        return PreMeetingSummaryVo.builder()
                .fileId(fileId)
                .fileName(fileName)
                .summary(pf.getSummary() != null ? pf.getSummary() : "")
                .extractedText(text)
                .build();
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
