package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.common.Result;
import com.si.backend.dto.PreMeetingAttendanceRequest;
import com.si.backend.dto.PreMeetingChatRequest;
import com.si.backend.dto.PreMeetingSummarizeRequest;
import com.si.backend.service.HotwordExtractionService;
import com.si.backend.service.PreMeetingService;
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
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/pre-meeting")
@RequiredArgsConstructor
public class PreMeetingController {

    private final PreMeetingService preMeetingService;
    private final HotwordExtractionService hotwordExtractionService;
    private final com.si.backend.service.AsrHotwordService asrHotwordService;

    @PostMapping("/upload")
    public Result<List<PreMeetingFileVo>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        try {
            List<PreMeetingFileVo> files = preMeetingService.upload(file);
            if (files.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "压缩包中未找到可解析的 Word 或 PDF 文件");
            }
            // Async hotword extraction from uploaded file content
            if (userId != null) {
                final Long finalUserId = userId;
                final List<String> fileIds = files.stream().map(PreMeetingFileVo::getFileId).toList();
                CompletableFuture.runAsync(() -> fileIds.forEach(fileId -> {
                    try {
                        String text = preMeetingService.getDocText(fileId);
                        hotwordExtractionService.extractAndSaveFromText(text, finalUserId);
                        // Add expected participant names + meeting venue from the agenda as hotwords.
                        PreMeetingService.MeetingEntities entities = preMeetingService.extractMeetingEntities(fileId);
                        asrHotwordService.saveMeetingEntities(
                                finalUserId, entities.participantNames(), entities.venue(), "MEETING_AGENDA");
                    } catch (Exception e) {
                        log.warn("[PreMeetingController] hotword extraction failed for fileId={}", fileId, e);
                    }
                }));
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
            long userId = request.getUserId() != null ? request.getUserId() : 0L;
            PreMeetingSummaryVo result = preMeetingService.summarize(
                    request.getFileId(), request.getRequirements(), userId);
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

    /** Save the 应到 list parsed from a freshly-uploaded 会议安排 onto the meeting (survives sessions). */
    @PostMapping("/attendance/save-expected")
    public Result<Integer> saveExpectedParticipants(@RequestBody Map<String, Object> body) {
        String fileId = body.get("fileId") != null ? body.get("fileId").toString() : null;
        Long meetingId = body.get("meetingId") != null ? Long.valueOf(body.get("meetingId").toString()) : null;
        if (fileId == null || fileId.isBlank() || meetingId == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少 fileId 或 meetingId");
        }
        try {
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
        if (request == null || request.getFileId() == null || request.getFileId().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择会议安排文件");
        }
        try {
            int actualCount = request.getActualParticipants() == null ? 0 : request.getActualParticipants().size();
            log.info("[PreMeetingController] exportAttendance start, fileId={}, actualCount={}",
                    request.getFileId(), actualCount);
            byte[] docxBytes = preMeetingService.buildAttendanceExportDocx(
                    request.getFileId(), request.getActualParticipants());
            String fileName = buildAttendanceExportFileName(preMeetingService.getFileName(request.getFileId()));
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
            log.info("[PreMeetingController] exportAttendance done, fileId={}, bytes={}",
                    request.getFileId(), docxBytes.length);
            return ResponseEntity.ok().headers(headers).body(docxBytes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] exportAttendance failed, fileId={}", request.getFileId(), e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "导出实际参加情况失败：" + e.getMessage());
        }
    }

    @PostMapping("/chat")
    public Result<PreMeetingChatVo> chat(@RequestBody PreMeetingChatRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        try {
            PreMeetingChatVo result;
            if (request.isCrossMeeting()) {
                result = preMeetingService.chatCrossMeeting(
                        request.getUserId(),
                        request.getQuestion(),
                        request.getHistory(),
                        request.getDays());
            } else {
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
            @RequestParam long userId,
            @RequestParam(defaultValue = "365") int days) {
        return Result.ok(preMeetingService.getDailyUsage(userId, days));
    }

    @PostMapping("/export/{fileId}")
    public ResponseEntity<byte[]> export(
            @PathVariable String fileId,
            @RequestBody Map<String, String> body) {
        String summary = body.getOrDefault("summary", "");
        try {
            byte[] docxBytes = preMeetingService.buildExportDocx(fileId, summary);
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
            byte[] pdfBytes = preMeetingService.buildExportPdf(fileId, summary);
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

    private String buildAttendanceExportFileName(String fileName) {
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
