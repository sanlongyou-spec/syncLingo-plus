package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.facade.TeamsBotQueryFacade;
import com.si.backend.vo.TeamsBotQueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Internal endpoint used by the Microsoft Teams Bot conversational entry.
 */
@Slf4j
@RestController
@RequestMapping("/api/teams-bot")
@RequiredArgsConstructor
public class TeamsBotQueryController {

    private static final String BOT_SECRET_HEADER = "X-SyncLingo-Bot-Secret";

    private final TeamsBotQueryFacade facade;

    @PostMapping("/query")
    public Result<TeamsBotQueryResponse> query(
            @RequestHeader(value = BOT_SECRET_HEADER, required = false) String apiSecret,
            @Valid @RequestBody TeamsBotQueryRequest request
    ) {
        log.info("[TeamsBotQueryController] query start, aadId={}, upn={}, messageLen={}",
                request.getAadId(), request.getUserPrincipalName(),
                request.getMessage() != null ? request.getMessage().length() : 0);
        TeamsBotQueryResponse response = facade.query(request, apiSecret);
        log.info("[TeamsBotQueryController] query end, aadId={}, command={}, matched={}",
                request.getAadId(), response.getCommand(), response.getUserMatched());
        return Result.ok(response);
    }

    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(
            @RequestHeader(value = BOT_SECRET_HEADER, required = false) String apiSecret,
            @Valid @RequestBody TeamsBotQueryRequest request
    ) {
        log.info("[TeamsBotQueryController] queryStream start, aadId={}, messageLen={}",
                request.getAadId(), request.getMessage() != null ? request.getMessage().length() : 0);
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread.ofVirtual().start(() -> {
            try {
                facade.queryStream(request, apiSecret, emitter);
            } catch (Exception e) {
                log.error("[TeamsBotQueryController] queryStream error, aadId={}", request.getAadId(), e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
