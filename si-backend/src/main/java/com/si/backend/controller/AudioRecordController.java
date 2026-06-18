package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.SessionAudioRecord;
import com.si.backend.security.AccessLevel;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.AudioRecordService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.AudioRecordVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.AUDIO_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 401, 403, 404})
@RequestMapping("/api/audio-records")
@RequiredArgsConstructor
public class AudioRecordController {

    private final AudioRecordService audioRecordService;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) Long meetingId) {
        AuthenticatedActor actor = AuthContext.requireActor();
        Long audioOwnerId = actor.userId();
        if (meetingId != null) {
            Meeting meeting = resourceOwnershipPolicy.requireMeetingAccess(actor, meetingId, AccessLevel.VIEW);
            audioOwnerId = meeting.getUserId();
        }
        var items = audioRecordService.search(audioOwnerId, meetingId, keyword, page, size)
                .stream()
                .map(AudioRecordVo::from)
                .toList();
        long total = audioRecordService.count(audioOwnerId, meetingId, keyword);
        return Result.ok(Map.of("items", items, "total", total));
    }

    @PutMapping("/{id}/name")
    public Result<Void> rename(
            @PathVariable Long id,
            @RequestParam String name) {
        AuthenticatedActor actor = AuthContext.requireActor();
        SessionAudioRecord record = requireAudioManageAccess(actor, id);
        boolean ok = audioRecordService.rename(record.getUserId(), id, name);
        return ok ? Result.ok() : Result.fail("记录不存在或无权限");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        AuthenticatedActor actor = AuthContext.requireActor();
        SessionAudioRecord record = requireAudioManageAccess(actor, id);
        boolean ok = audioRecordService.delete(record.getUserId(), id);
        return ok ? Result.ok() : Result.fail("记录不存在或无权限");
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        AuthenticatedActor actor = AuthContext.requireActor();
        SessionAudioRecord record = requireAudioViewAccess(actor, id);
        File file = audioRecordService.getFile(record.getUserId(), id);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        String filename = (record != null && record.getName() != null ? record.getName() : "recording") + ".wav";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }

    private SessionAudioRecord requireAudioViewAccess(AuthenticatedActor actor, Long id) {
        SessionAudioRecord record = audioRecordService.getRecord(id);
        if (record != null && record.getMeetingId() != null) {
            resourceOwnershipPolicy.requireMeetingAccess(actor, record.getMeetingId(), AccessLevel.VIEW);
            return record;
        }
        return resourceOwnershipPolicy.requireOwnedAudio(actor, id);
    }

    private SessionAudioRecord requireAudioManageAccess(AuthenticatedActor actor, Long id) {
        SessionAudioRecord record = audioRecordService.getRecord(id);
        if (record != null && record.getMeetingId() != null) {
            resourceOwnershipPolicy.requireMeetingAccess(actor, record.getMeetingId(), AccessLevel.OPERATE);
            return record;
        }
        return resourceOwnershipPolicy.requireOwnedAudio(actor, id);
    }
}
