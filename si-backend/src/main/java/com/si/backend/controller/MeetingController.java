package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.CreateMeetingRequest;
import com.si.backend.dto.MeetingNotificationPreviewRequest;
import com.si.backend.dto.MeetingNotificationSendRequest;
import com.si.backend.dto.SpeakerSummaryRequest;
import com.si.backend.dto.UpdateSpeakerSummaryRequest;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.facade.MeetingNotificationFacade;
import com.si.backend.facade.SpeakerSummaryFacade;
import com.si.backend.service.MeetingActionItemService;
import com.si.backend.service.MeetingInsightService;
import com.si.backend.service.MeetingService;
import com.si.backend.service.PreMeetingService;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.MeetingFileVo;
import com.si.backend.vo.MeetingNotificationPreviewVo;
import com.si.backend.vo.MeetingNotificationRecipientVo;
import com.si.backend.vo.MeetingNotificationSendVo;
import com.si.backend.vo.MeetingVo;
import com.si.backend.vo.PreMeetingSummaryVo;
import com.si.backend.vo.SpeakerSummaryRecordVo;
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
    private final SpeakerSummaryFacade speakerSummaryFacade;
    private final InterpretationFacade interpretationFacade;
    private final MeetingActionItemService actionItemService;
    private final MeetingInsightService meetingInsightService;
    private final MeetingNotificationFacade meetingNotificationFacade;
    private final PreMeetingService preMeetingService;

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

    @PostMapping("/{meetingId}/notification-preview")
    public Result<MeetingNotificationPreviewVo> previewNotification(
            @PathVariable Long meetingId,
            @RequestBody MeetingNotificationPreviewRequest request) {
        return Result.ok(meetingNotificationFacade.preview(meetingId, request));
    }

    @GetMapping("/{meetingId}/notification-recipients")
    public Result<List<MeetingNotificationRecipientVo>> notificationRecipients(@PathVariable Long meetingId) {
        return Result.ok(meetingNotificationFacade.recipients(meetingId));
    }

    @PostMapping("/{meetingId}/notification-send")
    public Result<MeetingNotificationSendVo> sendNotification(
            @PathVariable Long meetingId,
            @RequestBody MeetingNotificationSendRequest request) {
        return Result.ok(meetingNotificationFacade.send(meetingId, request));
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

    /** Re-load a previously uploaded file into the in-memory store so it can be re-selected / re-summarized. */
    @PostMapping("/{meetingId}/files/{fileId}/load")
    public Result<PreMeetingSummaryVo> loadFileForSummary(@PathVariable Long meetingId, @PathVariable Long fileId) {
        log.info("[MeetingController] loadFileForSummary, meetingId={}, fileId={}", meetingId, fileId);
        return Result.ok(preMeetingService.rehydratePersistedFile(meetingService.getFileFull(fileId)));
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
        return Result.ok(speakerSummaryFacade.summarize(request));
    }

    @GetMapping("/speaker-summaries/{sessionId}")
    public Result<List<SpeakerSummaryRecordVo>> getSpeakerSummaries(@PathVariable String sessionId) {
        return Result.ok(speakerSummaryFacade.getBySession(sessionId));
    }

    @PostMapping("/speaker-summaries/{id}/regenerate")
    public Result<SpeakerSummaryVo> regenerateSpeakerSummary(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String requirements = body != null ? body.get("requirements") : null;
        log.info("[MeetingController] regenerateSpeakerSummary, id={}", id);
        return Result.ok(speakerSummaryFacade.regenerate(id, requirements));
    }

    @PutMapping("/speaker-summaries/{id}")
    public Result<SpeakerSummaryRecordVo> updateSpeakerSummary(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSpeakerSummaryRequest request) {
        return Result.ok(speakerSummaryFacade.update(id, request));
    }

    // ── 行动项接口 (A5) ────────────────────────────────────────────────────────

    /** 从会话记录中提取行动项（LLM 生成并持久化） */
    @PostMapping("/sessions/{sessionId}/action-items/extract")
    public Result<List<MeetingActionItem>> extractActionItems(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> body) {
        Long meetingId = body != null && body.get("meetingId") != null
                ? Long.valueOf(body.get("meetingId").toString()) : null;
        Long userId = body != null && body.get("userId") != null
                ? Long.valueOf(body.get("userId").toString()) : null;
        log.info("[MeetingController] extractActionItems, sessionId={}", sessionId);
        List<MeetingActionItem> items = actionItemService.extractAndSave(sessionId, meetingId, userId);
        // P1-5: also extract structured insights (decisions/risks/metrics/topics) for retrieval.
        meetingInsightService.extractAndEmbed(sessionId, meetingId);
        return Result.ok(items);
    }

    /** 查询会话的所有行动项 */
    @GetMapping("/sessions/{sessionId}/action-items")
    public Result<List<MeetingActionItem>> listActionItems(@PathVariable String sessionId) {
        return Result.ok(actionItemService.listBySessionId(sessionId));
    }

    /** 更新行动项状态（pending / done / cancelled） */
    @PatchMapping("/action-items/{id}/status")
    public Result<MeetingActionItem> updateActionItemStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        log.info("[MeetingController] updateActionItemStatus, id={}, status={}", id, status);
        return Result.ok(actionItemService.updateStatus(id, status));
    }

    /** 删除行动项 */
    @DeleteMapping("/action-items/{id}")
    public Result<Void> deleteActionItem(@PathVariable Long id) {
        log.info("[MeetingController] deleteActionItem, id={}", id);
        actionItemService.delete(id);
        return Result.ok();
    }
}
