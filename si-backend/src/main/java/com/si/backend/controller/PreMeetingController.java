package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.common.Result;
import com.si.backend.dto.PreMeetingAttendanceRequest;
import com.si.backend.dto.PreMeetingChatRequest;
import com.si.backend.dto.PreMeetingSummarizeRequest;
import com.si.backend.service.MeetingMaterialExtractionService;
import com.si.backend.service.PreMeetingService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.PreMeetingAttendanceVo;
import com.si.backend.vo.PreMeetingChatVo;
import com.si.backend.vo.PreMeetingDailyUsageVo;
import com.si.backend.vo.PreMeetingFileVo;
import com.si.backend.vo.PreMeetingSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.PRE_MEETING_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.OWN_OR_SELF,
        expectedStatuses = {200, 400, 401, 403, 404})
@RequestMapping("/api/pre-meeting")
@RequiredArgsConstructor
public class PreMeetingController {

    private final PreMeetingService preMeetingService;
    private final MeetingMaterialExtractionService meetingMaterialExtractionService;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    @PostMapping("/upload")
    public Result<List<PreMeetingFileVo>> upload(
            @RequestParam("file") MultipartFile file) {
        Long userId = AuthContext.requireActor().userId();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        try {
            List<PreMeetingFileVo> files = preMeetingService.upload(file);
            if (files.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "压缩包中未找到可解析的 Word 或 PDF 文件");
            }
            for (PreMeetingFileVo uploaded : files) {
                String fileId = uploaded.getFileId();
                meetingMaterialExtractionService.enqueueFromPreMeetingFile(
                        userId,
                        fileId,
                        uploaded.getFileName(),
                        preMeetingService.getDocText(fileId),
                        preMeetingService.extractMeetingEntities(fileId));
            }
            return Result.ok(files);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] upload failed, fileName={}, errorType={}",
                    file.getOriginalFilename(), e.getClass().getName(), e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "文件解析失败：" + e.getMessage());
        }
    }

    @PostMapping("/summarize")
    public Result<PreMeetingSummaryVo> summarize(@RequestBody PreMeetingSummarizeRequest request) {
        try {
            AuthenticatedActor actor = AuthContext.requireActor();
            long userId = actor.userId();
            if (request.getMeetingId() != null) {
                resourceOwnershipPolicy.requireOwnedMeeting(actor, request.getMeetingId());
            }
            PreMeetingSummaryVo result = preMeetingService.summarize(
                    request.getFileId(), request.getRequirements(), userId, request.getMeetingId());
            return Result.ok(result);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[PreMeetingController] summarize failed", e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "生成总结失败：" + e.getMessage());
        }
    }

    @PostMapping("/attendance")
    public Result<PreMeetingAttendanceVo> generateAttendance(@RequestBody PreMeetingAttendanceRequest request) {
        boolean hasFile = request != null && request.getFileId() != null && !request.getFileId().isBlank();
        boolean hasMeeting = request != null && request.getMeetingId() != null;
        if (!hasFile && !hasMeeting) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请上传会议安排或选择已保存应到名单的会议");
        }
        try {
            if (hasMeeting) {
                resourceOwnershipPolicy.requireOwnedMeeting(AuthContext.requireActor(), request.getMeetingId());
            }
            int actualCount = request.getActualParticipants() == null ? 0 : request.getActualParticipants().size();
            // Prefer the freshly-uploaded file; otherwise fall back to the meeting's saved 应到 list.
            PreMeetingAttendanceVo result = hasFile
                    ? preMeetingService.generateAttendance(request.getFileId(), request.getActualParticipants())
                    : preMeetingService.generateAttendanceFromMeeting(request.getMeetingId(), request.getActualParticipants());
            log.info("[PreMeetingController] generateAttendance done, fileId={}, meetingId={}, actualCount={}, expectedCount={}, presentCount={}",
                    request.getFileId(), request.getMeetingId(), actualCount, result.getExpectedCount(), result.getPresentCount());
            return Result.ok(result);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] generateAttendance failed, fileId={}, meetingId={}",
                    request.getFileId(), request.getMeetingId(), e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "生成实际参加情况失败：" + e.getMessage());
        }
    }

    /** Build a standalone Word from a history summary/总结 text (for sending to Teams). */
    @PostMapping("/summary-doc")
    public ResponseEntity<byte[]> summaryDoc(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "会议总结");
        String summary = body.getOrDefault("summary", "");
        try {
            byte[] docxBytes = preMeetingService.buildSummaryDocx(title, summary, parseFormat(body));
            String fileName = (title == null || title.isBlank() ? "会议总结" : title) + ".docx";
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
            return ResponseEntity.ok().headers(headers).body(docxBytes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] summaryDoc failed", e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "生成总结 Word 失败：" + e.getMessage());
        }
    }

    /** Build a 会议总结 PDF (same 仿宋18/TNR16 format as the AI summary) for sending to Teams. */
    @PostMapping("/summary-pdf")
    public ResponseEntity<byte[]> summaryPdf(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "会议总结");
        String summary = body.getOrDefault("summary", "");
        try {
            byte[] pdfBytes = preMeetingService.buildSummaryPdf(title, summary);
            return pdfResponse(pdfBytes, (title == null || title.isBlank() ? "会议总结" : title) + ".pdf");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] summaryPdf failed", e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "生成总结 PDF 失败：" + e.getMessage());
        }
    }

    /** Build a 发言摘要 PDF (会议名 / 发言人小标题 / 正文 / 日期 / 整理) for sending to Teams. */
    @PostMapping("/speaker-summary-pdf")
    public ResponseEntity<byte[]> speakerSummaryPdf(@RequestBody Map<String, String> body) {
        String meetingName = body.getOrDefault("meetingName", "会议");
        String speakerName = body.getOrDefault("speakerName", "");
        String dateText = body.getOrDefault("dateText", "");
        String content = body.getOrDefault("body", "");
        int sequence = parseIntOrZero(body.get("sequence"));
        try {
            byte[] pdfBytes = preMeetingService.buildSpeakerSummaryPdf(meetingName, speakerName, sequence, dateText, content);
            String fileName = (speakerName == null || speakerName.isBlank() ? "发言摘要" : speakerName + "-发言摘要") + ".pdf";
            return pdfResponse(pdfBytes, fileName);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] speakerSummaryPdf failed", e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "生成发言摘要 PDF 失败：" + e.getMessage());
        }
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdfBytes, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    /** Save the 应到 list parsed from a freshly-uploaded 会议安排 onto the meeting (survives sessions). */
    @PostMapping("/attendance/save-expected")
    public Result<Integer> saveExpectedParticipants(@RequestBody Map<String, Object> body) {
        String fileId = body.get("fileId") != null ? body.get("fileId").toString() : null;
        Long meetingId = body.get("meetingId") != null ? Long.valueOf(body.get("meetingId").toString()) : null;
        if (fileId == null || fileId.isBlank() || meetingId == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少 fileId 或 meetingId");
        }
        try {
            resourceOwnershipPolicy.requireOwnedMeeting(AuthContext.requireActor(), meetingId);
            int count = preMeetingService.saveExpectedParticipants(fileId, meetingId);
            return Result.ok(count);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] saveExpectedParticipants failed, fileId={}, meetingId={}", fileId, meetingId, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "保存应到名单失败：" + e.getMessage());
        }
    }

    @PostMapping("/attendance/export")
    public ResponseEntity<byte[]> exportAttendance(@RequestBody PreMeetingAttendanceRequest request) {
        boolean hasFile = request != null && request.getFileId() != null && !request.getFileId().isBlank();
        boolean hasMeeting = request != null && request.getMeetingId() != null;
        if (!hasFile && !hasMeeting) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择会议安排文件或已保存应到名单的会议");
        }
        try {
            if (hasMeeting) {
                resourceOwnershipPolicy.requireOwnedMeeting(AuthContext.requireActor(), request.getMeetingId());
            }
            int actualCount = request.getActualParticipants() == null ? 0 : request.getActualParticipants().size();
            log.info("[PreMeetingController] exportAttendance start, fileId={}, meetingId={}, actualCount={}",
                    request.getFileId(), request.getMeetingId(), actualCount);
            byte[] docxBytes = preMeetingService.buildAttendanceExportDocx(
                    request.getFileId(), request.getMeetingId(), request.getActualParticipants());
            String sourceName = hasFile
                    ? preMeetingService.getFileName(request.getFileId())
                    : preMeetingService.getMeetingTitle(request.getMeetingId());
            String fileName = buildAttendanceExportFileName(sourceName);
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
            log.info("[PreMeetingController] exportAttendance done, fileId={}, meetingId={}, bytes={}",
                    request.getFileId(), request.getMeetingId(), docxBytes.length);
            return ResponseEntity.ok().headers(headers).body(docxBytes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] exportAttendance failed, fileId={}, meetingId={}",
                    request.getFileId(), request.getMeetingId(), e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "导出实际参加情况失败：" + e.getMessage());
        }
    }

    @PostMapping("/chat")
    public Result<PreMeetingChatVo> chat(@RequestBody PreMeetingChatRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        try {
            AuthenticatedActor actor = AuthContext.requireActor();
            PreMeetingChatVo result;
            if (request.isCrossMeeting()) {
                result = preMeetingService.chatCrossMeeting(
                        actor.userId(),
                        request.getQuestion(),
                        request.getHistory(),
                        request.getDays());
            } else {
                if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
                    resourceOwnershipPolicy.requireOwnedSession(actor, request.getSessionId());
                }
                result = preMeetingService.chat(
                        request.getFileId(),
                        request.getSessionId(),
                        request.getQuestion(),
                        request.getHistory());
            }
            return Result.ok(result);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] chat failed, crossMeeting={}", request.isCrossMeeting(), e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "问答失败：" + e.getMessage());
        }
    }

    @GetMapping("/usage")
    public Result<List<PreMeetingDailyUsageVo>> getUsage(
            @RequestParam(defaultValue = "365") int days) {
        return Result.ok(preMeetingService.getDailyUsage(AuthContext.requireActor().userId(), days));
    }

    @PostMapping("/export/{fileId}")
    public ResponseEntity<byte[]> export(
            @PathVariable String fileId,
            @RequestBody Map<String, String> body) {
        String summary = body.getOrDefault("summary", "");
        try {
            byte[] docxBytes = preMeetingService.buildExportDocx(fileId, summary, parseFormat(body));
            String fileName = preMeetingService.getFileName(fileId);
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
            return ResponseEntity.ok().headers(headers).body(docxBytes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] export failed, fileId={}", fileId, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "导出失败：" + e.getMessage());
        }
    }

    @PostMapping("/export/pdf/{fileId}")
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable String fileId,
            @RequestBody Map<String, String> body) {
        String summary = body.getOrDefault("summary", "");
        try {
            byte[] pdfBytes = preMeetingService.buildExportPdf(fileId, summary, parseFormat(body));
            String fileName = preMeetingService.getFileName(fileId);
            int dot = fileName.lastIndexOf('.');
            String pdfName = (dot > 0 ? fileName.substring(0, dot) : fileName) + ".pdf";
            String encoded = URLEncoder.encode(pdfName, StandardCharsets.UTF_8).replace("+", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
            return ResponseEntity.ok().headers(headers).body(pdfBytes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] exportPdf failed, fileId={}", fileId, e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "导出 PDF 失败：" + e.getMessage());
        }
    }

    /** Build the summary format options from the request body (bodyFont / bodySize / headingSize). */
    private PreMeetingService.SummaryFormat parseFormat(Map<String, String> body) {
        if (body == null) return PreMeetingService.SummaryFormat.INHERIT;
        String font = body.get("bodyFont");
        int bodySize = parseIntOrZero(body.get("bodySize"));
        int headingSize = parseIntOrZero(body.get("headingSize"));
        if ((font == null || font.isBlank()) && bodySize <= 0 && headingSize <= 0) {
            return PreMeetingService.SummaryFormat.INHERIT;
        }
        return new PreMeetingService.SummaryFormat(font, bodySize, headingSize);
    }

    private int parseIntOrZero(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String buildAttendanceExportFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            fileName = "实际参会名单";
        }
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        // Keep the original name (incl. date, title and place names) intact, only swap
        // 「会议通知」→「参会情况」. If the source name has no 「会议通知」, fall back to a suffix.
        String renamed = baseName.contains("会议通知")
                ? baseName.replace("会议通知", "参会情况")
                : baseName + "_参会情况";
        return renamed + ".docx";
    }
}
