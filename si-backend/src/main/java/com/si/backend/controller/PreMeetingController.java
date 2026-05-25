package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.common.Result;
import com.si.backend.dto.PreMeetingAttendanceRequest;
import com.si.backend.dto.PreMeetingSummarizeRequest;
import com.si.backend.service.PreMeetingService;
import com.si.backend.vo.PreMeetingAttendanceVo;
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
@RequestMapping("/api/pre-meeting")
@RequiredArgsConstructor
public class PreMeetingController {

    private final PreMeetingService preMeetingService;

    @PostMapping("/upload")
    public Result<List<PreMeetingFileVo>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        try {
            List<PreMeetingFileVo> files = preMeetingService.upload(file);
            if (files.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "压缩包中未找到可解析的 Word 或 PDF 文件");
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
        if (request == null || request.getFileId() == null || request.getFileId().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择会议安排文件");
        }
        try {
            int actualCount = request.getActualParticipants() == null ? 0 : request.getActualParticipants().size();
            log.info("[PreMeetingController] generateAttendance start, fileId={}, actualCount={}",
                    request.getFileId(), actualCount);
            PreMeetingAttendanceVo result = preMeetingService.generateAttendance(
                    request.getFileId(), request.getActualParticipants());
            log.info("[PreMeetingController] generateAttendance done, fileId={}, expectedCount={}, presentCount={}",
                    request.getFileId(), result.getExpectedCount(), result.getPresentCount());
            return Result.ok(result);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PreMeetingController] generateAttendance failed, fileId={}", request.getFileId(), e);
            throw BizException.of(ErrorCode.BAD_REQUEST, "生成实际参加情况失败：" + e.getMessage());
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

    private String buildAttendanceExportFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return baseName + "_实际参会名单.docx";
    }
}
