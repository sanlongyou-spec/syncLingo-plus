package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.CreateMeetingRequest;
import com.si.backend.dto.SpeakerSummaryRequest;
import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.service.MeetingService;
import com.si.backend.service.SpeakerSummaryService;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.MeetingFileVo;
import com.si.backend.vo.MeetingVo;
import com.si.backend.vo.SpeakerSummaryVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final SpeakerSummaryService speakerSummaryService;
    private final InterpretationFacade interpretationFacade;

    @PostMapping
    public Result<MeetingVo> create(@Valid @RequestBody CreateMeetingRequest request) {
        log.info("[MeetingController] create, userId={}, title={}", request.getUserId(), request.getTitle());
        return Result.ok(meetingService.createMeeting(
                request.getUserId(), request.getTitle(),
                request.getScheduledTime(), request.getNote()));
    }

    @GetMapping
    public Result<List<MeetingVo>> list(@RequestParam Long userId) {
        return Result.ok(meetingService.getMeetings(userId));
    }

    @GetMapping("/{meetingId}")
    public Result<MeetingVo> get(@PathVariable Long meetingId) {
        return Result.ok(meetingService.getMeeting(meetingId));
    }

    @PostMapping("/{meetingId}/files")
    public Result<MeetingFileVo> uploadFile(
            @PathVariable Long meetingId,
            @RequestParam("file") MultipartFile file) throws IOException {
        log.info("[MeetingController] uploadFile, meetingId={}, fileName={}", meetingId, file.getOriginalFilename());
        return Result.ok(meetingService.uploadFile(meetingId, file));
    }

    @GetMapping("/{meetingId}/files")
    public Result<List<MeetingFileVo>> listFiles(@PathVariable Long meetingId) {
        return Result.ok(meetingService.getFiles(meetingId));
    }

    @DeleteMapping("/{meetingId}/files/{fileId}")
    public Result<Void> deleteFile(@PathVariable Long meetingId, @PathVariable Long fileId) {
        meetingService.deleteFile(meetingId, fileId);
        return Result.ok();
    }

    @GetMapping("/{meetingId}/sessions")
    public Result<List<InterpretationSessionVo>> getSessions(@PathVariable Long meetingId) {
        return Result.ok(interpretationFacade.getSessionsByMeeting(meetingId));
    }

    @PutMapping("/{meetingId}/attendance")
    public Result<Void> saveAttendance(
            @PathVariable Long meetingId,
            @RequestBody Map<String, String> body) {
        meetingService.saveAttendance(meetingId, body.getOrDefault("attendanceJson", ""));
        return Result.ok();
    }

    @PutMapping("/{meetingId}/files/{fileId}/summary")
    public Result<Void> saveFileSummary(
            @PathVariable Long meetingId,
            @PathVariable Long fileId,
            @RequestBody Map<String, String> body) {
        meetingService.saveFileSummary(fileId, body.getOrDefault("summary", ""));
        return Result.ok();
    }

    @GetMapping("/{meetingId}/files/{fileId}/content")
    public Result<String> getFileContent(@PathVariable Long meetingId, @PathVariable Long fileId) {
        return Result.ok(meetingService.getFileWithContent(fileId).getFileContent());
    }

    @GetMapping("/{meetingId}/files/{fileId}/download")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long meetingId, @PathVariable Long fileId) {
        var file = meetingService.getFileForDownload(fileId);
        byte[] data = file.getFileData();
        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String encoded = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    @DeleteMapping("/{meetingId}")
    public Result<Void> delete(@PathVariable Long meetingId) {
        log.info("[MeetingController] delete, meetingId={}", meetingId);
        meetingService.deleteMeeting(meetingId);
        return Result.ok();
    }

    @PostMapping("/speaker-summary")
    public Result<SpeakerSummaryVo> speakerSummary(@Valid @RequestBody SpeakerSummaryRequest request) {
        log.info("[MeetingController] speakerSummary, sessionId={}, speaker={}, textLen={}",
                request.getSessionId(), request.getSpeakerName(), request.getText().length());
        String name = request.getSpeakerName() != null && !request.getSpeakerName().isBlank()
                ? request.getSpeakerName()
                : (request.getSpeakerId() != null ? request.getSpeakerId() : "未知发言人");
        SpeakerSummaryVo vo = speakerSummaryService.summarize(
                name, request.getText(), request.getSessionId(), request.getSpeakerId(), request.getRequirements());
        return Result.ok(vo);
    }

    @GetMapping("/speaker-summaries/{sessionId}")
    public Result<List<SpeakerSummaryRecord>> getSpeakerSummaries(@PathVariable String sessionId) {
        return Result.ok(speakerSummaryService.getBySession(sessionId));
    }

    @PostMapping("/speaker-summaries/{id}/regenerate")
    public Result<SpeakerSummaryVo> regenerateSpeakerSummary(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String requirements = body != null ? body.get("requirements") : null;
        log.info("[MeetingController] regenerateSpeakerSummary, id={}", id);
        return Result.ok(speakerSummaryService.regenerate(id, requirements));
    }
}
