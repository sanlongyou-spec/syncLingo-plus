package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.HotwordSuggestion;
import com.si.backend.dto.SaveAsrHotwordRequest;
import com.si.backend.facade.AsrHotwordFacade;
import com.si.backend.vo.AsrHotwordVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User-owned ASR hotword management endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/asr-hotwords")
@RequiredArgsConstructor
public class AsrHotwordController {

    private final AsrHotwordFacade facade;

    @GetMapping
    public Result<List<AsrHotwordVo>> list(@RequestParam Long userId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Boolean enabled,
                                         @RequestParam(required = false) String language,
                                         @RequestParam(required = false) String category) {
        log.info("[AsrHotwordController] list start, userId={}, keywordLen={}", userId, keyword != null ? keyword.length() : 0);
        List<AsrHotwordVo> result = facade.list(userId, keyword, enabled, language, category);
        log.info("[AsrHotwordController] list end, userId={}, count={}", userId, result.size());
        return Result.ok(result);
    }

    @PostMapping
    public Result<AsrHotwordVo> create(@RequestParam Long userId, @RequestBody SaveAsrHotwordRequest request) {
        log.info("[AsrHotwordController] create start, userId={}, phraseLen={}", userId, request.getPhrase() != null ? request.getPhrase().length() : 0);
        AsrHotwordVo result = facade.create(userId, request);
        log.info("[AsrHotwordController] create end, userId={}, id={}", userId, result.getId());
        return Result.ok(result);
    }

    @PostMapping("/batch")
    public Result<List<AsrHotwordVo>> createBatch(@RequestParam Long userId, @RequestBody List<SaveAsrHotwordRequest> requests) {
        log.info("[AsrHotwordController] createBatch start, userId={}, count={}", userId, requests.size());
        List<AsrHotwordVo> result = facade.createBatch(userId, requests);
        log.info("[AsrHotwordController] createBatch end, userId={}, count={}", userId, result.size());
        return Result.ok(result);
    }

    @PostMapping("/from-terminology/{terminologyId}")
    public Result<List<AsrHotwordVo>> createFromTerminology(@PathVariable Long terminologyId, @RequestParam Long userId) {
        log.info("[AsrHotwordController] createFromTerminology start, userId={}, terminologyId={}", userId, terminologyId);
        List<AsrHotwordVo> result = facade.createFromTerminology(userId, terminologyId);
        log.info("[AsrHotwordController] createFromTerminology end, userId={}, terminologyId={}, count={}",
                userId, terminologyId, result.size());
        return Result.ok(result);
    }

    @PostMapping("/from-meeting")
    public Result<List<AsrHotwordVo>> createFromMeeting(
            @RequestParam Long userId,
            @RequestBody com.si.backend.dto.MeetingHotwordsRequest request) {
        int nameCount = request != null && request.getNames() != null ? request.getNames().size() : 0;
        log.info("[AsrHotwordController] createFromMeeting start, userId={}, names={}", userId, nameCount);
        List<AsrHotwordVo> result = facade.createFromMeeting(
                userId,
                request != null ? request.getNames() : null,
                request != null ? request.getVenue() : null);
        log.info("[AsrHotwordController] createFromMeeting end, userId={}, created={}", userId, result.size());
        return Result.ok(result);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestParam Long userId, @RequestBody SaveAsrHotwordRequest request) {
        log.info("[AsrHotwordController] update start, id={}, userId={}", id, userId);
        facade.update(id, userId, request);
        log.info("[AsrHotwordController] update end, id={}, userId={}", id, userId);
        return Result.ok();
    }

    @PatchMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable Long id, @RequestParam Long userId, @RequestParam Boolean enabled) {
        log.info("[AsrHotwordController] updateEnabled start, id={}, userId={}, enabled={}", id, userId, enabled);
        facade.updateEnabled(id, userId, enabled);
        log.info("[AsrHotwordController] updateEnabled end, id={}, userId={}", id, userId);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @RequestParam Long userId) {
        log.info("[AsrHotwordController] delete start, id={}, userId={}", id, userId);
        facade.delete(id, userId);
        log.info("[AsrHotwordController] delete end, id={}, userId={}", id, userId);
        return Result.ok();
    }

    @GetMapping("/extract-preview/{sessionId}")
    public Result<List<HotwordSuggestion>> extractPreview(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        log.info("[AsrHotwordController] extractPreview start, sessionId={}, userId={}", sessionId, userId);
        List<HotwordSuggestion> suggestions = facade.previewExtractedHotwords(sessionId, userId);
        log.info("[AsrHotwordController] extractPreview end, sessionId={}, count={}", sessionId, suggestions.size());
        return Result.ok(suggestions);
    }

    @PostMapping("/extract-confirm")
    public Result<List<AsrHotwordVo>> extractConfirm(
            @RequestParam Long userId,
            @RequestBody List<HotwordSuggestion> selected) {
        log.info("[AsrHotwordController] extractConfirm start, userId={}, count={}", userId, selected != null ? selected.size() : 0);
        List<AsrHotwordVo> result = facade.confirmExtractedHotwords(userId, selected);
        log.info("[AsrHotwordController] extractConfirm end, userId={}, saved={}", userId, result.size());
        return Result.ok(result);
    }
}
