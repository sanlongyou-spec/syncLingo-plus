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
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.MeetingActionItemService;
import com.si.backend.service.MeetingInsightService;
import com.si.backend.service.MeetingService;
import com.si.backend.service.PreMeetingService;
import com.si.backend.util.AuthContext;
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
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.MEETING_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.OWN,
        expectedStatuses = {200, 400, 401, 403, 404})
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final MeetingNotificationFacade meetingNotificationFacade;
    private final SpeakerSummaryFacade speakerSummaryFacade;
    private final InterpretationFacade interpretationFacade;
    private final MeetingActionItemService actionItemService;
    private final MeetingInsightService meetingInsightService;
    private final PreMeetingService preMeetingService;
    private final com.si.backend.service.MeetingMemberService meetingMemberService;

    // ── P3 会议成员授权:作为安全运维功能入口,仅管理账号可调用。──
    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.USER,
            permission = com.si.backend.security.authorization.PermissionCode.OPS_EXECUTE,
            scope = com.si.backend.security.authorization.ResourceScope.ALL,
            expectedStatuses = {200, 400, 401, 403, 404})
    @GetMapping("/{meetingId}/members")
    public Result<List<com.si.backend.vo.MeetingMemberVo>> listMembers(@PathVariable Long meetingId) {
        return Result.ok(meetingMemberService.list(AuthContext.requireActor(), meetingId));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.USER,
            permission = com.si.backend.security.authorization.PermissionCode.OPS_EXECUTE,
            scope = com.si.backend.security.authorization.ResourceScope.ALL,
            expectedStatuses = {200, 400, 401, 403, 404})
    @PostMapping("/{meetingId}/members")
    public Result<com.si.backend.vo.MeetingMemberVo> assignMember(
            @PathVariable Long meetingId, @RequestBody Map<String, Object> body) {
        Long targetUserId = body.get("userId") == null ? null : Long.valueOf(String.valueOf(body.get("userId")));
        String level = body.get("accessLevel") == null ? null : String.valueOf(body.get("accessLevel"));
        return Result.ok(meetingMemberService.assign(AuthContext.requireActor(), meetingId, targetUserId, level));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.USER,
            permission = com.si.backend.security.authorization.PermissionCode.OPS_EXECUTE,
            scope = com.si.backend.security.authorization.ResourceScope.ALL,
            expectedStatuses = {200, 401, 403, 404})
    @DeleteMapping("/{meetingId}/members/{userId}")
    public Result<Void> revokeMember(@PathVariable Long meetingId, @PathVariable Long userId) {
        meetingMemberService.revoke(AuthContext.requireActor(), meetingId, userId);
        return Result.ok();
    }

    @PostMapping
    public Result<MeetingVo> create(@Valid @RequestBody CreateMeetingRequest request) {
        AuthenticatedActor actor = AuthContext.requireActor();
        log.info("[MeetingController] create, userId={}, title={}", actor.userId(), request.getTitle());
        return Result.ok(meetingService.createMeeting(
                actor, request.getTitle(),
                request.getScheduledTime(), request.getNote()));
    }

    @GetMapping
    public Result<List<MeetingVo>> list() {
        AuthenticatedActor actor = AuthContext.requireActor();
        return Result.ok(meetingService.getMeetings(actor));
    }

    @GetMapping("/{meetingId}")
    public Result<MeetingVo> get(@PathVariable Long meetingId) {
        return Result.ok(meetingService.getMeeting(AuthContext.requireActor(), meetingId));
    }

    @PostMapping("/{meetingId}/files")
    public Result<MeetingFileVo> uploadFile(
            @PathVariable Long meetingId,
            @RequestParam("file") MultipartFile file) throws IOException {
        log.info("[MeetingController] uploadFile, meetingId={}, fileName={}", meetingId, file.getOriginalFilename());
        return Result.ok(meetingService.uploadFile(AuthContext.requireActor(), meetingId, file));
    }

    @GetMapping("/{meetingId}/files")
    public Result<List<MeetingFileVo>> listFiles(@PathVariable Long meetingId) {
        return Result.ok(meetingService.getFiles(AuthContext.requireActor(), meetingId));
    }

    @DeleteMapping("/{meetingId}/files/{fileId}")
    public Result<Void> deleteFile(@PathVariable Long meetingId, @PathVariable Long fileId) {
        meetingService.deleteFile(AuthContext.requireActor(), meetingId, fileId);
        return Result.ok();
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.USER,
            permission = com.si.backend.security.authorization.PermissionCode.TEAMS_SEND,
            scope = com.si.backend.security.authorization.ResourceScope.OWN,
            expectedStatuses = {200, 400, 401, 403, 404, 502})
    @PostMapping("/{meetingId}/notification-preview")
    public Result<MeetingNotificationPreviewVo> previewNotification(
            @PathVariable Long meetingId,
            @RequestBody(required = false) MeetingNotificationPreviewRequest request) {
        return Result.ok(meetingNotificationFacade.preview(AuthContext.requireActor(), meetingId, request));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.USER,
            permission = com.si.backend.security.authorization.PermissionCode.TEAMS_SEND,
            scope = com.si.backend.security.authorization.ResourceScope.OWN,
            expectedStatuses = {200, 400, 401, 403, 404})
    @GetMapping("/{meetingId}/notification-recipients")
    public Result<List<MeetingNotificationRecipientVo>> getNotificationRecipients(@PathVariable Long meetingId) {
        return Result.ok(meetingNotificationFacade.recipients(AuthContext.requireActor(), meetingId));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.USER,
            permission = com.si.backend.security.authorization.PermissionCode.TEAMS_SEND,
            scope = com.si.backend.security.authorization.ResourceScope.OWN,
            expectedStatuses = {200, 400, 401, 403, 404, 502})
    @PostMapping("/{meetingId}/notification-send")
    public Result<MeetingNotificationSendVo> sendNotification(
            @PathVariable Long meetingId,
            @RequestBody MeetingNotificationSendRequest request) {
        return Result.ok(meetingNotificationFacade.send(AuthContext.requireActor(), meetingId, request));
    }

    @GetMapping("/{meetingId}/sessions")
    public Result<List<InterpretationSessionVo>> getSessions(@PathVariable Long meetingId) {
        return Result.ok(interpretationFacade.getSessionsByMeeting(AuthContext.requireActor(), meetingId));
    }

    @PutMapping("/{meetingId}/files/{fileId}/summary")
    public Result<Void> saveFileSummary(
            @PathVariable Long meetingId,
            @PathVariable Long fileId,
            @RequestBody Map<String, String> body) {
        meetingService.saveFileSummary(AuthContext.requireActor(), meetingId, fileId, body.getOrDefault("summary", ""));
        return Result.ok();
    }

    @GetMapping("/{meetingId}/files/{fileId}/content")
    public Result<String> getFileContent(@PathVariable Long meetingId, @PathVariable Long fileId) {
        return Result.ok(meetingService.getFileWithContent(
                AuthContext.requireActor(), meetingId, fileId).getFileContent());
    }

    /** Re-load a previously uploaded file into the in-memory store so it can be re-selected / re-summarized. */
    @PostMapping("/{meetingId}/files/{fileId}/load")
    public Result<PreMeetingSummaryVo> loadFileForSummary(@PathVariable Long meetingId, @PathVariable Long fileId) {
        log.info("[MeetingController] loadFileForSummary, meetingId={}, fileId={}", meetingId, fileId);
        return Result.ok(preMeetingService.rehydratePersistedFile(
                meetingService.getFileFull(AuthContext.requireActor(), meetingId, fileId)));
    }

    @GetMapping("/{meetingId}/files/{fileId}/download")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long meetingId, @PathVariable Long fileId) {
        var file = meetingService.getFileForDownload(AuthContext.requireActor(), meetingId, fileId);
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
        meetingService.deleteMeeting(AuthContext.requireActor(), meetingId);
        return Result.ok();
    }

    @PostMapping("/speaker-summary")
    public Result<SpeakerSummaryVo> speakerSummary(@Valid @RequestBody SpeakerSummaryRequest request) {
        return Result.ok(speakerSummaryFacade.summarize(AuthContext.requireActor(), request));
    }

    @GetMapping("/speaker-summaries/{sessionId}")
    public Result<List<SpeakerSummaryRecordVo>> getSpeakerSummaries(@PathVariable String sessionId) {
        return Result.ok(speakerSummaryFacade.getBySession(AuthContext.requireActor(), sessionId));
    }

    @PostMapping("/speaker-summaries/{id}/regenerate")
    public Result<SpeakerSummaryVo> regenerateSpeakerSummary(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String requirements = body != null ? body.get("requirements") : null;
        log.info("[MeetingController] regenerateSpeakerSummary, id={}", id);
        return Result.ok(speakerSummaryFacade.regenerate(AuthContext.requireActor(), id, requirements));
    }

    @PutMapping("/speaker-summaries/{id}")
    public Result<SpeakerSummaryRecordVo> updateSpeakerSummary(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSpeakerSummaryRequest request) {
        return Result.ok(speakerSummaryFacade.update(AuthContext.requireActor(), id, request));
    }

    // ── 行动项接口 (A5) ────────────────────────────────────────────────────────

    /** 从会话记录中提取行动项（LLM 生成并持久化） */
    @PostMapping("/sessions/{sessionId}/action-items/extract")
    public Result<List<MeetingActionItem>> extractActionItems(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> body) {
        Long meetingId = body != null && body.get("meetingId") != null
                ? Long.valueOf(body.get("meetingId").toString()) : null;
        log.info("[MeetingController] extractActionItems, sessionId={}", sessionId);
        List<MeetingActionItem> items = actionItemService.extractAndSave(
                AuthContext.requireActor(), sessionId, meetingId);
        // P1-5: also extract structured insights (decisions/risks/metrics/topics) for retrieval.
        meetingInsightService.extractAndEmbed(sessionId, meetingId);
        return Result.ok(items);
    }

    /** 查询会话的所有行动项 */
    @GetMapping("/sessions/{sessionId}/action-items")
    public Result<List<MeetingActionItem>> listActionItems(@PathVariable String sessionId) {
        return Result.ok(actionItemService.listBySessionId(AuthContext.requireActor(), sessionId));
    }

    /** 更新行动项状态（pending / done / cancelled） */
    @PatchMapping("/action-items/{id}/status")
    public Result<MeetingActionItem> updateActionItemStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        log.info("[MeetingController] updateActionItemStatus, id={}, status={}", id, status);
        return Result.ok(actionItemService.updateStatus(AuthContext.requireActor(), id, status));
    }

    /** 删除行动项 */
    @DeleteMapping("/action-items/{id}")
    public Result<Void> deleteActionItem(@PathVariable Long id) {
        log.info("[MeetingController] deleteActionItem, id={}", id);
        actionItemService.delete(AuthContext.requireActor(), id);
        return Result.ok();
    }
}
