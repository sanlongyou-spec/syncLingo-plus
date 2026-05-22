package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.facade.TeamsBotQueryFacade;
import com.si.backend.vo.TeamsBotQueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
