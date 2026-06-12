package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.entity.SessionAudioRecord;
import com.si.backend.service.AudioRecordService;
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
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/audio-records")
@RequiredArgsConstructor
public class AudioRecordController {

    private final AudioRecordService audioRecordService;

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam Long userId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        List<SessionAudioRecord> items = audioRecordService.search(userId, keyword, page, size);
        long total = audioRecordService.count(userId, keyword);
        return Result.ok(Map.of("items", items, "total", total));
    }

    @PutMapping("/{id}/name")
    public Result<Void> rename(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestParam String name) {
        boolean ok = audioRecordService.rename(userId, id, name);
        return ok ? Result.ok() : Result.fail("记录不存在或无权限");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @RequestParam Long userId) {
        boolean ok = audioRecordService.delete(userId, id);
        return ok ? Result.ok() : Result.fail("记录不存在或无权限");
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @RequestParam Long userId) {
        File file = audioRecordService.getFile(userId, id);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        SessionAudioRecord record = audioRecordService.getRecord(id);
        String filename = (record != null && record.getName() != null ? record.getName() : "recording") + ".wav";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }
}
